param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = "com.opentasker.app",
    [string]$Activity = "com.opentasker.app.MainActivity",
    [int]$SampleSeconds = 120,
    [string]$OutputRoot = "build/device-evidence/location",
    [switch]$GrantLocationPermissions,
    [switch]$SendHomeDuringSample,
    [switch]$SkipLaunch,
    [string]$RequireRunLogMessagePattern,
    [string]$RequireLogcatPattern,
    [switch]$RequireProviderCadenceEvidence,
    [switch]$RequireUnpluggedSample,
    [switch]$RequireRecentUnpluggedHistory,
    [int]$MinimumUnpluggedHistorySeconds = 600,
    [int]$MaximumUnpluggedHistoryAgeMinutes = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($SampleSeconds -lt 0) {
    throw "SampleSeconds must be zero or greater."
}
if ($MinimumUnpluggedHistorySeconds -lt 1) {
    throw "MinimumUnpluggedHistorySeconds must be at least 1."
}
if ($MaximumUnpluggedHistoryAgeMinutes -lt 1) {
    throw "MaximumUnpluggedHistoryAgeMinutes must be at least 1."
}

$script:AdbSerial = $null

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArgs,
        [switch]$AllowFailure
    )

    $allArgs = @()
    if ($script:AdbSerial) {
        $allArgs += @("-s", $script:AdbSerial)
    }
    $allArgs += $AdbArgs

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = "adb"
    foreach ($arg in $allArgs) {
        [void]$process.StartInfo.ArgumentList.Add($arg)
    }
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.UseShellExecute = $false
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()

    if ($process.ExitCode -ne 0 -and -not $AllowFailure) {
        throw "adb $($allArgs -join ' ') failed with exit code $($process.ExitCode): $stderr"
    }

    [pscustomobject]@{
        Args = $allArgs
        ExitCode = $process.ExitCode
        StdOut = $stdout.TrimEnd()
        StdErr = $stderr.TrimEnd()
    }
}

function Invoke-AdbToFile {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArgs,
        [Parameter(Mandatory = $true)]
        [string]$OutputPath,
        [switch]$AllowFailure
    )

    $allArgs = @()
    if ($script:AdbSerial) {
        $allArgs += @("-s", $script:AdbSerial)
    }
    $allArgs += $AdbArgs

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = "adb"
    foreach ($arg in $allArgs) {
        [void]$process.StartInfo.ArgumentList.Add($arg)
    }
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $process.StartInfo.UseShellExecute = $false

    $stderr = ""
    $file = [System.IO.File]::Open($OutputPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
    try {
        [void]$process.Start()
        $process.StandardOutput.BaseStream.CopyTo($file)
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
    } finally {
        $file.Dispose()
    }

    if ($process.ExitCode -ne 0) {
        Remove-Item -Path $OutputPath -Force -ErrorAction SilentlyContinue
        if (-not $AllowFailure) {
            throw "adb $($allArgs -join ' ') failed with exit code $($process.ExitCode): $stderr"
        }
    }

    [pscustomobject]@{
        Args = $allArgs
        ExitCode = $process.ExitCode
        StdErr = $stderr.TrimEnd()
        OutputPath = $OutputPath
    }
}

function Save-Text {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$Text
    )

    $path = Join-Path $script:OutputDir $Name
    Set-Content -Path $path -Value $Text -Encoding UTF8
    return $path
}

function Get-ShellText {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$ShellArgs,
        [switch]$AllowFailure
    )

    $result = Invoke-Adb -AdbArgs (@("shell") + $ShellArgs) -AllowFailure:$AllowFailure
    if ($result.ExitCode -ne 0) {
        return "ERROR: $($result.StdErr)"
    }
    return $result.StdOut
}

function Get-DumpField {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $match = [regex]::Match($Text, "(?m)^\s*$([regex]::Escape($Name)):\s*(.+)$")
    if ($match.Success) {
        return $match.Groups[1].Value.Trim()
    }
    return $null
}

function ConvertTo-NullableInt {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    $parsed = 0
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function ConvertTo-NullableLong {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    $parsed = 0L
    if ([long]::TryParse(([string]$Value).Trim(), [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function ConvertTo-NullableBool {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    switch (([string]$Value).Trim().ToLowerInvariant()) {
        "true" { return $true }
        "false" { return $false }
        default { return $null }
    }
}

function Convert-BatteryStatusName {
    param([object]$Status)

    switch ([string]$Status) {
        "1" { return "unknown" }
        "2" { return "charging" }
        "3" { return "discharging" }
        "4" { return "not_charging" }
        "5" { return "full" }
        default {
            if ($null -eq $Status) {
                return $null
            }
            return [string]$Status
        }
    }
}

function Get-MapValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Map,
        [Parameter(Mandatory = $true)]
        [string]$Key
    )

    if ($Map -is [System.Collections.IDictionary] -and $Map.Contains($Key)) {
        return $Map[$Key]
    }
    if ($Map.PSObject.Properties.Name -contains $Key) {
        return $Map.$Key
    }
    return $null
}

function Parse-BatteryDump {
    param([string]$Text)

    $fields = [ordered]@{}
    foreach ($name in @("level", "scale", "status", "health", "plugged", "temperature", "voltage")) {
        $value = Get-DumpField -Text $Text -Name $name
        if ($null -ne $value) {
            $fields[$name] = $value
        }
    }

    $acPowered = ConvertTo-NullableBool (Get-DumpField -Text $Text -Name "AC powered")
    $usbPowered = ConvertTo-NullableBool (Get-DumpField -Text $Text -Name "USB powered")
    $wirelessPowered = ConvertTo-NullableBool (Get-DumpField -Text $Text -Name "Wireless powered")
    $dockPowered = ConvertTo-NullableBool (Get-DumpField -Text $Text -Name "Dock powered")
    $pluggedCode = ConvertTo-NullableInt (Get-MapValue -Map $fields -Key "plugged")
    $plugTypeSummary = ConvertTo-NullableInt (Get-DumpField -Text $Text -Name "mSecPlugTypeSummary")
    $levelPercent = ConvertTo-NullableInt (Get-MapValue -Map $fields -Key "level")
    $scale = ConvertTo-NullableInt (Get-MapValue -Map $fields -Key "scale")
    $temperatureTenthsC = ConvertTo-NullableInt (Get-MapValue -Map $fields -Key "temperature")
    $voltageMv = ConvertTo-NullableInt (Get-MapValue -Map $fields -Key "voltage")
    $chargeCounterMicroampHours = ConvertTo-NullableLong (Get-DumpField -Text $Text -Name "Charge counter")
    if ($null -eq $chargeCounterMicroampHours) {
        $chargeCounterMicroampHours = ConvertTo-NullableLong (Get-DumpField -Text $Text -Name "charge counter")
    }
    $currentNowMicroamps = ConvertTo-NullableLong (Get-DumpField -Text $Text -Name "current now")

    $isPlugged = @($acPowered, $usbPowered, $wirelessPowered, $dockPowered) -contains $true
    if (-not $isPlugged -and $null -ne $pluggedCode) {
        $isPlugged = $pluggedCode -ne 0
    }
    if (-not $isPlugged -and $null -ne $plugTypeSummary) {
        $isPlugged = $plugTypeSummary -ne 0
    }

    $statusName = Convert-BatteryStatusName (Get-MapValue -Map $fields -Key "status")
    $fields["statusName"] = $statusName
    $fields["levelPercent"] = $levelPercent
    $fields["scaleNumeric"] = $scale
    $fields["temperatureTenthsC"] = $temperatureTenthsC
    $fields["voltageMv"] = $voltageMv
    $fields["chargeCounterMicroampHours"] = $chargeCounterMicroampHours
    $fields["currentNowMicroamps"] = $currentNowMicroamps
    $fields["acPowered"] = $acPowered
    $fields["usbPowered"] = $usbPowered
    $fields["wirelessPowered"] = $wirelessPowered
    $fields["dockPowered"] = $dockPowered
    $fields["plugTypeSummary"] = $plugTypeSummary
    $fields["isPlugged"] = [bool]$isPlugged
    $fields["plugState"] = if ($isPlugged) { "plugged" } else { "unplugged" }
    return $fields
}

function Compare-BatterySamples {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Before,
        [Parameter(Mandatory = $true)]
        [object]$After,
        [long]$BeforeEpochMs,
        [long]$AfterEpochMs
    )

    $beforeLevel = Get-MapValue -Map $Before -Key "levelPercent"
    $afterLevel = Get-MapValue -Map $After -Key "levelPercent"
    $beforeCharge = Get-MapValue -Map $Before -Key "chargeCounterMicroampHours"
    $afterCharge = Get-MapValue -Map $After -Key "chargeCounterMicroampHours"
    $beforeVoltage = Get-MapValue -Map $Before -Key "voltageMv"
    $afterVoltage = Get-MapValue -Map $After -Key "voltageMv"
    $elapsedMs = $AfterEpochMs - $BeforeEpochMs

    $levelDelta = $null
    if ($null -ne $beforeLevel -and $null -ne $afterLevel) {
        $levelDelta = [int]$afterLevel - [int]$beforeLevel
    }

    $chargeDelta = $null
    if ($null -ne $beforeCharge -and $null -ne $afterCharge) {
        $chargeDelta = [long]$afterCharge - [long]$beforeCharge
    }

    $voltageDelta = $null
    if ($null -ne $beforeVoltage -and $null -ne $afterVoltage) {
        $voltageDelta = [int]$afterVoltage - [int]$beforeVoltage
    }

    [ordered]@{
        elapsedSeconds = [math]::Round($elapsedMs / 1000.0, 3)
        levelDeltaPercent = $levelDelta
        chargeCounterDeltaMicroampHours = $chargeDelta
        voltageDeltaMv = $voltageDelta
        beforePlugState = Get-MapValue -Map $Before -Key "plugState"
        afterPlugState = Get-MapValue -Map $After -Key "plugState"
        beforeStatus = Get-MapValue -Map $Before -Key "statusName"
        afterStatus = Get-MapValue -Map $After -Key "statusName"
    }
}

function Convert-BatteryHistoryTimestamp {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value,
        [Parameter(Mandatory = $true)]
        [DateTimeOffset]$ReferenceTime
    )

    $formats = [string[]]@("MM-dd HH:mm:ss.fff", "MM-dd HH:mm:ss")
    $parsed = [datetime]::MinValue
    if (-not [datetime]::TryParseExact(
        $Value.Trim(),
        $formats,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None,
        [ref]$parsed
    )) {
        return $null
    }

    $referenceLocal = $ReferenceTime.LocalDateTime
    if ($parsed -gt $referenceLocal.AddDays(1)) {
        $parsed = $parsed.AddYears(-1)
    } elseif ($parsed -lt $referenceLocal.AddDays(-365)) {
        $parsed = $parsed.AddYears(1)
    }
    return $parsed
}

function Convert-BatteryHistoryPayloadMap {
    param([string]$Payload)

    $values = @{}
    foreach ($match in [regex]::Matches($Payload, "(?<key>[A-Za-z_]+):(?<value>[^,\s]+)")) {
        $values[$match.Groups["key"].Value] = $match.Groups["value"].Value
    }
    return $values
}

function Test-BatteryHistoryPayloadPlugged {
    param([object]$Values)

    $knownPowerFields = @("ac", "usb", "wireless", "pogo", "dock")
    $foundPowerField = $false
    foreach ($field in $knownPowerFields) {
        if ($Values.ContainsKey($field)) {
            $foundPowerField = $true
            $fieldValue = ConvertTo-NullableBool $Values[$field]
            if ($fieldValue) {
                return $true
            }
        }
    }

    if ($foundPowerField) {
        return $false
    }
    return $null
}

function Convert-BatteryHistoryInterval {
    param([object]$Interval)

    if ($null -eq $Interval) {
        return $null
    }

    [ordered]@{
        startLocal = $Interval.Start.ToString("yyyy-MM-ddTHH:mm:ss.fff")
        endLocal = $Interval.End.ToString("yyyy-MM-ddTHH:mm:ss.fff")
        durationSeconds = $Interval.DurationSeconds
        startLevelPercent = $Interval.StartEvent.LevelPercent
        endLevelPercent = $Interval.EndEvent.LevelPercent
        startStatus = $Interval.StartEvent.StatusName
        endStatus = $Interval.EndEvent.StatusName
        startCurrentAvgMicroamps = $Interval.StartEvent.CurrentAvgMicroamps
        endCurrentAvgMicroamps = $Interval.EndEvent.CurrentAvgMicroamps
        startChargeCounterMicroampHours = $Interval.StartEvent.ChargeCounterMicroampHours
        endChargeCounterMicroampHours = $Interval.EndEvent.ChargeCounterMicroampHours
        startSource = $Interval.StartEvent.Source
        endSource = $Interval.EndEvent.Source
    }
}

function Get-UnpluggedBatteryIntervals {
    param([object[]]$StateEvents)

    $intervals = @()
    $activeStart = $null
    foreach ($stateEvent in @($StateEvents | Sort-Object Timestamp, Source)) {
        if (-not [bool]$stateEvent.IsPlugged) {
            if ($null -eq $activeStart) {
                $activeStart = $stateEvent
            }
            continue
        }

        if ($null -ne $activeStart -and $stateEvent.Timestamp -gt $activeStart.Timestamp) {
            $intervals += [pscustomobject]@{
                Start = $activeStart.Timestamp
                End = $stateEvent.Timestamp
                DurationSeconds = [math]::Round(($stateEvent.Timestamp - $activeStart.Timestamp).TotalSeconds, 3)
                StartEvent = $activeStart
                EndEvent = $stateEvent
            }
        }
        $activeStart = $null
    }

    return $intervals
}

function Parse-RecentUnpluggedBatteryHistory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,
        [int]$MinimumRequiredSeconds,
        [int]$MaximumAgeMinutes,
        [DateTimeOffset]$ReferenceTime = [DateTimeOffset]::Now
    )

    $events = @()
    $timestampPattern = "(?<timestamp>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{3})?)"

    foreach ($match in [regex]::Matches($Text, "(?m)^$timestampPattern\s+(?<event>android\.intent\.action\.ACTION_POWER_(?:DISCONNECTED|CONNECTED))\s*$")) {
        $timestamp = Convert-BatteryHistoryTimestamp -Value $match.Groups["timestamp"].Value -ReferenceTime $ReferenceTime
        if ($null -eq $timestamp) {
            continue
        }
        $eventName = $match.Groups["event"].Value
        $events += [pscustomobject]@{
            Timestamp = $timestamp
            IsPlugged = $eventName -eq "android.intent.action.ACTION_POWER_CONNECTED"
            Source = "power_intent"
            Event = $eventName
            LevelPercent = $null
            StatusName = $null
            CurrentAvgMicroamps = $null
            ChargeCounterMicroampHours = $null
        }
    }

    foreach ($match in [regex]::Matches($Text, "(?m)^$timestampPattern\s+Sending ACTION_BATTERY_CHANGED:\s+(?<payload>.+)$")) {
        $timestamp = Convert-BatteryHistoryTimestamp -Value $match.Groups["timestamp"].Value -ReferenceTime $ReferenceTime
        if ($null -eq $timestamp) {
            continue
        }
        $values = Convert-BatteryHistoryPayloadMap -Payload $match.Groups["payload"].Value
        $isPlugged = Test-BatteryHistoryPayloadPlugged -Values $values
        if ($null -eq $isPlugged) {
            continue
        }
        $events += [pscustomobject]@{
            Timestamp = $timestamp
            IsPlugged = [bool]$isPlugged
            Source = "battery_changed"
            Event = "ACTION_BATTERY_CHANGED"
            LevelPercent = ConvertTo-NullableInt (Get-MapValue -Map $values -Key "level")
            StatusName = Convert-BatteryStatusName (Get-MapValue -Map $values -Key "status")
            CurrentAvgMicroamps = ConvertTo-NullableLong (Get-MapValue -Map $values -Key "current_avg")
            ChargeCounterMicroampHours = ConvertTo-NullableLong (Get-MapValue -Map $values -Key "cc")
        }
    }

    $powerEvents = @($events | Where-Object { $_.Source -eq "power_intent" })
    $intervals = @(Get-UnpluggedBatteryIntervals -StateEvents $powerEvents)
    $intervalSource = "power_intent"
    if ($intervals.Count -eq 0) {
        $intervals = @(Get-UnpluggedBatteryIntervals -StateEvents $events)
        $intervalSource = "power_intent_and_battery_changed"
    }

    $referenceLocal = $ReferenceTime.LocalDateTime
    $recentCutoff = $referenceLocal.AddMinutes(-$MaximumAgeMinutes)
    $recentIntervals = @($intervals | Where-Object { $_.End -ge $recentCutoff })
    $longestComplete = @($intervals | Sort-Object DurationSeconds -Descending | Select-Object -First 1)
    $longestRecent = @($recentIntervals | Sort-Object DurationSeconds -Descending | Select-Object -First 1)
    $latestComplete = @($intervals | Sort-Object End -Descending | Select-Object -First 1)
    $requirementMet = $false
    if ($longestRecent.Count -gt 0) {
        $requirementMet = [double]$longestRecent[0].DurationSeconds -ge [double]$MinimumRequiredSeconds
    }

    [ordered]@{
        available = $intervals.Count -gt 0
        minimumRequiredSeconds = $MinimumRequiredSeconds
        maximumAgeMinutes = $MaximumAgeMinutes
        intervalSource = $intervalSource
        completeIntervalCount = $intervals.Count
        recentCompleteIntervalCount = $recentIntervals.Count
        longestCompleteInterval = if ($longestComplete.Count -gt 0) { Convert-BatteryHistoryInterval -Interval $longestComplete[0] } else { $null }
        longestRecentCompleteInterval = if ($longestRecent.Count -gt 0) { Convert-BatteryHistoryInterval -Interval $longestRecent[0] } else { $null }
        latestCompleteInterval = if ($latestComplete.Count -gt 0) { Convert-BatteryHistoryInterval -Interval $latestComplete[0] } else { $null }
        requirementMet = [bool]$requirementMet
    }
}

function Parse-ServiceState {
    param([string]$Text)

    $typeHex = $null
    $typeMatch = [regex]::Match($Text, "types=(0x[0-9a-fA-F]+)")
    if ($typeMatch.Success) {
        $typeHex = $typeMatch.Groups[1].Value
    }

    $typeValue = 0L
    if ($typeHex) {
        $typeValue = [Convert]::ToInt64($typeHex.Substring(2), 16)
    }

    [ordered]@{
        automationServiceFound = $Text -match "AutomationService"
        isForeground = $Text -match "isForeground=true"
        typeHex = $typeHex
        hasLocationType = (($typeValue -band 0x8L) -ne 0)
        hasSpecialUseType = (($typeValue -band 0x40000000L) -ne 0)
    }
}

function Parse-PermissionGrant {
    param(
        [string]$PackageDump,
        [string]$Permission
    )

    $escaped = [regex]::Escape($Permission)
    return $PackageDump -match "${escaped}:\s+granted=true"
}

function Parse-OpenTaskerProviderCadenceEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    $packagePattern = [regex]::Escape($PackageName)
    $gpsRegistration = $Text -match "(?m)^\s+\d+/$packagePattern/[A-Fa-f0-9]+ Request\[@\+3m0s0ms.*minUpdateDistance=100\.0"
    $networkRegistration = $Text -match "(?m)^\s+\d+/$packagePattern/[A-Fa-f0-9]+ Request\[@\+1m30s0ms.*minUpdateDistance=150\.0"
    $gpsProviderRequest = $Text -match "(?m)^\s*service:\s+ProviderRequest\[@\+3m0s0ms.*WorkSource\{\d+ $packagePattern\}\]"
    $networkProviderRequest = $Text -match "(?m)^\s*service:\s+ProviderRequest\[@\+1m30s0ms.*WorkSource\{\d+ $packagePattern\}\]"
    $gpsHistorical = $Text -match "(?m)^\s+\d+/${packagePattern}:\s+min/max interval = 180s/180s"
    $networkHistorical = $Text -match "(?m)^\s+\d+/${packagePattern}:\s+min/max interval = 90s/90s"

    [ordered]@{
        gps = [ordered]@{
            expectedIntervalSeconds = 180
            expectedDistanceMeters = 100
            registrationFound = [bool]$gpsRegistration
            providerRequestFound = [bool]$gpsProviderRequest
            historicalAggregateFound = [bool]$gpsHistorical
            evidenceFound = [bool]($gpsRegistration -or $gpsProviderRequest -or $gpsHistorical)
        }
        network = [ordered]@{
            expectedIntervalSeconds = 90
            expectedDistanceMeters = 150
            registrationFound = [bool]$networkRegistration
            providerRequestFound = [bool]$networkProviderRequest
            historicalAggregateFound = [bool]$networkHistorical
            evidenceFound = [bool]($networkRegistration -or $networkProviderRequest -or $networkHistorical)
        }
    }
}

function Copy-AppDatabaseSnapshot {
    param([string]$PackageName)

    $files = @(
        "opentasker.db",
        "opentasker.db-wal",
        "opentasker.db-shm"
    )
    $copied = @()
    $errors = @()

    foreach ($file in $files) {
        $outputName = "room-$file"
        $outputPath = Join-Path $script:OutputDir $outputName
        $copy = Invoke-AdbToFile `
            -AdbArgs @("exec-out", "run-as", $PackageName, "cat", "databases/$file") `
            -OutputPath $outputPath `
            -AllowFailure
        if ($copy.ExitCode -eq 0) {
            $copied += $outputName
        } else {
            $errors += [ordered]@{
                file = $file
                stderr = $copy.StdErr
            }
        }
    }

    [ordered]@{
        copiedFiles = $copied
        errors = $errors
    }
}

function Read-RoomDatabaseSummary {
    param([string]$DatabasePath)

    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) {
        return [ordered]@{
            available = $false
            reason = "python_not_found"
        }
    }

    $script = @'
import json
import sqlite3
import sys

db_path = sys.argv[1]
conn = sqlite3.connect(db_path)
conn.row_factory = sqlite3.Row

def count(name):
    try:
        return conn.execute(f"SELECT COUNT(*) AS c FROM {name}").fetchone()["c"]
    except sqlite3.Error:
        return None

def rows(query):
    try:
        return [dict(row) for row in conn.execute(query).fetchall()]
    except sqlite3.Error as exc:
        return [{"error": str(exc)}]

summary = {
    "available": True,
    "counts": {
        "profiles": count("profiles"),
        "tasks": count("tasks"),
        "run_logs": count("run_logs"),
    },
    "profiles": rows("SELECT id, name, enabled, enterTaskId, contextsJson FROM profiles ORDER BY id"),
    "tasks": rows("SELECT id, name, actionsJson FROM tasks ORDER BY id"),
    "recentRunLogs": rows("SELECT id, taskId, taskName, timestamp, success, message FROM run_logs ORDER BY timestamp DESC LIMIT 20"),
}
print(json.dumps(summary, ensure_ascii=False))
'@

    $result = & $python.Source -c $script $DatabasePath 2>&1
    if ($LASTEXITCODE -ne 0) {
        return [ordered]@{
            available = $false
            reason = "sqlite_summary_failed"
            stderr = ($result -join "`n")
        }
    }

    return $result | ConvertFrom-Json
}

function Test-RunLogEvidencePattern {
    param(
        [Parameter(Mandatory = $true)]
        [object]$RunLog,
        [Parameter(Mandatory = $true)]
        [object]$RoomSummary,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    $fields = @(
        [string]$RunLog.taskName,
        [string]$RunLog.message
    )

    if ($RoomSummary.PSObject.Properties.Name -contains "tasks") {
        $task = @($RoomSummary.tasks | Where-Object { [string]$_.id -eq [string]$RunLog.taskId } | Select-Object -First 1)
        if ($task.Count -gt 0) {
            $fields += [string]$task[0].actionsJson
        }
    }

    return @($fields | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -match $Pattern }).Count -gt 0
}

$adbCheck = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adbCheck) {
    throw "adb was not found on PATH."
}

$devices = Invoke-Adb -AdbArgs @("devices")
$deviceLines = @($devices.StdOut -split "\r?\n" | Where-Object { $_ -match "^\S+\s+device$" })

if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($deviceLines.Count -ne 1) {
        throw "Expected exactly one connected adb device, found $($deviceLines.Count). Set -Serial or ANDROID_SERIAL."
    }
    $Serial = ($deviceLines[0] -split "\s+")[0]
}

$script:AdbSerial = $Serial

$packageList = Get-ShellText -ShellArgs @("pm", "list", "packages", $Package)
if ($packageList -notmatch [regex]::Escape("package:$Package")) {
    throw "Package $Package is not installed on device $Serial. Install the debug APK before collecting evidence."
}

if ([System.IO.Path]::IsPathRooted($OutputRoot)) {
    $outputBase = $OutputRoot
} else {
    $outputBase = Join-Path (Get-Location).Path $OutputRoot
}

$sessionId = Get-Date -Format "yyyyMMdd-HHmmss"
$script:OutputDir = Join-Path $outputBase $sessionId
[void](New-Item -ItemType Directory -Force -Path $script:OutputDir)
$evidenceStartedAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$grantResults = @()
if ($GrantLocationPermissions) {
    foreach ($permission in @(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.POST_NOTIFICATIONS"
    )) {
        $grant = Invoke-Adb -AdbArgs @("shell", "pm", "grant", $Package, $permission) -AllowFailure
        $grantResults += [ordered]@{
            permission = $permission
            exitCode = $grant.ExitCode
            stderr = $grant.StdErr
        }
    }
}

$beforeBatteryCapturedAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$beforeBattery = Get-ShellText -ShellArgs @("dumpsys", "battery") -AllowFailure
$beforeStats = Get-ShellText -ShellArgs @("dumpsys", "batterystats", "--charged", $Package) -AllowFailure
$beforePackage = Get-ShellText -ShellArgs @("dumpsys", "package", $Package) -AllowFailure
$beforeAppOps = Get-ShellText -ShellArgs @("appops", "get", $Package) -AllowFailure
$locationEnabled = Get-ShellText -ShellArgs @("cmd", "location", "is-location-enabled") -AllowFailure

[void](Save-Text -Name "battery-before.txt" -Text $beforeBattery)
[void](Save-Text -Name "batterystats-before.txt" -Text $beforeStats)
[void](Save-Text -Name "package-before.txt" -Text $beforePackage)
[void](Save-Text -Name "appops-before.txt" -Text $beforeAppOps)

if (-not $SkipLaunch) {
    [void](Invoke-Adb -AdbArgs @("logcat", "-c") -AllowFailure)
    [void](Invoke-Adb -AdbArgs @("shell", "am", "force-stop", $Package) -AllowFailure)
    [void](Invoke-Adb -AdbArgs @("shell", "am", "start", "-n", "$Package/$Activity"))
    Start-Sleep -Seconds 7
}

$serviceAfterLaunch = Get-ShellText -ShellArgs @("dumpsys", "activity", "services", $Package) -AllowFailure
[void](Save-Text -Name "services-after-launch.txt" -Text $serviceAfterLaunch)

if ($SendHomeDuringSample) {
    [void](Invoke-Adb -AdbArgs @("shell", "input", "keyevent", "KEYCODE_HOME") -AllowFailure)
}

if ($SampleSeconds -gt 0) {
    Start-Sleep -Seconds $SampleSeconds
}

$serviceAfterSample = Get-ShellText -ShellArgs @("dumpsys", "activity", "services", $Package) -AllowFailure
$afterBatteryCapturedAtEpochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$afterBattery = Get-ShellText -ShellArgs @("dumpsys", "battery") -AllowFailure
$afterStats = Get-ShellText -ShellArgs @("dumpsys", "batterystats", "--charged", $Package) -AllowFailure
$afterLocation = Get-ShellText -ShellArgs @("dumpsys", "location") -AllowFailure
$packageAfter = Get-ShellText -ShellArgs @("dumpsys", "package", $Package) -AllowFailure
$appOpsAfter = Get-ShellText -ShellArgs @("appops", "get", $Package) -AllowFailure
$logcat = (Invoke-Adb -AdbArgs @("logcat", "-d", "-t", "1000") -AllowFailure).StdOut
$databaseSnapshot = Copy-AppDatabaseSnapshot -PackageName $Package
$roomDatabasePath = Join-Path $script:OutputDir "room-opentasker.db"
$roomSummary = if (Test-Path $roomDatabasePath) {
    Read-RoomDatabaseSummary -DatabasePath $roomDatabasePath
} else {
    [ordered]@{
        available = $false
        reason = "database_not_copied"
    }
}

[void](Save-Text -Name "services-after-sample.txt" -Text $serviceAfterSample)
[void](Save-Text -Name "battery-after.txt" -Text $afterBattery)
[void](Save-Text -Name "batterystats-after.txt" -Text $afterStats)
[void](Save-Text -Name "location-after.txt" -Text $afterLocation)
[void](Save-Text -Name "package-after.txt" -Text $packageAfter)
[void](Save-Text -Name "appops-after.txt" -Text $appOpsAfter)
[void](Save-Text -Name "logcat-tail.txt" -Text $logcat)
$roomSummaryJson = $roomSummary | ConvertTo-Json -Depth 8
[void](Save-Text -Name "room-summary.json" -Text $roomSummaryJson)

$permissions = [ordered]@{
    fine = Parse-PermissionGrant -PackageDump $packageAfter -Permission "android.permission.ACCESS_FINE_LOCATION"
    coarse = Parse-PermissionGrant -PackageDump $packageAfter -Permission "android.permission.ACCESS_COARSE_LOCATION"
    background = Parse-PermissionGrant -PackageDump $packageAfter -Permission "android.permission.ACCESS_BACKGROUND_LOCATION"
    notifications = Parse-PermissionGrant -PackageDump $packageAfter -Permission "android.permission.POST_NOTIFICATIONS"
}

$batteryBeforeSummary = Parse-BatteryDump -Text $beforeBattery
$batteryAfterSummary = Parse-BatteryDump -Text $afterBattery
$batterySample = Compare-BatterySamples `
    -Before $batteryBeforeSummary `
    -After $batteryAfterSummary `
    -BeforeEpochMs $beforeBatteryCapturedAtEpochMs `
    -AfterEpochMs $afterBatteryCapturedAtEpochMs
$batteryHistory = Parse-RecentUnpluggedBatteryHistory `
    -Text $afterBattery `
    -MinimumRequiredSeconds $MinimumUnpluggedHistorySeconds `
    -MaximumAgeMinutes $MaximumUnpluggedHistoryAgeMinutes
$providerCadence = Parse-OpenTaskerProviderCadenceEvidence -Text $afterLocation -PackageName $Package

$summary = [ordered]@{
    collectedAt = (Get-Date).ToString("o")
    evidenceStartedAtEpochMs = $evidenceStartedAtEpochMs
    serial = $Serial
    package = $Package
    activity = $Activity
    sampleSeconds = $SampleSeconds
    sentHomeDuringSample = [bool]$SendHomeDuringSample
    grantedPermissionsRequested = [bool]$GrantLocationPermissions
    grantResults = $grantResults
    device = [ordered]@{
        manufacturer = Get-ShellText -ShellArgs @("getprop", "ro.product.manufacturer") -AllowFailure
        model = Get-ShellText -ShellArgs @("getprop", "ro.product.model") -AllowFailure
        sdk = Get-ShellText -ShellArgs @("getprop", "ro.build.version.sdk") -AllowFailure
        release = Get-ShellText -ShellArgs @("getprop", "ro.build.version.release") -AllowFailure
        locationEnabled = $locationEnabled
    }
    permissions = $permissions
    serviceAfterLaunch = Parse-ServiceState -Text $serviceAfterLaunch
    serviceAfterSample = Parse-ServiceState -Text $serviceAfterSample
    batteryBeforeCapturedAtEpochMs = $beforeBatteryCapturedAtEpochMs
    batteryAfterCapturedAtEpochMs = $afterBatteryCapturedAtEpochMs
    batteryBefore = $batteryBeforeSummary
    batteryAfter = $batteryAfterSummary
    batterySample = $batterySample
    batteryHistory = $batteryHistory
    providerCadence = $providerCadence
    roomDatabase = [ordered]@{
        snapshot = $databaseSnapshot
        summary = $roomSummary
    }
    requirements = [ordered]@{
        runLogMessagePattern = $RequireRunLogMessagePattern
        logcatPattern = $RequireLogcatPattern
        providerCadenceEvidence = [bool]$RequireProviderCadenceEvidence
        unpluggedSample = [bool]$RequireUnpluggedSample
        recentUnpluggedHistory = [bool]$RequireRecentUnpluggedHistory
        minimumUnpluggedHistorySeconds = $MinimumUnpluggedHistorySeconds
        maximumUnpluggedHistoryAgeMinutes = $MaximumUnpluggedHistoryAgeMinutes
    }
    evidenceFiles = @(
        "battery-before.txt",
        "batterystats-before.txt",
        "package-before.txt",
        "appops-before.txt",
        "services-after-launch.txt",
        "services-after-sample.txt",
        "battery-after.txt",
        "batterystats-after.txt",
        "location-after.txt",
        "package-after.txt",
        "appops-after.txt",
        "logcat-tail.txt",
        "room-summary.json"
    )
}

$summaryPath = Join-Path $script:OutputDir "summary.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8

$launchState = $summary.serviceAfterLaunch
$sampleState = $summary.serviceAfterSample
if (-not $launchState.automationServiceFound -or -not $launchState.isForeground) {
    throw "AutomationService was not foreground after launch. Evidence: $script:OutputDir"
}
if ($permissions.background -and (-not $launchState.hasLocationType -or -not $launchState.hasSpecialUseType)) {
    throw "AutomationService did not expose expected specialUse|location type after launch. Evidence: $script:OutputDir"
}
if ($SendHomeDuringSample -and (-not $sampleState.automationServiceFound -or -not $sampleState.isForeground)) {
    throw "AutomationService was not foreground after home/background sample. Evidence: $script:OutputDir"
}
if ($RequireUnpluggedSample) {
    $beforePlugged = Get-MapValue -Map $summary.batteryBefore -Key "isPlugged"
    $afterPlugged = Get-MapValue -Map $summary.batteryAfter -Key "isPlugged"
    if ($null -eq $beforePlugged -or $null -eq $afterPlugged) {
        throw "Unplugged sample requirement could not be checked from dumpsys battery. Evidence: $script:OutputDir"
    }
    if ($beforePlugged -or $afterPlugged) {
        throw "Unplugged sample required, but device was plugged before or after sample. Evidence: $script:OutputDir"
    }
}
if ($RequireRecentUnpluggedHistory) {
    if (-not $summary.batteryHistory.available) {
        throw "Recent unplugged history requirement could not find any complete unplugged intervals in dumpsys battery. Evidence: $script:OutputDir"
    }
    if (-not $summary.batteryHistory.requirementMet) {
        $observed = $null
        if ($null -ne $summary.batteryHistory.longestRecentCompleteInterval) {
            $observed = $summary.batteryHistory.longestRecentCompleteInterval.durationSeconds
        }
        throw "Recent unplugged history requirement was not met. Required ${MinimumUnpluggedHistorySeconds}s within ${MaximumUnpluggedHistoryAgeMinutes}m; observed longest recent interval ${observed}s. Evidence: $script:OutputDir"
    }
}
if ($RequireProviderCadenceEvidence) {
    $gpsEvidence = Get-MapValue -Map (Get-MapValue -Map $summary.providerCadence -Key "gps") -Key "evidenceFound"
    $networkEvidence = Get-MapValue -Map (Get-MapValue -Map $summary.providerCadence -Key "network") -Key "evidenceFound"
    if (-not $gpsEvidence -or -not $networkEvidence) {
        throw "Location provider cadence evidence did not include expected OpenTasker GPS and network registrations or historical aggregates. Evidence: $script:OutputDir"
    }
}
if (-not [string]::IsNullOrWhiteSpace($RequireLogcatPattern) -and $logcat -notmatch $RequireLogcatPattern) {
    throw "Logcat did not contain required pattern '$RequireLogcatPattern'. Evidence: $script:OutputDir"
}
if (-not [string]::IsNullOrWhiteSpace($RequireRunLogMessagePattern)) {
    if (-not $roomSummary.available) {
        throw "Run-log requirement could not be checked because Room summary is unavailable. Evidence: $script:OutputDir"
    }
    $matchingRunLogs = @($roomSummary.recentRunLogs | Where-Object {
        $_.timestamp -ge $evidenceStartedAtEpochMs -and (Test-RunLogEvidencePattern -RunLog $_ -RoomSummary $roomSummary -Pattern $RequireRunLogMessagePattern)
    })
    if ($matchingRunLogs.Count -eq 0) {
        throw "Run logs did not contain required task, action, or message pattern '$RequireRunLogMessagePattern' after evidence start. Evidence: $script:OutputDir"
    }
}

Write-Host "Evidence written to $script:OutputDir"
Write-Host "After launch: serviceFound=$($launchState.automationServiceFound) foreground=$($launchState.isForeground) type=$($launchState.typeHex)"
Write-Host "After sample: serviceFound=$($sampleState.automationServiceFound) foreground=$($sampleState.isForeground) type=$($sampleState.typeHex)"
Write-Host "Battery: before=$($summary.batteryBefore.levelPercent)% $($summary.batteryBefore.plugState) after=$($summary.batteryAfter.levelPercent)% $($summary.batteryAfter.plugState) delta=$($summary.batterySample.levelDeltaPercent)%"
if ($summary.batteryHistory.available) {
    $longestRecentUnplugged = if ($null -ne $summary.batteryHistory.longestRecentCompleteInterval) {
        "$($summary.batteryHistory.longestRecentCompleteInterval.durationSeconds)s"
    } else {
        "none"
    }
    Write-Host "Battery history: longestRecentUnplugged=$longestRecentUnplugged requirementMet=$($summary.batteryHistory.requirementMet)"
} else {
    Write-Host "Battery history: no complete unplugged intervals found"
}
Write-Host "Provider cadence: gps=$($summary.providerCadence.gps.evidenceFound) network=$($summary.providerCadence.network.evidenceFound)"
if ($roomSummary.available) {
    Write-Host "Room summary: profiles=$($roomSummary.counts.profiles) tasks=$($roomSummary.counts.tasks) runLogs=$($roomSummary.counts.run_logs)"
} else {
    Write-Host "Room summary unavailable: $($roomSummary.reason)"
}
Write-Host "Summary: $summaryPath"
