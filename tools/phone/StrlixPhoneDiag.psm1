Set-StrictMode -Version 2

$script:StrlixPackage = 'com.phonecodex.app'
$script:StrlixServicePattern = '^com\.phonecodex\.app/(?:com\.phonecodex\.app)?\.accessibility\.PhoneCodexAccessibilityService$'
# "Bound services" in dumpsys lists labels, not component names.
$script:StrlixServiceLabels = @('PhoneCodex Focus Detection', 'PhoneCodex Accessibility')

# The only device commands this tool may run. Every entry is read-only.
# Keys double as fixture file names (<key>.txt) in -FixtureDir mode.
$script:DiagCommands = [ordered]@{
    'device_clock'         = 'date +%Y-%m-%dT%H:%M:%S%z'
    'device_epoch'         = 'date +%s'
    'device_uptime'        = 'cat /proc/uptime'
    'device_model'         = 'getprop ro.product.model'
    'android_release'      = 'getprop ro.build.version.release'
    'android_sdk'          = 'getprop ro.build.version.sdk'
    'miui_version'         = 'getprop ro.miui.ui.version.name'
    'hyperos_version'      = 'getprop ro.mi.os.version.name'
    'package_info'         = 'dumpsys package com.phonecodex.app'
    'pid'                  = 'pidof com.phonecodex.app'
    'exit_info'            = 'dumpsys activity exit-info com.phonecodex.app'
    'a11y_dumpsys'         = 'dumpsys accessibility'
    'a11y_setting_enabled' = 'settings get secure accessibility_enabled'
    'a11y_setting_list'    = 'settings get secure enabled_accessibility_services'
}

function Get-DiagCommands { $script:DiagCommands }

function Select-DiagDevice {
    <# Returns the one usable serial from `adb devices` output, or throws. #>
    param([string]$DevicesOutput, [string]$Serial)
    $ready = @()
    $notReady = @()
    foreach ($line in ($DevicesOutput -split "`r?`n")) {
        if ($line -match '^\s*(\S+)\s+(device|unauthorized|offline|recovery|sideload|bootloader|no permissions.*)\s*$') {
            if ($Matches[2] -eq 'device') { $ready += $Matches[1] } else { $notReady += "$($Matches[1]) ($($Matches[2]))" }
        }
    }
    if ($Serial) {
        if ($ready -contains $Serial) { return $Serial }
        throw "Device '$Serial' is not connected and authorized. Ready devices: $($ready.Count)."
    }
    if ($ready.Count -eq 0) {
        $extra = if ($notReady.Count) { " Not ready: $($notReady -join ', ')." } else { '' }
        throw "No authorized ADB device connected.$extra"
    }
    if ($ready.Count -gt 1) {
        throw "$($ready.Count) ADB devices connected; pass -Serial to choose one."
    }
    $ready[0]
}

function Get-MaskedSerial {
    param([string]$Serial)
    if (-not $Serial) { return $null }
    if ($Serial.Length -le 4) { return $Serial }
    ('*' * ($Serial.Length - 4)) + $Serial.Substring($Serial.Length - 4)
}

function Get-DumpsysSection {
    <# Bodies of every `<Header>:{...}` block (one per user state), brace-balanced. #>
    param([string]$Text, [string]$Header)
    $bodies = New-Object System.Collections.Generic.List[string]
    $token = "${Header}:{"
    $from = 0
    while ($true) {
        $i = $Text.IndexOf($token, $from)
        if ($i -lt 0) { break }
        $start = $i + $token.Length
        $depth = 1
        $j = $start
        while ($j -lt $Text.Length -and $depth -gt 0) {
            $c = $Text[$j]
            if ($c -eq '{') { $depth++ } elseif ($c -eq '}') { $depth-- }
            $j++
        }
        $end = if ($depth -eq 0) { $j - 1 } else { $j }
        $bodies.Add($Text.Substring($start, $end - $start))
        $from = $j
    }
    , $bodies.ToArray()
}

function Test-StrlixComponent {
    param([string]$Component)
    ($Component.Trim() -replace '^ComponentInfo\{|\}$', '') -match $script:StrlixServicePattern
}

function Get-ComponentItems {
    param([string]$Body)
    [regex]::Matches($Body, '\{([^{}]+)\}') | ForEach-Object { $_.Groups[1].Value.Trim() }
}

function Get-StrlixAccessibilityState {
    <#
      Parses `dumpsys accessibility` and keeps only the Strlix service state.
      Enabled/Binding/Crashed list component names; Bound lists labels.
    #>
    param([string]$DumpsysText)
    $text = if ($DumpsysText) { $DumpsysText } else { '' }
    $enabledBodies = Get-DumpsysSection -Text $text -Header 'Enabled services'
    $bindingBodies = Get-DumpsysSection -Text $text -Header 'Binding services'
    $crashedBodies = Get-DumpsysSection -Text $text -Header 'Crashed services'
    $boundBodies = Get-DumpsysSection -Text $text -Header 'Bound services'

    $inComponents = {
        param($bodies)
        foreach ($b in $bodies) { foreach ($item in (Get-ComponentItems $b)) { if (Test-StrlixComponent $item) { return $true } } }
        $false
    }
    $enabled = & $inComponents $enabledBodies
    $binding = & $inComponents $bindingBodies
    $crashed = & $inComponents $crashedBodies

    $boundEntry = $null
    foreach ($b in $boundBodies) {
        foreach ($m in [regex]::Matches($b, 'Service\[label=(?<label>[^,\]]*)(?<rest>(?:[^\[\]]|\[[^\[\]]*\])*)\]')) {
            if ($script:StrlixServiceLabels -contains $m.Groups['label'].Value.Trim()) {
                $boundEntry = ($m.Value -replace '\s+', ' ').Trim()
                break
            }
        }
        if (-not $boundEntry) {
            foreach ($item in (Get-ComponentItems $b)) { if (Test-StrlixComponent $item) { $boundEntry = $item; break } }
        }
        if ($boundEntry) { break }
    }
    $bound = [bool]$boundEntry
    $parsed = ($enabledBodies.Count + $bindingBodies.Count + $boundBodies.Count + $crashedBodies.Count) -gt 0

    $verdict = if (-not $parsed) { 'unparsed' }
        elseif ($crashed) { 'crashed' }
        elseif ($bound -and $enabled) { 'bound' }
        elseif ($bound) { 'bound_not_enabled' }
        elseif ($binding) { 'binding' }
        elseif ($enabled) { 'enabled_not_bound' }
        else { 'not_enabled' }

    [pscustomobject]@{
        Parsed     = $parsed
        Enabled    = [bool]$enabled
        Binding    = [bool]$binding
        Bound      = $bound
        Crashed    = [bool]$crashed
        Verdict    = $verdict
        BoundEntry = $boundEntry
    }
}

function Format-StrlixAccessibilityState {
    param($State)
    $yn = { param($v) if ($v) { 'yes' } else { 'no' } }
    $lines = @(
        "verdict: $($State.Verdict)"
        "enabled services: strlix=$(& $yn $State.Enabled)"
        "binding services: strlix=$(& $yn $State.Binding)"
        "bound services: strlix=$(& $yn $State.Bound)"
        "crashed services: strlix=$(& $yn $State.Crashed)"
    )
    if ($State.BoundEntry) { $lines += "strlix bound entry: $($State.BoundEntry)" }
    $lines -join "`n"
}

function Get-StrlixSettingState {
    <# `settings get secure enabled_accessibility_services` -> Strlix listed or not; other names dropped. #>
    param([string]$SettingValue)
    $items = @()
    if ($SettingValue -and $SettingValue.Trim() -ne 'null') { $items = $SettingValue.Trim() -split ':' }
    [pscustomobject]@{ StrlixListed = [bool](@($items | Where-Object { Test-StrlixComponent $_ }).Count) }
}

function Protect-ForeignPackageNames {
    <# Replaces package-like names that are not Strlix's with <other-package>. #>
    param([string]$Text)
    [regex]::Replace($Text, '\b[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+(?:\:[a-zA-Z0-9_.]+)?', {
            param($m)
            if ($m.Value -eq $script:StrlixPackage -or $m.Value.StartsWith("$($script:StrlixPackage):") -or
                $m.Value.StartsWith("$($script:StrlixPackage).")) { $m.Value } else { '<other-package>' }
        })
}

function Get-SanitizedExitInfo {
    param([string]$ExitInfoText)
    $text = if ($ExitInfoText) { $ExitInfoText } else { '' }
    $lines = foreach ($line in ($text -split "`r?`n")) {
        if (-not $line.Trim()) { continue }
        $clean = Protect-ForeignPackageNames $line
        if ($clean.Length -gt 300) { $clean.Substring(0, 300) + '...' } else { $clean }
    }
    ($lines -join "`n")
}

function Get-ExitInfoRecords {
    <# One object per ApplicationExitInfo record: timestamp, pid, reason, subreason, importance, description. #>
    param([string]$ExitInfoText)
    $text = if ($ExitInfoText) { $ExitInfoText } else { '' }
    $chunks = [regex]::Split($text, 'ApplicationExitInfo #\d+:') | Select-Object -Skip 1
    foreach ($c in $chunks) {
        $get = { param($pattern) $m = [regex]::Match($c, $pattern); if ($m.Success) { $m.Groups[1].Value.Trim() } else { $null } }
        [pscustomobject]@{
            timestamp   = & $get 'timestamp=(\S+ \S+)'
            pid         = & $get '\bpid=(\d+)'
            process     = Protect-ForeignPackageNames (& $get '\bprocess=(\S+)')
            reason      = & $get '\breason=(\d+ \([^)]*\))'
            subreason   = & $get '\bsubreason=(\d+ \([^)]*\))'
            importance  = & $get '\bimportance=(\d+)'
            description = Protect-ForeignPackageNames (& $get '\bdescription=(.*?)(?=\s+state=|\s+trace=|\r?\n|$)')
        }
    }
}

function Get-PackageVersionLines {
    param([string]$PackageText)
    $seen = @{}
    $text = if ($PackageText) { $PackageText } else { '' }
    $out = foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^\s*(versionCode|versionName|firstInstallTime|lastUpdateTime)=') {
            if (-not $seen.ContainsKey($Matches[1])) { $seen[$Matches[1]] = $true; $line.Trim() }
        }
    }
    ($out -join "`n")
}

Export-ModuleMember -Function Get-DiagCommands, Select-DiagDevice, Get-MaskedSerial, Get-DumpsysSection,
Get-StrlixAccessibilityState, Format-StrlixAccessibilityState, Get-StrlixSettingState,
Protect-ForeignPackageNames, Get-SanitizedExitInfo, Get-ExitInfoRecords, Get-PackageVersionLines
