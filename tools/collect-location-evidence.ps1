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
    [string]$RequireLogcatPattern
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($SampleSeconds -lt 0) {
    throw "SampleSeconds must be zero or greater."
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

function Parse-BatteryDump {
    param([string]$Text)

    $fields = [ordered]@{}
    foreach ($name in @("level", "scale", "status", "health", "plugged", "temperature", "voltage")) {
        $match = [regex]::Match($Text, "(?m)^\s*$([regex]::Escape($name)):\s*(.+)$")
        if ($match.Success) {
            $fields[$name] = $match.Groups[1].Value.Trim()
        }
    }
    return $fields
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
    batteryBefore = Parse-BatteryDump -Text $beforeBattery
    batteryAfter = Parse-BatteryDump -Text $afterBattery
    roomDatabase = [ordered]@{
        snapshot = $databaseSnapshot
        summary = $roomSummary
    }
    requirements = [ordered]@{
        runLogMessagePattern = $RequireRunLogMessagePattern
        logcatPattern = $RequireLogcatPattern
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
if (-not [string]::IsNullOrWhiteSpace($RequireLogcatPattern) -and $logcat -notmatch $RequireLogcatPattern) {
    throw "Logcat did not contain required pattern '$RequireLogcatPattern'. Evidence: $script:OutputDir"
}
if (-not [string]::IsNullOrWhiteSpace($RequireRunLogMessagePattern)) {
    if (-not $roomSummary.available) {
        throw "Run-log requirement could not be checked because Room summary is unavailable. Evidence: $script:OutputDir"
    }
    $matchingRunLogs = @($roomSummary.recentRunLogs | Where-Object {
        $_.timestamp -ge $evidenceStartedAtEpochMs -and $_.message -match $RequireRunLogMessagePattern
    })
    if ($matchingRunLogs.Count -eq 0) {
        throw "Run logs did not contain required pattern '$RequireRunLogMessagePattern' after evidence start. Evidence: $script:OutputDir"
    }
}

Write-Host "Evidence written to $script:OutputDir"
Write-Host "After launch: serviceFound=$($launchState.automationServiceFound) foreground=$($launchState.isForeground) type=$($launchState.typeHex)"
Write-Host "After sample: serviceFound=$($sampleState.automationServiceFound) foreground=$($sampleState.isForeground) type=$($sampleState.typeHex)"
if ($roomSummary.available) {
    Write-Host "Room summary: profiles=$($roomSummary.counts.profiles) tasks=$($roomSummary.counts.tasks) runLogs=$($roomSummary.counts.run_logs)"
} else {
    Write-Host "Room summary unavailable: $($roomSummary.reason)"
}
Write-Host "Summary: $summaryPath"
