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
    fun resolvedPoliciesAndVibratePermissionStayFailClosed() {
        val build = repoRoot.resolve("app/build.gradle.kts").readText()
        val manifest = repoRoot.resolve("app/src/main/AndroidManifest.xml").readText()

        assertTrue(build.contains("resolutionResult.allComponents"))
        assertTrue(build.contains("RepositoriesMode.FAIL_ON_PROJECT_REPOS"))
        assertTrue(build.contains("<sha256 value="))
        assertTrue(build.contains("JVM_TEST_FLOOR = $EXPECTED_JVM_TEST_FLOOR"))
        assertTrue(build.contains("minimumTests.set(JVM_TEST_FLOOR)"))
        assertTrue(manifest.contains("android.permission.VIBRATE"))
    }
// RETIRED: upstream's release-gate process (one documented command owning every local release
// boundary). This fork releases through its own build-apk / publish-version skills.

    private companion object {
        /** Keep in step with JVM_TEST_FLOOR in app/build.gradle.kts. */
        const val EXPECTED_JVM_TEST_FLOOR = 1324
    }
}
