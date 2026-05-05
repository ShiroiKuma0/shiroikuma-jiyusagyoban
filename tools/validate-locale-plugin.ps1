param(
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$PluginPackage,
    [string]$OpenTaskerPackage = "com.opentasker.app",
    [string]$ConditionActivityClass,
    [string]$OutputRoot = "build/device-evidence/locale-plugin",
    [switch]$RequireSettingSupport,
    [switch]$RequireConditionSupport,
    [switch]$SendRequestQuery,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Actions = [ordered]@{
    EditSetting = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    FireSetting = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    EditCondition = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    QueryCondition = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    RequestQuery = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
}
$Extras = [ordered]@{
    Activity = "com.twofortyfouram.locale.intent.extra.ACTIVITY"
}

if ($Help) {
    @"
Validate an installed Locale/Tasker-compatible plugin against OpenTasker's host contract.

Examples:
  powershell -File tools/validate-locale-plugin.ps1 -PluginPackage com.example.plugin -RequireConditionSupport
  powershell -File tools/validate-locale-plugin.ps1 -PluginPackage com.example.plugin -ConditionActivityClass com.example.plugin.ConditionActivity -SendRequestQuery

Output:
  Writes raw adb evidence and summary.json under $OutputRoot/<timestamp>.

Notes:
  The harness validates manifest-visible contract surfaces from dumpsys package output.
  It does not fabricate a plugin Bundle because adb cannot construct nested Bundle extras safely.
"@
    exit 0
}

if ([string]::IsNullOrWhiteSpace($PluginPackage)) {
    throw "PluginPackage is required. Use -Help for examples."
}
if ($SendRequestQuery -and [string]::IsNullOrWhiteSpace($ConditionActivityClass)) {
    throw "ConditionActivityClass is required when SendRequestQuery is set."
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
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Value
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Set-Content -Path $Path -Value $Value -Encoding UTF8
}

function Test-ActionInDump {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Dump,
        [Parameter(Mandatory = $true)]
        [string]$Action
    )
    return $Dump.Contains($Action)
}

if ($Serial) {
    $script:AdbSerial = $Serial
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputDir = Join-Path $OutputRoot $timestamp
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

$devices = Invoke-Adb -AdbArgs @("devices")
Save-Text -Path (Join-Path $outputDir "adb-devices.txt") -Value $devices.StdOut

$packagePath = Invoke-Adb -AdbArgs @("shell", "pm", "path", $PluginPackage)
Save-Text -Path (Join-Path $outputDir "pm-path.txt") -Value $packagePath.StdOut

$packageDump = Invoke-Adb -AdbArgs @("shell", "dumpsys", "package", $PluginPackage)
Save-Text -Path (Join-Path $outputDir "dumpsys-package.txt") -Value $packageDump.StdOut

$queryCommands = @(
    @{ Name = "edit-setting-activities"; Args = @("shell", "cmd", "package", "query-intent-activities", "-a", $Actions.EditSetting, $PluginPackage) },
    @{ Name = "edit-condition-activities"; Args = @("shell", "cmd", "package", "query-intent-activities", "-a", $Actions.EditCondition, $PluginPackage) },
    @{ Name = "fire-setting-receivers"; Args = @("shell", "cmd", "package", "query-intent-receivers", "-a", $Actions.FireSetting, $PluginPackage) },
    @{ Name = "query-condition-receivers"; Args = @("shell", "cmd", "package", "query-intent-receivers", "-a", $Actions.QueryCondition, $PluginPackage) }
)

$queryResults = @()
foreach ($query in $queryCommands) {
    $result = Invoke-Adb -AdbArgs $query.Args -AllowFailure
    Save-Text -Path (Join-Path $outputDir "$($query.Name).txt") -Value (($result.StdOut, $result.StdErr) -join "`n")
    $queryResults += [pscustomobject]@{
        name = $query.Name
        exitCode = $result.ExitCode
        args = $result.Args
    }
}

$checks = [ordered]@{
    editSetting = Test-ActionInDump -Dump $packageDump.StdOut -Action $Actions.EditSetting
    fireSetting = Test-ActionInDump -Dump $packageDump.StdOut -Action $Actions.FireSetting
    editCondition = Test-ActionInDump -Dump $packageDump.StdOut -Action $Actions.EditCondition
    queryCondition = Test-ActionInDump -Dump $packageDump.StdOut -Action $Actions.QueryCondition
}

$requestQueryResult = $null
if ($SendRequestQuery) {
    $requestQueryResult = Invoke-Adb -AdbArgs @(
        "shell", "am", "broadcast",
        "-a", $Actions.RequestQuery,
        "-p", $OpenTaskerPackage,
        "--es", $Extras.Activity, $ConditionActivityClass.Trim()
    ) -AllowFailure
    Save-Text -Path (Join-Path $outputDir "request-query-broadcast.txt") -Value (($requestQueryResult.StdOut, $requestQueryResult.StdErr) -join "`n")
}

$failures = New-Object System.Collections.Generic.List[string]
if ($RequireSettingSupport) {
    if (-not $checks.editSetting) { [void]$failures.Add("Missing EDIT_SETTING activity action in dumpsys output.") }
    if (-not $checks.fireSetting) { [void]$failures.Add("Missing FIRE_SETTING receiver action in dumpsys output.") }
}
if ($RequireConditionSupport) {
    if (-not $checks.editCondition) { [void]$failures.Add("Missing EDIT_CONDITION activity action in dumpsys output.") }
    if (-not $checks.queryCondition) { [void]$failures.Add("Missing QUERY_CONDITION receiver action in dumpsys output.") }
}
if ($SendRequestQuery -and $requestQueryResult.ExitCode -ne 0) {
    [void]$failures.Add("Synthetic REQUEST_QUERY broadcast failed with exit code $($requestQueryResult.ExitCode).")
}

$summary = [ordered]@{
    generatedAt = (Get-Date).ToString("o")
    pluginPackage = $PluginPackage
    openTaskerPackage = $OpenTaskerPackage
    outputDir = (Resolve-Path $outputDir).Path
    checks = $checks
    queryCommands = $queryResults
    requestQueryBroadcast = if ($requestQueryResult) {
        [ordered]@{
            sent = $true
            conditionActivityClass = $ConditionActivityClass.Trim()
            exitCode = $requestQueryResult.ExitCode
            stdout = $requestQueryResult.StdOut
            stderr = $requestQueryResult.StdErr
        }
    } else {
        [ordered]@{ sent = $false }
    }
    failures = @($failures)
}

$summaryJson = $summary | ConvertTo-Json -Depth 8
Save-Text -Path (Join-Path $outputDir "summary.json") -Value $summaryJson
Write-Host $summaryJson

if ($failures.Count -gt 0) {
    throw "Locale plugin validation failed: $($failures -join ' ')"
}
