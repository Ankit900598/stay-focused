<#
  Offline tests for tools/phone (synthetic fixtures only; never calls adb).
  Run: powershell -NoProfile -ExecutionPolicy Bypass -File tools\phone\Test-StrlixPhoneDiag.ps1
#>
# Continue: child-script stderr must not abort the run; Check() catches real throws.
$ErrorActionPreference = 'Continue'
Import-Module (Join-Path $PSScriptRoot 'StrlixPhoneDiag.psm1') -Force
$fixtures = Join-Path $PSScriptRoot 'fixtures'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$script:passed = 0
$script:failed = @()
function Check([string]$Name, [scriptblock]$Body) {
    try {
        $ok = & $Body
        if ($ok -eq $true) { $script:passed++ } else { $script:failed += "$Name (returned '$ok')" }
    } catch { $script:failed += "$Name (threw: $($_.Exception.Message))" }
}
function Throws([scriptblock]$Body) { try { & $Body | Out-Null; $false } catch { $true } }

$foreignNames = @('Accessibility menu', 'Private Bank Reader', 'privatebank', 'BankReaderService',
    'vpnhelper', 'VpnA11yService', 'AccessibilityMenuService', 'com.android.settings')
function Leaks([string]$Text) { @($foreignNames | Where-Object { $Text -like "*$_*" }) }

function A11y([string]$Name) {
    Get-StrlixAccessibilityState -DumpsysText (Get-Content (Join-Path $fixtures "accessibility\$Name.txt") -Raw)
}

# --- accessibility states are distinguished -------------------------------------------
$bound = A11y 'bound'
Check 'bound: enabled+bound, not crashed' { $bound.Enabled -and $bound.Bound -and -not $bound.Crashed -and -not $bound.Binding -and $bound.Verdict -eq 'bound' }
Check 'bound: entry is the Strlix label with its event types' { $bound.BoundEntry -like 'Service`[label=PhoneCodex Focus Detection*TYPE_WINDOWS_CHANGED*' }

$crashed = A11y 'crashed'
Check 'crashed: enabled, not bound, crashed' { $crashed.Enabled -and -not $crashed.Bound -and $crashed.Crashed -and $crashed.Verdict -eq 'crashed' }

$binding = A11y 'binding'
Check 'binding: short component form recognised' { $binding.Enabled -and $binding.Binding -and -not $binding.Bound -and $binding.Verdict -eq 'binding' }

$disabled = A11y 'disabled'
Check 'disabled: not enabled' { -not $disabled.Enabled -and -not $disabled.Bound -and -not $disabled.Crashed -and $disabled.Verdict -eq 'not_enabled' }

Check 'garbage input: unparsed, never "bound"' { (Get-StrlixAccessibilityState -DumpsysText 'Can''t find service: accessibility').Verdict -eq 'unparsed' }
Check 'empty input: unparsed' { (Get-StrlixAccessibilityState -DumpsysText '').Verdict -eq 'unparsed' }
$bindingOnly = @'
ACCESSIBILITY MANAGER
User state[
 Binding services:{{com.phonecodex.app/com.phonecodex.app.accessibility.PhoneCodexAccessibilityService}}
]
'@
$bindingOnlyState = Get-StrlixAccessibilityState -DumpsysText $bindingOnly
Check 'binding-only dump: parsed and binding' { $bindingOnlyState.Parsed -and $bindingOnlyState.Binding -and $bindingOnlyState.Verdict -eq 'binding' }

# --- sanitized output keeps only Strlix -----------------------------------------------
foreach ($s in @($bound, $crashed, $binding, $disabled)) {
    $text = Format-StrlixAccessibilityState $s
    Check "sanitized ($($s.Verdict)) has no unrelated names" { (Leaks $text).Count -eq 0 }
}
Check 'sanitized crashed text states it plainly' { (Format-StrlixAccessibilityState $crashed) -match 'crashed services: strlix=yes' -and (Format-StrlixAccessibilityState $crashed) -match 'bound services: strlix=no' }

# --- settings value ---------------------------------------------------------------------
Check 'setting: Strlix listed among others' { (Get-StrlixSettingState 'com.example.privatebank/.X:com.phonecodex.app/com.phonecodex.app.accessibility.PhoneCodexAccessibilityService').StrlixListed }
Check 'setting: Strlix absent' { -not (Get-StrlixSettingState 'com.example.privatebank/.X').StrlixListed }
Check 'setting: null' { -not (Get-StrlixSettingState 'null').StrlixListed }

# --- exit-info ----------------------------------------------------------------------------
$exitText = Get-Content (Join-Path $fixtures 'device-crashed\exit_info.txt') -Raw
$records = @(Get-ExitInfoRecords $exitText)
Check 'exit-info: three records' { $records.Count -eq 3 }
Check 'exit-info: one-line record (real Xiaomi layout) stops description before state=' { $records[2].description -eq 'stop com.phonecodex.app due to installPackageLI' -and $records[2].reason -eq '16 (PACKAGE UPDATED)' }
Check 'exit-info: OneKeyClean record parsed' { $records[0].description -eq 'OneKeyClean' -and $records[0].reason -eq '13 (OTHER)' -and $records[0].pid -eq '27078' -and $records[0].timestamp -eq '2026-09-28 13:33:57.412' }
Check 'exit-info: SwipeUpClean record parsed' { $records[1].description -eq 'SwipeUpClean' -and $records[1].importance -eq '400' }
$exitClean = Get-SanitizedExitInfo $exitText
Check 'exit-info: foreign package redacted, Strlix kept' { $exitClean -notmatch 'privatebank' -and $exitClean -match '<other-package>' -and $exitClean -match 'process=com\.phonecodex\.app' }
Check 'exit-info: two-component package redacted' { (Protect-ForeignPackageNames 'connected client=com.example') -eq 'connected client=<other-package>' }

# --- package version --------------------------------------------------------------------
$ver = Get-PackageVersionLines (Get-Content (Join-Path $fixtures 'device-crashed\package_info.txt') -Raw)
Check 'package: only version lines kept' { $ver -match 'versionName=0\.1-fixture' -and $ver -match 'versionCode=1' -and $ver -notmatch 'privatebank|permission' }

# --- device selection fails safely --------------------------------------------------------
$none = "List of devices attached`n"
$one = "List of devices attached`nAAA111`tdevice`n"
$two = "List of devices attached`nAAA111`tdevice`nBBB222`tdevice`n"
$unauth = "List of devices attached`nAAA111`tunauthorized`n"
Check 'devices: zero -> throws' { Throws { Select-DiagDevice -DevicesOutput $none } }
Check 'devices: unauthorized only -> throws' { Throws { Select-DiagDevice -DevicesOutput $unauth } }
Check 'devices: two without serial -> throws' { Throws { Select-DiagDevice -DevicesOutput $two } }
Check 'devices: one -> that serial' { (Select-DiagDevice -DevicesOutput $one) -eq 'AAA111' }
Check 'devices: two with serial -> chosen' { (Select-DiagDevice -DevicesOutput $two -Serial 'BBB222') -eq 'BBB222' }
Check 'devices: unknown serial -> throws' { Throws { Select-DiagDevice -DevicesOutput $two -Serial 'ZZZ' } }
Check 'serial masked to last 4' { (Get-MaskedSerial 'ABCDEF1234') -eq '******1234' }

# --- read-only allowlist -----------------------------------------------------------------
$readOnly = '^(date \+\S+|cat /proc/uptime|getprop [a-z0-9._]+|pidof com\.phonecodex\.app|dumpsys (accessibility|package com\.phonecodex\.app|activity exit-info com\.phonecodex\.app)|settings get secure [a-z_]+)$'
foreach ($entry in (Get-DiagCommands).GetEnumerator()) {
    Check "allowlist '$($entry.Key)' is a read-only query" { $entry.Value -match $readOnly }
}
$forbidden = 'uiautomator|screencap|screenrecord|\binput\b|\bam\s|\bpm\s|cmd\s|settings\s+put|settings\s+delete|run-as|logcat|\binstall\b|\buninstall\b|\bkill\b|force-stop|svc\s|\bmonkey\b|\brm\b|\bpush\b|\bpull\b|shared_prefs'
foreach ($file in @('StrlixPhoneDiag.psm1', 'Capture-StrlixDiag.ps1')) {
    $code = Get-Content (Join-Path $PSScriptRoot $file) -Raw
    Check "$file contains no state-changing or capture command" { $code -notmatch $forbidden }
}

# --- end-to-end fixture run ---------------------------------------------------------------
$capture = Join-Path $PSScriptRoot 'Capture-StrlixDiag.ps1'
$testOut = Join-Path $repoRoot '.tmp-phone-verify-diag-selftest'
if (Test-Path $testOut) { Remove-Item -Recurse -Force $testOut }
$run = & powershell -NoProfile -ExecutionPolicy Bypass -File $capture -FixtureDir (Join-Path $fixtures 'device-crashed') -Label fixture -OutRoot $testOut 2>&1 | ForEach-Object { "$_" }
$runExit = $LASTEXITCODE
Check 'fixture run exits 0' { $runExit -eq 0 }
$runDir = @(Get-ChildItem $testOut -Directory -ErrorAction SilentlyContinue)
Check 'fixture run wrote one capture folder' { $runDir.Count -eq 1 }
if ($runDir.Count -eq 1) {
    $files = @(Get-ChildItem $runDir[0].FullName | ForEach-Object Name | Sort-Object)
    Check 'fixture run files are exactly the sanitized set' { ($files -join ',') -eq 'accessibility_strlix.txt,exit_info.txt,package_version.txt,summary.json' }
    $all = ($files | ForEach-Object { Get-Content (Join-Path $runDir[0].FullName $_) -Raw }) -join "`n"
    Check 'fixture run output has no unrelated names' { (Leaks $all).Count -eq 0 }
    Check 'fixture run output has no raw serial' { $all -notmatch 'FIXTURE0001SERIAL' -and $all -match '\*+RIAL' }
    $sum = Get-Content (Join-Path $runDir[0].FullName 'summary.json') -Raw | ConvertFrom-Json
    Check 'fixture summary: crashed, enabled, no pid' { $sum.a11y_verdict -eq 'crashed' -and $sum.a11y_enabled -and -not $sum.a11y_bound -and -not $sum.strlix_process_alive -and $null -eq $sum.strlix_pid }
    Check 'fixture summary: OneKeyClean exit, version, mode' { $sum.exit_records[0].description -eq 'OneKeyClean' -and $sum.strlix_version_name -eq '0.1-fixture' -and $sum.mode -eq 'fixture' -and $sum.a11y_strlix_in_setting }
}
& git -C $repoRoot check-ignore -q -- '.tmp-phone-verify-diag-selftest/x/summary.json'
Check 'self-test output folder is git-ignored' { $LASTEXITCODE -eq 0 }
& git -C $repoRoot check-ignore -q -- '.tmp-phone-verify-diag/x/summary.json'
Check 'default output folder is git-ignored' { $LASTEXITCODE -eq 0 }
if (Test-Path $testOut) { Remove-Item -Recurse -Force $testOut }

# --- refuses unsafe output locations --------------------------------------------------------
& powershell -NoProfile -ExecutionPolicy Bypass -File $capture -FixtureDir (Join-Path $fixtures 'device-crashed') -OutRoot (Join-Path $repoRoot 'tools\phone\out') 2>&1 | Out-Null
Check 'refuses a non-.tmp-phone output folder (exit 3)' { $LASTEXITCODE -eq 3 -and -not (Test-Path (Join-Path $repoRoot 'tools\phone\out')) }
& powershell -NoProfile -ExecutionPolicy Bypass -File $capture -FixtureDir (Join-Path $fixtures 'device-crashed') -OutRoot (Join-Path $repoRoot '.tmp-phone-notignored') 2>&1 | Out-Null
Check 'refuses a .tmp-phone folder that git does not ignore (exit 3)' { $LASTEXITCODE -eq 3 -and -not (Test-Path (Join-Path $repoRoot '.tmp-phone-notignored')) }

# --- zero / multiple devices fail before any capture ---------------------------------------
$tmpFx = Join-Path $env:TEMP ("strlix-diag-fx-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $tmpFx | Out-Null
Copy-Item (Join-Path $fixtures 'device-crashed\*') $tmpFx
Set-Content (Join-Path $tmpFx 'devices.txt') $two
& powershell -NoProfile -ExecutionPolicy Bypass -File $capture -FixtureDir $tmpFx -OutRoot (Join-Path $repoRoot '.tmp-phone-verify-diag-selftest') 2>&1 | Out-Null
Check 'two devices without -Serial -> exit 2, nothing written' { $LASTEXITCODE -eq 2 -and -not (Test-Path $testOut) }
Set-Content (Join-Path $tmpFx 'devices.txt') $none
& powershell -NoProfile -ExecutionPolicy Bypass -File $capture -FixtureDir $tmpFx -OutRoot (Join-Path $repoRoot '.tmp-phone-verify-diag-selftest') 2>&1 | Out-Null
Check 'zero devices -> exit 2, nothing written' { $LASTEXITCODE -eq 2 -and -not (Test-Path $testOut) }
Remove-Item -Recurse -Force $tmpFx

"passed=$script:passed failed=$($script:failed.Count)"
$script:failed | ForEach-Object { "FAIL: $_" }
if ($script:failed.Count) { exit 1 } else { exit 0 }
