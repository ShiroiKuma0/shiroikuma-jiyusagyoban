package com.opentasker.docs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class ReleaseTruthContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun readmeReleaseTableMatchesGradleAndVersionCatalog() {
        val readme = repoRoot.resolve("README.md").readText()
        val gradle = repoRoot.resolve("app/build.gradle.kts").readText()
        val versions = repoRoot.resolve("gradle/libs.versions.toml").readText()
        val wrapper = repoRoot.resolve("gradle/wrapper/gradle-wrapper.properties").readText()

        val versionName = gradleValue(gradle, """val\s+appVersionName\s*=\s*"([^"]+)"""")
        val minSdk = gradleValue(gradle, """minSdk\s*=\s*(\d+)""")
        val compileSdk = gradleValue(gradle, """compileSdk\s*=\s*(\d+)""")
        val targetSdk = gradleValue(gradle, """targetSdk\s*=\s*(\d+)""")
        val buildTools = gradleValue(gradle, """buildToolsVersion\s*=\s*"([^"]+)"""")
        val gradleVersion = gradleValue(wrapper, """gradle-([0-9.]+)-""")

        assertTrue(readme.contains("version-$versionName-blue.svg"))
        assertTableValue(readme, "Kotlin", catalogVersion(versions, "kotlin"))
        assertTableValue(readme, "Gradle", gradleVersion)
        assertTableValue(readme, "AGP", catalogVersion(versions, "agp"))
        assertTableValue(readme, "KSP", catalogVersion(versions, "ksp"))
        assertTableValue(readme, "Build Tools", buildTools)
        assertTableValue(readme, "Min SDK", "$minSdk (Android 8.0)")
        assertTableValue(readme, "Compile SDK", compileSdk)
        assertTableValue(readme, "Target SDK", targetSdk)
        assertTableValue(readme, "Room", catalogVersion(versions, "room"))
        assertTableValue(readme, "Compose BOM", catalogVersion(versions, "composeBom"))
        assertTableValue(readme, "WorkManager", catalogVersion(versions, "work"))
    }

    @Test
    fun readmeDoesNotDescribeShippedFeaturesAsPlannedOrDetectionOnly() {
        val readme = repoRoot.resolve("README.md").readText()
        val staleClaims = listOf(
            "gated Termux script run (not yet wired)",
            "Power-user readiness (detection only)",
            "Shizuku manager status detection and elevated-action hints",
            "Termux/Termux:Tasker package detection and script readiness status",
            "Read-only flow graphs",
            "GitHub Actions CI",
            "Scene multi-select layout edits, alignment guides, and overlay launch",
            "Visual flow editor authoring",
            "Target SDK 36 platform readiness pass",
        )

        staleClaims.forEach { claim ->
            assertFalse("README contains stale release claim: $claim", readme.contains(claim))
        }
    }

    @Test
    fun localFeatureDocsDoNotUseOldReadinessOnlyClaimsWhenPresent() {
        assertOptionalDocTruth(
            path = "docs/SHIZUKU.md",
            required = listOf("ShizukuShellRunner", "allowlisted", "kill switch"),
            forbidden = listOf("readiness baseline", "does not execute privileged work", "No Shizuku API dependency is linked"),
        )
        assertOptionalDocTruth(
            path = "docs/TERMUX_SCRIPTING.md",
            required = listOf("RUN_COMMAND", "TermuxScriptAction", "dispatch"),
            forbidden = listOf("readiness baseline", "not a script runner yet", "No Termux `RUN_COMMAND` service intent is dispatched"),
        )
        assertOptionalDocTruth(
            path = "docs/SCENES.md",
            required = listOf("SceneOverlayService", "multi-select", "alignment guides"),
            forbidden = listOf("Overlay window launch.", "Resize handles and multi-select layout editing."),
        )
        assertOptionalDocTruth(
            path = "docs/VISUAL_FLOW.md",
            required = listOf("zoom/pan", "edge routing", "branch/subflow markers"),
            forbidden = listOf("keeps the graph layout read-only", "No branching editor.", "Add zoom gestures only if"),
        )
    }

    private fun gradleValue(text: String, pattern: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1)
            ?: error("Missing value for pattern $pattern")

    private fun catalogVersion(text: String, key: String): String =
        gradleValue(text, """(?m)^$key\s*=\s*"([^"]+)"""")

    private fun assertTableValue(readme: String, property: String, expected: String) {
        assertTrue("README $property should be $expected", readme.contains("| $property | $expected |"))
    }

    private fun assertOptionalDocTruth(path: String, required: List<String>, forbidden: List<String>) {
        val docPath = repoRoot.resolve(path)
        if (!Files.exists(docPath)) return
        val text = docPath.readText()
        required.forEach { phrase ->
            assertTrue("$path should mention shipped capability: $phrase", text.contains(phrase, ignoreCase = true))
        }
        forbidden.forEach { phrase ->
            assertFalse("$path contains stale claim: $phrase", text.contains(phrase, ignoreCase = true))
        }
    }
}
