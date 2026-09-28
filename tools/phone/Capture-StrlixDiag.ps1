<#
.SYNOPSIS
  Read-only, privacy-safe Strlix phone diagnostics snapshot (see tools/phone/README.md).

.EXAMPLE
  .\tools\phone\Capture-StrlixDiag.ps1 -Label before
.EXAMPLE
  .\tools\phone\Capture-StrlixDiag.ps1 -FixtureDir tools\phone\fixtures\device-crashed -Label fixture
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Label = 'capture',
    [string]$OutRoot,
    [string]$FixtureDir
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'StrlixPhoneDiag.psm1') -Force

function Stop-Diag([string]$Message, [int]$Code) {
    [Console]::Error.WriteLine("Capture-StrlixDiag: $Message")
    exit $Code
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $OutRoot) { $OutRoot = Join-Path $repoRoot '.tmp-phone-verify-diag' }
$OutRoot = [IO.Path]::GetFullPath($OutRoot)

# Output must be a git-ignored .tmp-phone-* folder at the repo root.
if (-not $OutRoot.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Stop-Diag "OutRoot must be inside the repo: $OutRoot" 3
}
$relative = $OutRoot.Substring($repoRoot.Length).TrimStart('\', '/')
if (($relative -split '[\\/]')[0] -notlike '.tmp-phone-*') {
    Stop-Diag "OutRoot must be a .tmp-phone-* folder at the repo root: $relative" 3
}
& git -C $repoRoot check-ignore -q -- ("$relative/probe/summary.json" -replace '\\', '/')
if ($LASTEXITCODE -ne 0) {
    Stop-Diag "OutRoot is not git-ignored: $relative" 3
}

$commands = Get-DiagCommands
$fixtureMode = [bool]$FixtureDir
if ($fixtureMode) { $FixtureDir = (Resolve-Path $FixtureDir).Path }

function Read-Fixture([string]$Name) {
    $path = Join-Path $FixtureDir "$Name.txt"
    if (Test-Path $path) { (Get-Content $path -Raw) } else { '' }
}

function Invoke-Native([scriptblock]$Call) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { ((& $Call 2>&1) | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $prev }
}

$devicesOutput = if ($fixtureMode) { Read-Fixture 'devices' } else { Invoke-Native { adb devices } }
try {
    $selected = Select-DiagDevice -DevicesOutput $devicesOutput -Serial $Serial
} catch {
    Stop-Diag $_.Exception.Message 2
}

function Invoke-DiagCommand([string]$Key) {
    if (-not $commands.Contains($Key)) { throw "Not an allowlisted diagnostics command: $Key" }
    if ($fixtureMode) { return (Read-Fixture $Key) }
    $query = $commands[$Key]
    Invoke-Native { adb -s $selected shell $query }
}

$hostStart = Get-Date
$raw = [ordered]@{}
foreach ($key in $commands.Keys) { $raw[$key] = Invoke-DiagCommand $key }
$hostEnd = Get-Date

function Clean([string]$Value) { ($Value + '').Trim() }

$a11y = Get-StrlixAccessibilityState -DumpsysText $raw['a11y_dumpsys']
$setting = Get-StrlixSettingState -SettingValue $raw['a11y_setting_list']
$exitRecords = @(Get-ExitInfoRecords -ExitInfoText $raw['exit_info'])
$pidText = Clean $raw['pid']
$deviceEpoch = 0L
$skew = $null
if ([long]::TryParse((Clean $raw['device_epoch']), [ref]$deviceEpoch)) {
    $skew = $deviceEpoch - ([DateTimeOffset]$hostStart).ToUnixTimeSeconds()
}
$versionLines = Get-PackageVersionLines -PackageText $raw['package_info']
$versionName = if ($versionLines -match 'versionName=(\S+)') { $Matches[1] } else { $null }
$versionCode = if ($versionLines -match 'versionCode=(\d+)') { $Matches[1] } else { $null }

$summary = [ordered]@{
    tool                   = 'tools/phone/Capture-StrlixDiag.ps1'
    mode                   = if ($fixtureMode) { 'fixture' } else { 'device' }
    label                  = $Label
    host_start_local       = $hostStart.ToString('yyyy-MM-ddTHH:mm:ss.fffzzz')
    host_start_utc         = $hostStart.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    host_end_local         = $hostEnd.ToString('yyyy-MM-ddTHH:mm:ss.fffzzz')
    device_serial_masked   = Get-MaskedSerial $selected
    device_clock           = Clean $raw['device_clock']
    device_clock_skew_s    = $skew
    device_uptime_s        = ((Clean $raw['device_uptime']) -split '\s+')[0]
    device_model           = Clean $raw['device_model']
    android_release        = Clean $raw['android_release']
    android_sdk            = Clean $raw['android_sdk']
    miui_version           = Clean $raw['miui_version']
    hyperos_version        = Clean $raw['hyperos_version']
    strlix_version_name    = $versionName
    strlix_version_code    = $versionCode
    strlix_pid             = if ($pidText -match '^\d+(\s+\d+)*$') { $pidText } else { $null }
    strlix_process_alive   = [bool]($pidText -match '^\d+(\s+\d+)*$')
    a11y_setting_enabled   = Clean $raw['a11y_setting_enabled']
    a11y_strlix_in_setting = $setting.StrlixListed
    a11y_verdict           = $a11y.Verdict
    a11y_enabled           = $a11y.Enabled
    a11y_binding           = $a11y.Binding
    a11y_bound             = $a11y.Bound
    a11y_crashed           = $a11y.Crashed
    exit_records           = $exitRecords
}

$safeLabel = ($Label -replace '[^A-Za-z0-9_-]', '_')
$dir = Join-Path $OutRoot ("{0}_{1}" -f $hostStart.ToString('yyyyMMdd-HHmmss'), $safeLabel)
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $dir 'summary.json'), ($summary | ConvertTo-Json -Depth 5), $utf8)
[IO.File]::WriteAllText((Join-Path $dir 'accessibility_strlix.txt'), (Format-StrlixAccessibilityState $a11y) + "`n", $utf8)
[IO.File]::WriteAllText((Join-Path $dir 'exit_info.txt'), (Get-SanitizedExitInfo -ExitInfoText $raw['exit_info']) + "`n", $utf8)
[IO.File]::WriteAllText((Join-Path $dir 'package_version.txt'), $versionLines + "`n", $utf8)

Write-Output "Saved: $dir"
Write-Output ("a11y={0} pid={1} version={2} exits={3}" -f $a11y.Verdict, $summary.strlix_pid, $versionName, $exitRecords.Count)
