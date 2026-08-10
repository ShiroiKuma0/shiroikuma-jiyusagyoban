package com.opentasker.docs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class LocalReleaseGateContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun oneDocumentedCommandOwnsEveryLocalReleaseBoundary() {
        val readme = repoRoot.resolve("README.md").readText()
        val build = repoRoot.resolve("app/build.gradle.kts").readText()
        val script = repoRoot.resolve("tools/verify-local-release.ps1").readText()

        assertTrue(readme.contains(".\\tools\\verify-local-release.ps1"))
        assertTrue(readme.contains("tools/release-truth.json"))
        assertTrue(build.contains("abortOnError = true"))
        assertFalse(build.contains("disable += listOf(\"MissingPermission\""))
        assertFalse(build.contains("baseline = file(\"lint-baseline.xml\")"))
        listOf(
            "lintDebug",
            "compileDebugAndroidTestKotlin",
            "verifyRoomSchema",
            "verifyReleaseTruth",
            "verifyResolvedDependencyPolicy",
            "generateCycloneDxSbom",
            "verifyJvmTestCount",
            "verifyCoverageFloor",
            "verifyLocaleResources",
        ).forEach { task -> assertTrue("Local gate must include $task", build.contains(task)) }

        assertTrue(script.contains("openTaskerDistribution=play"))
        assertTrue(script.contains("openTaskerDistribution=fdroid"))
        assertTrue(script.contains("Reusing configuration cache"))
        assertTrue(script.contains("https://api.osv.dev/v1/querybatch"))
        assertTrue(script.contains("diff --quiet -- app/schemas"))
        assertTrue(script.contains("[switch]\$SeedFailure"))
        assertTrue(script.contains("[switch]\$BootstrapOnly"))
        assertTrue(script.contains("Get-FileHash -LiteralPath \$GradleWrapperJar -Algorithm SHA256"))
        assertTrue(script.contains("safe.directory=\$GitSafeDirectory"))
        assertTrue(script.contains("-C \$Root"))
        assertTrue(script.contains("\$env:GIT_CONFIG_KEY_0 = \"safe.directory\""))
        assertTrue(script.contains("\$env:GIT_CONFIG_VALUE_0 = \$GitSafeDirectory"))
        val bootstrapCheck = script.indexOf("\nAssert-GradleBootstrapIntegrity\n")
        val firstGradleInvocation = script.indexOf("Invoke-Gradle -Arguments")
        assertTrue("Bootstrap must be verified before Gradle executes", bootstrapCheck >= 0)
        assertTrue("Bootstrap must be verified before Gradle executes", firstGradleInvocation > bootstrapCheck)
    }

    @Test
    fun resolvedPoliciesAndVibratePermissionStayFailClosed() {
        val build = repoRoot.resolve("app/build.gradle.kts").readText()
        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()

        assertTrue(build.contains("resolutionResult.allComponents"))
        assertTrue(build.contains("RepositoriesMode.FAIL_ON_PROJECT_REPOS"))
        assertTrue(build.contains("<sha256 value="))
        assertTrue(build.contains("minimumTests.set(1020)"))
        assertTrue(manifest.contains("android.permission.VIBRATE"))
    }
}
