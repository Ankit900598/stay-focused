<#
.SYNOPSIS
  Captures a normalized Strlix decision trace; raw logcat is never written to disk.

.EXAMPLE
  .\tools\phone\Capture-StrlixDecisionTrace.ps1 -Label after-test
.EXAMPLE
  .\tools\phone\Capture-StrlixDecisionTrace.ps1 -FixtureDir tools\phone\fixtures\decision-trace -Label fixture
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Label = 'trace',
    [string]$OutRoot,
    [string]$FixtureDir,
    [ValidateRange(1, 2000)][int]$TailLines = 1200
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StrlixPhoneDiag.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'StrlixDecisionTrace.psm1') -Force

function Stop-Trace([string]$Message, [int]$Code) {
    [Console]::Error.WriteLine("Capture-StrlixDecisionTrace: $Message")
    exit $Code
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $OutRoot) { $OutRoot = Join-Path $repoRoot '.tmp-phone-verify-diag' }
$OutRoot = [IO.Path]::GetFullPath($OutRoot)
if (-not $OutRoot.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Stop-Trace 'OutRoot must be inside the repository.' 3
}
$relative = $OutRoot.Substring($repoRoot.Length).TrimStart('\', '/')
if (($relative -split '[\\/]')[0] -notlike '.tmp-phone-*') {
    Stop-Trace 'OutRoot must be a .tmp-phone-* folder at the repository root.' 3
}
& git -C $repoRoot check-ignore -q -- (($relative -replace '\\', '/') + '/probe/trace.jsonl')
if ($LASTEXITCODE -ne 0) { Stop-Trace 'OutRoot is not git-ignored.' 3 }

$fixtureMode = [bool]$FixtureDir
if ($fixtureMode) { $FixtureDir = (Resolve-Path $FixtureDir).Path }
function Read-TraceFixture([string]$Name) {
    $path = Join-Path $FixtureDir "$Name.txt"
    if (Test-Path -LiteralPath $path) { return Get-Content -LiteralPath $path -Raw }
    ''
}

function Invoke-AdbText([string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & adb @Arguments 2>$null
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) { Stop-Trace "ADB read failed (exit $exitCode)." 4 }
        return (@($output | ForEach-Object { "$_" }) -join "`n")
    } catch {
        Stop-Trace 'ADB read failed; raw command output was discarded.' 4
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

$deviceOutput = if ($fixtureMode) { Read-TraceFixture 'devices' } else { Invoke-AdbText @('devices') }
try { $selectedSerial = Select-DiagDevice -DevicesOutput $deviceOutput -Serial $Serial }
catch { Stop-Trace 'No single authorized Android device was selected; check adb devices and -Serial.' 2 }

$processIdText = if ($fixtureMode) {
    Read-TraceFixture 'process_id'
} else {
    Invoke-AdbText @('-s', $selectedSerial, 'shell', 'pidof', 'com.phonecodex.app')
}
$processIds = @([regex]::Matches(($processIdText + '').Trim(), '\d{1,10}') | ForEach-Object { $_.Value } | Select-Object -Unique)
if ($processIds.Count -ne 1) { Stop-Trace 'Strlix must have exactly one live process for PID-scoped capture.' 2 }
$expectedPid = $processIds[0]

$hostStart = Get-Date
$rawLogcat = if ($fixtureMode) {
    Read-TraceFixture 'logcat'
} else {
    Invoke-AdbText @('-s', $selectedSerial, 'logcat', '-d', "--pid=$expectedPid", '-v', 'epoch', '-t', [string]$TailLines,
        '-s', 'PhoneCodexDecision:D', 'PhoneCodexOverlay:D', 'PhoneCodexNetAI:D')
}
if ($rawLogcat.Length -gt 16000000) { Stop-Trace 'Logcat selection exceeded the in-memory size limit; no output was written.' 4 }
$hostEnd = Get-Date
$records = @(ConvertFrom-StrlixLogcat -LogcatText $rawLogcat -ExpectedPid $expectedPid -MaxRecords $TailLines)
$rawLogcat = $null
$processIdText = $null
$deviceOutput = $null

$safeLabel = ($Label -replace '[^A-Za-z0-9_-]', '_')
$directoryName = 'trace_{0}_{1}' -f $hostStart.ToString('yyyyMMdd-HHmmss-fff'), $safeLabel
$captureDirectory = Join-Path $OutRoot $directoryName
if (Test-Path -LiteralPath $captureDirectory) { Stop-Trace 'Capture directory already exists; choose another label and retry.' 3 }
New-Item -ItemType Directory -Force -Path $captureDirectory | Out-Null

$utf8 = New-Object System.Text.UTF8Encoding($false)
$lines = foreach ($record in $records) { $record | ConvertTo-Json -Compress -Depth 4 }
$traceText = if ($records.Count) { ($lines -join "`n") + "`n" } else { '' }
$summary = [ordered]@{
    tool = 'tools/phone/Capture-StrlixDecisionTrace.ps1'
    mode = if ($fixtureMode) { 'fixture' } else { 'device' }
    label = $safeLabel
    capture_started_utc = $hostStart.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    capture_finished_utc = $hostEnd.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    device_serial_masked = Get-MaskedSerial $selectedSerial
    tags = @('PhoneCodexDecision', 'PhoneCodexOverlay', 'PhoneCodexNetAI')
    pid_filter_used = $true
    requested_tail_lines = $TailLines
    normalized_record_count = $records.Count
    raw_logcat_saved = $false
}
[IO.File]::WriteAllText((Join-Path $captureDirectory 'trace.jsonl'), $traceText, $utf8)
[IO.File]::WriteAllText((Join-Path $captureDirectory 'summary.json'), ($summary | ConvertTo-Json -Depth 4), $utf8)
Write-Output "Saved normalized trace: $captureDirectory"
Write-Output ("records={0} raw_logcat_saved=no pid_filter=yes" -f $records.Count)
