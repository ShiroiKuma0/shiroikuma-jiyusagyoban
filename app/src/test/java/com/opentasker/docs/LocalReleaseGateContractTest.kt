package com.opentasker.docs

import org.junit.Assert.assertEquals
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
            "verifyPackagedTypeCompleteness",
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
        assertTrue(build.contains("private val JVM_TEST_FLOOR = 1200"))
        assertTrue(build.contains("minimumTests.set(JVM_TEST_FLOOR)"))
        assertTrue(manifest.contains("android.permission.VIBRATE"))
    }

    @Test
    fun jvmTestReportSeparatesObservedCountFromConfiguredFloor() {
        val readme = repoRoot.resolve("README.md").readText()
        val build = repoRoot.resolve("app/build.gradle.kts").readText()
        val script = repoRoot.resolve("tools/verify-local-release.ps1").readText()

        assertEquals(1, Regex("JVM_TEST_FLOOR\\s*=\\s*1200").findAll(build).count())
        assertTrue(build.contains("reportFile.set(rootProject.layout.buildDirectory.file(\"reports/opentasker/jvm-test-count.json\"))"))
        assertTrue(script.contains("jvm-test-count.json"))
        assertTrue(script.contains("observedJvmTests"))
        assertTrue(script.contains("jvmTestFloor"))
        assertFalse(script.contains("minimumJvmTests"))
        assertTrue(readme.contains("observed JVM test count"))
        assertTrue(readme.contains("configured JVM test floor"))
        assertFalse(readme.contains("1,049-test JVM floor"))
    }

    /**
     * Removing the in-repo keystore made an unsigned release a silent outcome rather than a build
     * failure, and the staging copy renames whatever it is handed to the published asset name.
     * Both halves of that hole stay closed here.
     */
    @Test
    fun anUnsignedReleaseCannotBePackagedOrStagedOutsideFdroid() {
        val build = repoRoot.resolve("app/build.gradle.kts").readText()

        assertTrue(
            "packageRelease must refuse to run when signing is required but not configured",
            build.contains("check(!signingRequired || signingConfigured)"),
        )
        assertTrue(
            "The signing failure must name the variables an operator has to set",
            build.contains("OPEN_TASKER_RELEASE_KEY_PASSWORD, or build the F-Droid profile with"),
        )
        assertTrue(
            "Staging must verify the artifact is signed for every signed distribution",
            build.contains("requireSignature.set(!releaseBuildIsUnsigned)"),
        )
        assertTrue(
            "Staging must reject an unsigned APK rather than rename it",
            build.contains("check(apkIsSigned(source))"),
        )
        assertTrue(
            "Signature detection must read the APK signing block directly",
            build.contains("APK Sig Block 42"),
        )

        // The Play lane discards its APK, so it is allowed to skip the packaging guard. That
        // exemption has to stay visible at the call site, and it must not exist for staging.
        val script = repoRoot.resolve("tools/verify-local-release.ps1").readText()
        assertTrue(
            "The Play manifest lane must opt out explicitly rather than require a keystore",
            script.contains("-PopenTaskerAllowUnsignedRelease=true"),
        )
        assertEquals(
            "Only the Play lane may opt out of the signing guard",
            1,
            Regex("openTaskerAllowUnsignedRelease=true").findAll(script).count(),
        )
        assertFalse(
            "Staging must not honour the unsigned opt-out",
            build.contains("requireSignature.set(!releaseBuildIsUnsigned || allowUnsignedRelease)"),
        )
    }
}
