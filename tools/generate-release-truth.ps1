[CmdletBinding()]
param(
    [string]$RequiredArtifactCommit,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $Root "tools\release-truth.json"
}
$Gradle = Get-Content -LiteralPath (Join-Path $Root "app\build.gradle.kts") -Raw
$ActionCatalog = Get-Content -LiteralPath (Join-Path $Root "app\src\main\java\com\opentasker\core\actions\ActionCatalog.kt") -Raw
$ContextSpec = Get-Content -LiteralPath (Join-Path $Root "app\src\main\java\com\opentasker\core\model\ContextSpec.kt") -Raw
$FlowStructure = Get-Content -LiteralPath (Join-Path $Root "app\src\main\java\com\opentasker\core\engine\FlowStructure.kt") -Raw
$TaskRunner = Get-Content -LiteralPath (Join-Path $Root "app\src\main\java\com\opentasker\core\engine\TaskRunner.kt") -Raw
$AppDatabase = Get-Content -LiteralPath (Join-Path $Root "app\src\main\java\com\opentasker\core\storage\AppDatabase.kt") -Raw
$Bundle = Get-Content -LiteralPath (Join-Path $Root "app\src\main\java\com\opentasker\core\transfer\OpenTaskerBundle.kt") -Raw
$Wrapper = Get-Content -LiteralPath (Join-Path $Root "gradle\wrapper\gradle-wrapper.properties") -Raw
$Versions = Get-Content -LiteralPath (Join-Path $Root "gradle\libs.versions.toml") -Raw

function Match-Value([string]$Text, [string]$Pattern, [string]$Name) {
    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) { throw "Could not derive $Name from the checked-in source." }
    return $match.Groups[1].Value
}

if ([string]::IsNullOrWhiteSpace($RequiredArtifactCommit)) {
    $RequiredArtifactCommit = (& git -C $Root rev-parse HEAD).Trim()
}
if ($RequiredArtifactCommit -cnotmatch "^[0-9a-f]{40}$") {
    throw "Required artifact commit must be a full lowercase Git SHA-1."
}

$flowBody = Match-Value $FlowStructure '(?s)\bval\s+ALL\s*=\s*setOf\(([^)]*)\)' "FlowControl.ALL"
$flowControlIds = [regex]::Matches($flowBody, '\b[A-Z][A-Z0-9_]*\b') |
    ForEach-Object { $_.Value } |
    Sort-Object -Unique
if ($flowControlIds.Count -eq 0) {
    throw "FlowControl.ALL must contain at least one engine-handled action."
}
if ($TaskRunner -notmatch '(?m)^\s*const val SUB_TASK_ACTION_ID\s*=') {
    throw "SUB_TASK_ACTION_ID must remain a declared engine-handled action."
}
$engineHandledActionCount = $flowControlIds.Count + 1

$truth = [ordered]@{
    schemaVersion = 1
    requiredArtifactCommit = $RequiredArtifactCommit
    application = [ordered]@{
        versionName = Match-Value $Gradle 'val\s+appVersionName\s*=\s*"([^"]+)"' "version name"
        versionCode = [int](Match-Value $Gradle 'val\s+appVersionCode\s*=\s*(\d+)' "version code")
    }
    android = [ordered]@{
        minSdk = [int](Match-Value $Gradle 'minSdk\s*=\s*(\d+)' "minimum SDK")
        compileSdk = [int](Match-Value $Gradle 'compileSdk\s*=\s*(\d+)' "compile SDK")
        targetSdk = [int](Match-Value $Gradle 'targetSdk\s*=\s*(\d+)' "target SDK")
        buildTools = Match-Value $Gradle 'buildToolsVersion\s*=\s*"([^"]+)"' "build tools"
    }
    dependencies = [ordered]@{
        kotlin = Match-Value $Versions '(?m)^kotlin\s*=\s*"([^"]+)"' "Kotlin version"
        gradle = Match-Value $Wrapper 'gradle-([0-9.]+)-' "Gradle version"
        agp = Match-Value $Versions '(?m)^agp\s*=\s*"([^"]+)"' "AGP version"
        ksp = Match-Value $Versions '(?m)^ksp\s*=\s*"([^"]+)"' "KSP version"
        room = Match-Value $Versions '(?m)^room\s*=\s*"([^"]+)"' "Room version"
        composeBom = Match-Value $Versions '(?m)^composeBom\s*=\s*"([^"]+)"' "Compose BOM version"
        work = Match-Value $Versions '(?m)^work\s*=\s*"([^"]+)"' "WorkManager version"
    }
    capabilities = [ordered]@{
        registeredActions = ([regex]::Matches($ActionCatalog, '(?m)^\s*define\("')).Count
        engineHandledActions = $engineHandledActionCount
        contextFamilies = ([regex]::Match($ContextSpec, '(?s)enum class ContextType\s*\{(.*?)\}')).Groups[1].Value.Split("`n") |
            Where-Object { $_ -match '^\s+[A-Z][A-Z_]+\s*(,|//)' } | Measure-Object | Select-Object -ExpandProperty Count
        bundleSchemaVersion = [int](Match-Value $Bundle 'const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\s*=\s*(\d+)' "bundle schema version")
        roomSchemaVersion = [int](Match-Value $AppDatabase 'const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\s*=\s*(\d+)' "Room schema version")
    }
}

New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
[IO.File]::WriteAllText(
    $OutputPath,
    ($truth | ConvertTo-Json -Depth 6) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)
Write-Host "Release truth manifest generated at $OutputPath"
