<#
  Offline tests for the normalized decision trace capture (synthetic fixtures only; never calls adb).
  Run: powershell -NoProfile -ExecutionPolicy Bypass -File tools\phone\Test-StrlixDecisionTrace.ps1
#>
$ErrorActionPreference = 'Continue'
Import-Module (Join-Path $PSScriptRoot 'StrlixPhoneDiag.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'StrlixDecisionTrace.psm1') -Force
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$fixtureDir = Join-Path $PSScriptRoot 'fixtures\decision-trace'
$script:passed = 0
$script:failed = @()

function Check([string]$Name, [scriptblock]$Body) {
    try {
        $result = & $Body
        if ($result -eq $true) { $script:passed++ } else { $script:failed += "$Name (returned '$result')" }
    } catch { $script:failed += "$Name (threw: $($_.Exception.Message))" }
}

$logcat = Get-Content -LiteralPath (Join-Path $fixtureDir 'logcat.txt') -Raw
$records = @(ConvertFrom-StrlixLogcat -LogcatText $logcat -ExpectedPid '24680' -MaxRecords 20)
Check 'fixture retains only six allowlisted Strlix records' { $records.Count -eq 6 }

$decision = $records | Where-Object event -eq 'enforcement_decision' | Select-Object -First 1
Check 'decision keeps timestamp and policy fields' { $decision.timestamp -eq '1791730427.219' -and $decision.package -eq 'com.google.android.youtube' -and $decision.surface -eq 'SHORT_FORM_PLAYER' -and $decision.decision -eq 'BLOCK' -and $decision.code -eq 'STRUCTURED_SURFACE_BLOCK' }
Check 'decision splits count and limit' { $decision.count -eq 2 -and $decision.limit -eq 5 }
Check 'decision omits non-allowlisted activity data' { -not $decision.Contains('reason') -and -not $decision.Contains('evidence') -and -not $decision.Contains('scopeClause') }

$lifecycle = $records | Where-Object event -eq 'overlay_lifecycle' | Select-Object -First 1
Check 'overlay trace keeps transition timing and safe package identity' { $lifecycle.action -eq 'away_pending' -and $lifecycle.eventPackage -eq 'miui.home' -and $lifecycle.targetPackage -eq 'com.google.android.youtube' -and $lifecycle.elapsedMs -eq 480 -and $lifecycle.awayMs -eq 11 }
Check 'overlay trace omits root, page, reason, and source' { -not $lifecycle.Contains('root') -and -not $lifecycle.Contains('blockedPage') -and -not $lifecycle.Contains('currentPage') -and -not $lifecycle.Contains('reason') -and -not $lifecycle.Contains('source') }

$overlayShown = $records | Where-Object event -eq 'overlay_shown' | Select-Object -First 1
Check 'overlay show event is normalized' { $overlayShown.kind -eq 'BLOCK' -and $overlayShown.targetPackage -eq 'com.google.android.youtube' }
$overlayHidden = $records | Where-Object event -eq 'overlay_hidden' | Select-Object -First 1
Check 'allowed transition records the package that cleared the overlay' { $overlayHidden.kind -eq 'BLOCK' -and $overlayHidden.package -eq 'com.miui.home' }
$classifier = $records | Where-Object event -eq 'classifier_result' | Select-Object -First 1
Check 'classifier decision keeps confidence but drops free text' { $classifier.decision -eq 'WARN' -and $classifier.confidence -eq 0.7 -and -not $classifier.Contains('reason') }
$quota = $records | Where-Object event -eq 'short_quota' | Select-Object -First 1
Check 'quota trace keeps counters but not video title' { $quota.count -eq 2 -and $quota.limit -eq 5 -and $quota.counted -and $quota.durationSeconds -eq 35 -and -not $quota.Contains('title') }

$serialized = ($records | ConvertTo-Json -Depth 6 -Compress)
Check 'serialized trace excludes fixture private text and URLs' { $serialized -notmatch 'PRIVATE_|private\.example|https?://' }
Check 'different PID and unrecognized tag are excluded' { $serialized -notmatch 'com\.whatsapp|PRIVATE_UNRELATED|PRIVATE_DIFFERENT_PROCESS' }
Check 'malformed logcat lines are ignored' { $serialized -notmatch 'PRIVATE_MALFORMED_LINE' }

$captureScript = Join-Path $PSScriptRoot 'Capture-StrlixDecisionTrace.ps1'
$testOut = Join-Path $repoRoot ('.tmp-phone-verify-trace-selftest-' + [guid]::NewGuid().ToString('N'))
try {
    $runOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $captureScript -FixtureDir $fixtureDir -Label fixture -OutRoot $testOut 2>&1 | ForEach-Object { "$_" }
    $runExit = $LASTEXITCODE
    Check 'fixture capture exits 0' { $runExit -eq 0 }
    $captureDirectories = @(Get-ChildItem -LiteralPath $testOut -Directory -ErrorAction SilentlyContinue)
    Check 'fixture capture creates one unique output directory' { $captureDirectories.Count -eq 1 }
    if ($captureDirectories.Count -eq 1) {
        $outputNames = @(Get-ChildItem -LiteralPath $captureDirectories[0].FullName -File | ForEach-Object Name | Sort-Object)
        Check 'capture writes only normalized trace and summary' { ($outputNames -join ',') -eq 'summary.json,trace.jsonl' }
        $tracePath = Join-Path $captureDirectories[0].FullName 'trace.jsonl'
        $summaryPath = Join-Path $captureDirectories[0].FullName 'summary.json'
        $writtenTrace = Get-Content -LiteralPath $tracePath -Raw
        $writtenSummary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        Check 'written trace remains free of raw private fixture values' { $writtenTrace -notmatch 'PRIVATE_|private\.example|https?://|reason=|evidence=|scopeClause=|title=|blockedPage=|currentPage=' }
        Check 'summary marks raw logcat unsaved and records count' { -not $writtenSummary.raw_logcat_saved -and $writtenSummary.normalized_record_count -eq 6 -and $writtenSummary.pid_filter_used }
        Check 'summary masks fixture device serial' { $writtenSummary.device_serial_masked -ne 'TRACEFIXTURE01' -and $writtenSummary.device_serial_masked -match '\*' }
    }
    & git -C $repoRoot check-ignore -q -- ((Split-Path -Leaf $testOut) + '/x/trace.jsonl')
    Check 'fixture output root matches ignored temp pattern' { $LASTEXITCODE -eq 0 }
} finally {
    if (Test-Path -LiteralPath $testOut) { Remove-Item -LiteralPath $testOut -Recurse -Force }
}

$oldCapture = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Capture-StrlixDiag.ps1') -Raw
$phoneModule = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'StrlixPhoneDiag.psm1') -Raw
Check 'original phone diagnostic capture remains free of logcat' { $oldCapture -notmatch 'logcat' -and $phoneModule -notmatch 'logcat' }
Check 'new capture never clears the log buffer or disables PID filtering' {
    $captureText = Get-Content -LiteralPath $captureScript -Raw
    $captureText -match 'logcat.*-d.*--pid=' -and $captureText -notmatch 'logcat[^\r\n]*\s-c(?:\s|$)'
}

"passed=$script:passed failed=$($script:failed.Count)"
$script:failed | ForEach-Object { "FAIL: $_" }
if ($script:failed.Count) { exit 1 } else { exit 0 }
