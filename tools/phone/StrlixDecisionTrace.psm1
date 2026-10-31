Set-StrictMode -Version 2

$script:AllowedTraceTags = @('PhoneCodexDecision', 'PhoneCodexOverlay', 'PhoneCodexNetAI')

function Get-TraceToken {
    param([string]$Text, [string]$Name)
    $pattern = '(?:^|\s)' + [regex]::Escape($Name) + '=(?<value>[A-Za-z0-9_./:-]{1,200})(?=\s|$)'
    $match = [regex]::Match(($Text + ''), $pattern)
    if ($match.Success) { $match.Groups['value'].Value } else { $null }
}

function Get-SafeTracePackage {
    param([string]$Value)
    if ($Value -and $Value.Length -le 180 -and $Value -match '^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$') {
        return $Value
    }
    $null
}

function Get-SafeTraceEnum {
    param([string]$Value)
    if ($Value -and $Value.Length -le 64 -and $Value -match '^[A-Za-z][A-Za-z0-9_.-]*$') { return $Value }
    $null
}

function Get-SafeTraceInteger {
    param([string]$Value)
    if ($Value -and $Value -match '^(0|[1-9][0-9]{0,9})$') { return [long]$Value }
    $null
}

function Add-CommonDecisionFields {
    param([System.Collections.IDictionary]$Record, [string]$Message)

    $package = Get-SafeTracePackage (Get-TraceToken $Message 'pkg')
    if ($package) { $Record.package = $package }

    foreach ($field in @('surface', 'activity', 'rule', 'durationSource', 'scopeKind', 'code')) {
        $value = Get-SafeTraceEnum (Get-TraceToken $Message $field)
        if ($value) { $Record[$field] = $value }
    }

    $decision = Get-TraceToken $Message 'decision'
    if ($decision -and $decision.ToUpperInvariant() -in @('ALLOW', 'WARN', 'BLOCK', 'LOCK', 'WAIT')) {
        $Record.decision = $decision.ToUpperInvariant()
    }

    $countToken = Get-TraceToken $Message 'count'
    if ($countToken -match '^(?<count>0|[1-9][0-9]{0,9})/(?<limit>0|[1-9][0-9]{0,9})$') {
        $Record.count = [long]$Matches['count']
        $Record.limit = [long]$Matches['limit']
    } else {
        $count = Get-SafeTraceInteger $countToken
        if ($null -ne $count) { $Record.count = $count }
        $limit = Get-SafeTraceInteger (Get-TraceToken $Message 'limit')
        if ($null -ne $limit) { $Record.limit = $limit }
    }
}

function ConvertFrom-DecisionMessage {
    param([string]$Timestamp, [string]$Message)

    if ($Message -match '^ShortQuota(?:\s|$)') {
        $record = [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexDecision'; event = 'short_quota' }
        Add-CommonDecisionFields $record $Message
        $action = Get-SafeTraceEnum (Get-TraceToken $Message 'action')
        if ($action) { $record.action = $action }
        $counted = Get-TraceToken $Message 'counted'
        if ($counted -in @('true', 'false')) { $record.counted = [bool]::Parse($counted) }
        $duration = Get-SafeTraceInteger (Get-TraceToken $Message 'durationSec')
        if ($null -ne $duration) { $record.durationSeconds = $duration }
        return $record
    }

    if ($Message -match '^pkg=') {
        $record = [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexDecision'; event = 'enforcement_decision' }
        Add-CommonDecisionFields $record $Message
        if ($record.Contains('package') -and $record.Contains('decision') -and $record.Contains('code')) { return $record }
        return $null
    }

    if ($Message -match '^(?<event>[A-Z][A-Z0-9_]{1,63})(?:\s|$)') {
        $record = [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexDecision'; event = $Matches['event'] }
        $package = Get-SafeTracePackage (Get-TraceToken $Message 'pkg')
        if ($package) { $record.package = $package }
        $requestCount = Get-SafeTraceInteger (Get-TraceToken $Message 'requests')
        if ($null -ne $requestCount) { $record.requests = $requestCount }
        $playerStopped = Get-TraceToken $Message 'player_stop_observed'
        if ($playerStopped -in @('true', 'false')) { $record.playerStopObserved = [bool]::Parse($playerStopped) }
        $unavailable = Get-TraceToken $Message 'unavailable'
        if ($unavailable -in @('true', 'false')) { $record.unavailable = [bool]::Parse($unavailable) }
        if ($record.Count -gt 3) { return $record }
    }
    $null
}

function ConvertFrom-OverlayMessage {
    param([string]$Timestamp, [string]$Message)

    if ($Message -match '^OVERLAY_LIFECYCLE\s') {
        $record = [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'overlay_lifecycle' }
        $action = Get-SafeTraceEnum (Get-TraceToken $Message 'action')
        if ($action -in @('retain', 'away_pending', 'clear_left_target', 'reevaluate_page_changed')) { $record.action = $action }
        foreach ($field in @('event', 'effective', 'target')) {
            $package = Get-SafeTracePackage (Get-TraceToken $Message $field)
            if ($package) { $record[($field + 'Package')] = $package }
        }
        foreach ($field in @('elapsedMs', 'awayMs')) {
            $value = Get-SafeTraceInteger (Get-TraceToken $Message $field)
            if ($null -ne $value) { $record[$field] = $value }
        }
        if ($record.Contains('action')) { return $record }
        return $null
    }

    if ($Message -match '^Overlay shown kind=(?<kind>BLOCK|WARN) target=(?<target>\S+)') {
        $target = Get-SafeTracePackage $Matches['target']
        if ($target) { return [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'overlay_shown'; kind = $Matches['kind']; targetPackage = $target } }
    }
    if ($Message -match '^Warning overlay shown kind=WARN target=(?<target>\S+)') {
        $target = Get-SafeTracePackage $Matches['target']
        if ($target) { return [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'overlay_shown'; kind = 'WARN'; targetPackage = $target } }
    }
    if ($Message -match '^Rotation rebuild (?<kind>BLOCK|WARN) target=(?<target>\S+)') {
        $target = Get-SafeTracePackage $Matches['target']
        if ($target) { return [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'rotation_rebuild'; kind = $Matches['kind']; targetPackage = $target } }
    }
    if ($Message -match '^Decision hold pkg=(?<package>\S+) decision=(?<decision>ALLOW|WARN|BLOCK|LOCK|WAIT)') {
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { return [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'decision_hold'; package = $package; decision = $Matches['decision'] } }
    }
    if ($Message -match '^(?:Overlay hidden|Warning overlay hidden)') {
        $kind = if ($Message.StartsWith('Warning ')) { 'WARN' } else { 'BLOCK' }
        $record = [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'overlay_hidden'; kind = $kind }
        $package = Get-SafeTracePackage (Get-TraceToken $Message 'package')
        if (-not $package) { $package = Get-SafeTracePackage (Get-TraceToken $Message 'now') }
        if ($package) { $record.package = $package }
        return $record
    }
    if ($Message -match '^Warning overlay kept for target=(?<package>\S+)') {
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { return [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexOverlay'; event = 'overlay_retained'; kind = 'WARN'; targetPackage = $package } }
    }
    $null
}

function ConvertFrom-NetAiMessage {
    param([string]$Timestamp, [string]$Message)

    $record = [ordered]@{ timestamp = $Timestamp; tag = 'PhoneCodexNetAI' }
    if ($Message -match '^Classification request started for (?<package>\S+)') {
        $record.event = 'classification_started'
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { $record.package = $package }
        return $record
    }
    if ($Message -match '^Using cached classification for (?<package>\S+)') {
        $record.event = 'classification_cache_hit'
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { $record.package = $package }
        return $record
    }
    if ($Message -match '^(?:Backend decision=|Backend ok )') {
        $record.event = 'classifier_result'
        $package = Get-SafeTracePackage (Get-TraceToken $Message 'pkg')
        if ($package) { $record.package = $package }
        $decision = Get-TraceToken $Message 'decision'
        if (-not $decision) { $decision = Get-TraceToken $Message 'Backend' }
        if ($Message -match 'Backend decision=(?<decision>ALLOW|WARN|BLOCK|LOCK|WAIT)') { $record.decision = $Matches['decision'] }
        elseif ($decision -and $decision.ToUpperInvariant() -in @('ALLOW', 'WARN', 'BLOCK', 'LOCK', 'WAIT')) { $record.decision = $decision.ToUpperInvariant() }
        $confidenceToken = Get-TraceToken $Message 'confidence'
        if ($confidenceToken -match '^(?:0(?:\.[0-9]{1,6})?|1(?:\.0{1,6})?)$') { $record.confidence = [double]::Parse($confidenceToken, [Globalization.CultureInfo]::InvariantCulture) }
        $latency = Get-SafeTraceInteger (Get-TraceToken $Message 'latencyMs')
        if ($null -ne $latency) { $record.latencyMs = $latency }
        if ($record.Contains('decision') -or $record.Contains('confidence')) { return $record }
        return $null
    }
    if ($Message -match '^(?:Classifier failed for|Vision classify failed for|Backend classify failed for|Classification request failed for) (?<package>\S+)') {
        $record.event = 'classification_failed'
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { $record.package = $package }
        return $record
    }
    if ($Message -match '^Discarded stale classifier (?:result|decision) for (?<package>\S+)') {
        $record.event = 'classification_stale_result_discarded'
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { $record.package = $package }
        return $record
    }
    if ($Message -match '^Classification throttled for (?<package>\S+)') {
        $record.event = 'classification_throttled'
        $package = Get-SafeTracePackage $Matches['package']
        if ($package) { $record.package = $package }
        return $record
    }
    $null
}

function ConvertFrom-StrlixLogcat {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$LogcatText,
        [Parameter(Mandatory = $true)][string]$ExpectedPid,
        [ValidateRange(1, 2000)][int]$MaxRecords = 1200
    )

    if ($ExpectedPid -notmatch '^\d{1,10}$') { throw 'ExpectedPid must be numeric.' }
    $records = New-Object 'System.Collections.Generic.List[object]'
    $linePattern = '^\s*(?<timestamp>\d{10}(?:\.\d{1,9})?)\s+(?<pid>\d{1,10})\s+(?<tid>\d{1,10})\s+(?<priority>[VDIWEF])\s+(?<tag>[A-Za-z0-9_]+):\s?(?<message>.*)$'

    foreach ($line in ($LogcatText -split "`r?`n")) {
        $lineMatch = [regex]::Match($line, $linePattern)
        if (-not $lineMatch.Success -or $lineMatch.Groups['pid'].Value -ne $ExpectedPid) { continue }
        $tag = $lineMatch.Groups['tag'].Value
        if ($script:AllowedTraceTags -notcontains $tag) { continue }
        $timestamp = $lineMatch.Groups['timestamp'].Value
        $message = $lineMatch.Groups['message'].Value
        $record = switch ($tag) {
            'PhoneCodexDecision' { ConvertFrom-DecisionMessage $timestamp $message }
            'PhoneCodexOverlay' { ConvertFrom-OverlayMessage $timestamp $message }
            'PhoneCodexNetAI' { ConvertFrom-NetAiMessage $timestamp $message }
        }
        if ($null -ne $record -and $records.Count -lt $MaxRecords) { $records.Add($record) }
    }
    $records.ToArray()
}

Export-ModuleMember -Function ConvertFrom-StrlixLogcat
