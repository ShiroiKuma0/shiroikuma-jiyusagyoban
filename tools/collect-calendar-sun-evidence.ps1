param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$Package = "com.opentasker.app",
    [string]$Activity = "com.opentasker.app.MainActivity",
    [int]$SampleSeconds = 10,
    [string]$OutputRoot = "build/device-evidence/calendar-sun",
    [switch]$GrantCalendarPermission,
    [switch]$RequireCalendarPermission,
    [switch]$RequireCalendarProviderAccessible,
    [switch]$RequireForegroundService
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($SampleSeconds -lt 0) {
    throw "SampleSeconds must be zero or greater."
}

$script:AdbSerial = $null
$script:OutputDir = $null

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
    if ($process.StartInfo.PSObject.Properties.Name -contains "ArgumentList") {
        foreach ($arg in $allArgs) {
            [void]$process.StartInfo.ArgumentList.Add($arg)
        }
    } else {
        $process.StartInfo.Arguments = Join-ProcessArguments -ArgList $allArgs
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

function Join-ProcessArguments {
    param([string[]]$ArgList)
    ($ArgList | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }) -join " "
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
    return (($result.StdOut, $result.StdErr) -join "`n").Trim()
}

function Parse-PermissionGrant {
    param(
        [string]$PackageDump,
        [string]$Permission
    )
    $escaped = [regex]::Escape($Permission)
    return $PackageDump -match "${escaped}:\s+granted=true"
}

function Parse-ServiceState {
    param([string]$Text)
    [ordered]@{
        automationServiceFound = $Text -match "AutomationService"
        isForeground = $Text -match "isForeground=true"
    }
}

function Query-ContentUri {
    param([string]$Uri)
    $result = Invoke-Adb -AdbArgs @("shell", "content", "query", "--uri", $Uri) -AllowFailure
    [ordered]@{
        exitCode = $result.ExitCode
        stdout = $result.StdOut
        stderr = $result.StdErr
    }
}

if ($Serial) {
    $script:AdbSerial = $Serial
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:OutputDir = Join-Path $OutputRoot $timestamp
New-Item -ItemType Directory -Path $script:OutputDir -Force | Out-Null

$devices = Invoke-Adb -AdbArgs @("devices")
[void](Save-Text -Name "adb-devices.txt" -Text $devices.StdOut)

if ($GrantCalendarPermission) {
    $grant = Invoke-Adb -AdbArgs @("shell", "pm", "grant", $Package, "android.permission.READ_CALENDAR") -AllowFailure
    [void](Save-Text -Name "grant-calendar.txt" -Text (($grant.StdOut, $grant.StdErr) -join "`n"))
}

$launch = Invoke-Adb -AdbArgs @("shell", "am", "start", "-n", "$Package/$Activity") -AllowFailure
[void](Save-Text -Name "launch.txt" -Text (($launch.StdOut, $launch.StdErr) -join "`n"))
if ($SampleSeconds -gt 0) {
    Start-Sleep -Seconds $SampleSeconds
}

$packageDump = Get-ShellText -ShellArgs @("dumpsys", "package", $Package) -AllowFailure
[void](Save-Text -Name "dumpsys-package.txt" -Text $packageDump)

$services = Get-ShellText -ShellArgs @("dumpsys", "activity", "services", $Package) -AllowFailure
[void](Save-Text -Name "services.txt" -Text $services)

$calendarList = Query-ContentUri -Uri "content://com.android.calendar/calendars"
[void](Save-Text -Name "calendar-list.txt" -Text (($calendarList.stdout, $calendarList.stderr) -join "`n"))

$nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$endMs = $nowMs + (24L * 60L * 60L * 1000L)
$instancesUri = "content://com.android.calendar/instances/when/$nowMs/$endMs"
$instances = Query-ContentUri -Uri $instancesUri
[void](Save-Text -Name "calendar-instances.txt" -Text (($instances.stdout, $instances.stderr) -join "`n"))

$serviceState = Parse-ServiceState -Text $services
$permissionGranted = Parse-PermissionGrant -PackageDump $packageDump -Permission "android.permission.READ_CALENDAR"
$calendarProviderAccessible = $calendarList.exitCode -eq 0
$instancesAccessible = $instances.exitCode -eq 0

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    package = $Package
    activity = $Activity
    sampleSeconds = $SampleSeconds
    outputDir = (Resolve-Path $script:OutputDir).Path
    permission = [ordered]@{
        readCalendarGranted = [bool]$permissionGranted
    }
    service = $serviceState
    calendarProvider = [ordered]@{
        calendarsExitCode = $calendarList.exitCode
        instancesExitCode = $instances.exitCode
        calendarsAccessible = [bool]$calendarProviderAccessible
        instancesAccessible = [bool]$instancesAccessible
        instancesUri = $instancesUri
    }
}

$summaryJson = $summary | ConvertTo-Json -Depth 8
[void](Save-Text -Name "summary.json" -Text $summaryJson)
Write-Host $summaryJson

if ($RequireCalendarPermission -and -not $permissionGranted) {
    throw "READ_CALENDAR is not granted. Evidence: $script:OutputDir"
}
if ($RequireCalendarProviderAccessible -and (-not $calendarProviderAccessible -or -not $instancesAccessible)) {
    throw "Calendar provider queries failed. Evidence: $script:OutputDir"
}
if ($RequireForegroundService -and (-not $serviceState.automationServiceFound -or -not $serviceState.isForeground)) {
    throw "AutomationService was not foreground during calendar/sun smoke. Evidence: $script:OutputDir"
}
