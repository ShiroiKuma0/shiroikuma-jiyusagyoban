[CmdletBinding()]
param(
    [switch]$SeedFailure
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$Gradlew = Join-Path $Root "gradlew.bat"
$ReportDirectory = Join-Path $Root "build\reports\opentasker"
$SbomPath = Join-Path $ReportDirectory "sbom.cdx.json"
$OsvReportPath = Join-Path $ReportDirectory "osv-advisories.json"
$SummaryPath = Join-Path $ReportDirectory "local-release-gate.json"

if (-not (Test-Path -LiteralPath $Gradlew -PathType Leaf)) {
    throw "Gradle wrapper not found at $Gradlew"
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$CaptureOutput
    )

    Write-Host "gradlew $($Arguments -join ' ')"
    Push-Location $Root
    try {
        if ($CaptureOutput) {
            $lines = @(& $Gradlew @Arguments 2>&1)
            $exitCode = $LASTEXITCODE
            $lines | ForEach-Object { Write-Host $_ }
            if ($exitCode -ne 0) {
                throw "Gradle exited with code $exitCode"
            }
            return @($lines | ForEach-Object { $_.ToString() })
        }

        & $Gradlew @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$CommonGradleArguments = @(
    "--no-daemon",
    "--console=plain",
    "--project-prop=kotlin.compiler.execution.strategy=in-process"
)

if ($SeedFailure) {
    Invoke-Gradle -Arguments (@(
        ":app:verifyQualityGateSeed",
        "-PopenTaskerQualityGateSeedFailure=true"
    ) + $CommonGradleArguments)
    throw "Seeded failure unexpectedly passed."
}

$QualityArguments = @(
    ":app:localQualityGate",
    "--configuration-cache"
) + $CommonGradleArguments

Invoke-Gradle -Arguments $QualityArguments
$reuseOutput = Invoke-Gradle -Arguments $QualityArguments -CaptureOutput
if (-not ($reuseOutput -match "Reusing configuration cache")) {
    throw "The second local quality-gate invocation did not reuse the Gradle configuration cache."
}

Push-Location $Root
try {
    & git diff --quiet -- app/schemas
    $schemaDiffExit = $LASTEXITCODE
    if ($schemaDiffExit -eq 1) {
        throw "Room schema generation changed tracked files. Review and commit the schema update."
    }
    if ($schemaDiffExit -ne 0) {
        throw "Unable to verify tracked Room schema state (git exit $schemaDiffExit)."
    }
    $untrackedSchemas = @(& git ls-files --others --exclude-standard -- app/schemas)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to verify untracked Room schema state."
    }
    if ($untrackedSchemas.Count -gt 0) {
        throw "Room schema generation created untracked files: $($untrackedSchemas -join ', ')"
    }
} finally {
    Pop-Location
}

Invoke-Gradle -Arguments (@(
    "-PopenTaskerDistribution=play",
    ":app:assembleRelease",
    ":app:verifyPlayManifestPolicy"
) + $CommonGradleArguments)

Invoke-Gradle -Arguments (@(
    "-PopenTaskerDistribution=fdroid",
    ":app:assembleRelease",
    ":app:verifyFdroidReadiness",
    ":app:verifyFdroidMetadata"
) + $CommonGradleArguments)

if (-not (Test-Path -LiteralPath $SbomPath -PathType Leaf)) {
    throw "CycloneDX SBOM was not produced at $SbomPath"
}

$sbom = Get-Content -LiteralPath $SbomPath -Raw | ConvertFrom-Json
$components = @($sbom.components | Where-Object { -not [string]::IsNullOrWhiteSpace($_.purl) })
if ($components.Count -eq 0) {
    throw "CycloneDX SBOM contains no queryable release components."
}

$queries = @($components | ForEach-Object {
    [ordered]@{
        package = [ordered]@{ purl = $_.purl }
    }
})
$requestBody = [ordered]@{ queries = $queries } | ConvertTo-Json -Depth 6 -Compress

# OSV guarantees querybatch response ordering matches the request ordering:
# https://google.github.io/osv.dev/post-v1-querybatch/
try {
    $osvResponse = Invoke-RestMethod `
        -Method Post `
        -Uri "https://api.osv.dev/v1/querybatch" `
        -ContentType "application/json" `
        -Body $requestBody `
        -TimeoutSec 120
} catch {
    throw "OSV advisory query failed; no incomplete security report will be accepted. $($_.Exception.Message)"
}

$results = @($osvResponse.results)
if ($results.Count -ne $components.Count) {
    throw "OSV returned $($results.Count) result(s) for $($components.Count) component(s)."
}

$findings = @()
for ($index = 0; $index -lt $results.Count; $index++) {
    $result = $results[$index]
    if ($null -ne $result.PSObject.Properties["next_page_token"]) {
        throw "OSV paginated the result for $($components[$index].purl); refusing an incomplete advisory report."
    }
    $vulnerabilitiesProperty = $result.PSObject.Properties["vulns"]
    if ($null -eq $vulnerabilitiesProperty) { continue }
    foreach ($vulnerability in @($vulnerabilitiesProperty.Value)) {
        if ($null -eq $vulnerability) { continue }
        $findings += [ordered]@{
            purl = $components[$index].purl
            id = $vulnerability.id
            modified = $vulnerability.modified
        }
    }
}

$AllowedAdvisoryIds = @()
$unapprovedFindings = @($findings | Where-Object { $_.id -notin $AllowedAdvisoryIds })
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
$osvReport = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    endpoint = "https://api.osv.dev/v1/querybatch"
    componentCount = $components.Count
    advisoryCount = $findings.Count
    allowedAdvisoryIds = $AllowedAdvisoryIds
    findings = $findings
}
[System.IO.File]::WriteAllText(
    $OsvReportPath,
    ($osvReport | ConvertTo-Json -Depth 8) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)
if ($unapprovedFindings.Count -gt 0) {
    $ids = @($unapprovedFindings | ForEach-Object { $_.id } | Sort-Object -Unique)
    throw "OSV found unapproved advisories: $($ids -join ', '). See $OsvReportPath"
}

$summary = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    status = "passed"
    minimumJvmTests = 522
    configurationCacheReused = $true
    resolvedComponentCount = $components.Count
    advisoryCount = $findings.Count
    reports = @(
        "build/reports/opentasker/sbom.cdx.json",
        "build/reports/opentasker/osv-advisories.json",
        "app/build/reports/lint-results-debug.html"
    )
}
[System.IO.File]::WriteAllText(
    $SummaryPath,
    ($summary | ConvertTo-Json -Depth 5) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "Local release gate passed. Reports: $ReportDirectory"
