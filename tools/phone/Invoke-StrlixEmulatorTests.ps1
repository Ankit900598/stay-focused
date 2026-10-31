[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5556',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

if ($Serial -notmatch '^emulator-\d+$') {
    throw 'Refusing device serial. Only emulator-<port> serials are accepted.'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$adbCommand = Get-Command adb -ErrorAction Stop
$adb = $adbCommand.Source
$deviceRows = @(& $adb devices | Select-Object -Skip 1)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not query ADB devices.'
}

$selectedDevice = $deviceRows | Where-Object { $_ -match "^$([Regex]::Escape($Serial))\s+device(?:\s|$)" }
if (-not $selectedDevice) {
    throw "Selected emulator is not online: $Serial"
}

$qemu = (& $adb -s $Serial shell getprop ro.kernel.qemu 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $qemu -ne '1') {
    throw "Refusing non-emulator target: $Serial"
}

$bootCompleted = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $bootCompleted -ne '1') {
    throw "Selected emulator is not fully booted: $Serial"
}

if (-not $SkipBuild) {
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot 'gradlew.bat') :app:assemblePhoneTest :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw 'Android APK assembly failed.'
        }
    } finally {
        Pop-Location
    }
}

$appApk = Join-Path $repoRoot 'app\build\outputs\apk\phoneTest\app-phoneTest.apk'
$testApk = Join-Path $repoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($apk in @($appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Required APK is missing: $apk"
    }
}

& $adb -s $Serial install -r $appApk
if ($LASTEXITCODE -ne 0) {
    throw "App APK install failed on $Serial."
}

& $adb -s $Serial install -r $testApk
if ($LASTEXITCODE -ne 0) {
    throw "Android-test APK install failed on $Serial."
}

$instrumentationOutput = & $adb -s $Serial shell am instrument -w -r com.phonecodex.app.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$instrumentationExitCode = $LASTEXITCODE
$instrumentationOutput | ForEach-Object { Write-Output "$_" }
$instrumentationText = $instrumentationOutput -join "`n"
if ($instrumentationExitCode -ne 0 -or $instrumentationText -notmatch 'OK \(\d+ tests\)') {
    throw "Emulator instrumentation failed on $Serial."
}

Write-Output "Emulator-only instrumentation passed on $Serial."
