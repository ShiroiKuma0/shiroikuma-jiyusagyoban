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
        assertTrue(build.contains("abortOnError = true"))
        assertFalse(build.contains("disable += listOf(\"MissingPermission\""))
        assertFalse(build.contains("baseline = file(\"lint-baseline.xml\")"))
        listOf(
            "lintDebug",
            "compileDebugAndroidTestKotlin",
            "verifyRoomSchema",
            "verifyResolvedDependencyPolicy",
            "generateCycloneDxSbom",
            "verifyJvmTestCount",
        ).forEach { task -> assertTrue("Local gate must include $task", build.contains(task)) }

        assertTrue(script.contains("openTaskerDistribution=play"))
        assertTrue(script.contains("openTaskerDistribution=fdroid"))
        assertTrue(script.contains("Reusing configuration cache"))
        assertTrue(script.contains("https://api.osv.dev/v1/querybatch"))
        assertTrue(script.contains("git diff --quiet -- app/schemas"))
        assertTrue(script.contains("[switch]\$SeedFailure"))
    }

    @Test
    fun resolvedPoliciesAndVibratePermissionStayFailClosed() {
        val build = repoRoot.resolve("app/build.gradle.kts").readText()
        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()

        assertTrue(build.contains("resolutionResult.allComponents"))
        assertTrue(build.contains("RepositoriesMode.FAIL_ON_PROJECT_REPOS"))
        assertTrue(build.contains("<sha256 value="))
        assertTrue(build.contains("minimumTests.set(522)"))
        assertTrue(manifest.contains("android.permission.VIBRATE"))
    }
}
