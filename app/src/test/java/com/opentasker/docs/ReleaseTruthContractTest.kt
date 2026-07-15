package com.opentasker.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTruthContractTest {
    private val repoRoot: Path = listOf(Path.of("."), Path.of(".."))
        .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
        .toAbsolutePath()
        .normalize()

    @Test
    fun releaseMatricesMatchGradleCatalogAndFdroidMetadata() {
        val readme = read("README.md")
        val gradle = read("app/build.gradle.kts")
        val versions = read("gradle/libs.versions.toml")
        val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
        val fdroidMetadata = read("fdroid/metadata/com.opentasker.app.yml")

        val versionName = gradleValue(gradle, """val\s+appVersionName\s*=\s*"([^"]+)"""")
        val versionCode = gradleValue(gradle, """val\s+appVersionCode\s*=\s*(\d+)""")
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

        val dependencyDoc = optionalCurrentDoc("docs/DEPENDENCY_MODERNIZATION.md", "## Batch Log")
        if (dependencyDoc != null) {
            assertDocTableValue(dependencyDoc, "Gradle wrapper", gradleVersion)
            assertDocTableValue(dependencyDoc, "Android Gradle Plugin", catalogVersion(versions, "agp"))
            assertDocTableValue(dependencyDoc, "Compile SDK / Build Tools", "$compileSdk / $buildTools")
            assertDocTableValue(dependencyDoc, "Target SDK", targetSdk)
            assertDocTableValue(dependencyDoc, "Kotlin / Compose compiler plugin", catalogVersion(versions, "kotlin"))
            assertDocTableValue(dependencyDoc, "KSP", catalogVersion(versions, "ksp"))
            assertDocTableValue(dependencyDoc, "Compose BOM", catalogVersion(versions, "composeBom"))
            assertDocTableValue(dependencyDoc, "Room", catalogVersion(versions, "room"))
            assertDocTableValue(dependencyDoc, "WorkManager", catalogVersion(versions, "work"))
            assertDocTableValue(dependencyDoc, "OkHttp / MockWebServer", catalogVersion(versions, "okhttp"))
            assertDocTableValue(dependencyDoc, "Shizuku API / Provider", catalogVersion(versions, "shizuku"))
        }

        assertMetadataValue(fdroidMetadata, "versionName", versionName)
        assertMetadataValue(fdroidMetadata, "versionCode", versionCode)
        assertMetadataValue(fdroidMetadata, "CurrentVersion", versionName)
        assertMetadataValue(fdroidMetadata, "CurrentVersionCode", versionCode)
        assertFalse(fdroidMetadata.contains("Termux script readiness", ignoreCase = true))

        optionalDoc("docs/FDROID_READINESS.md")?.let { fdroidDoc ->
            val commit = metadataValue(fdroidMetadata, "commit")
            listOf(versionName, versionCode, commit, buildTools, "Android SDK $compileSdk", gradleVersion).forEach { claim ->
                assertTrue("docs/FDROID_READINESS.md should contain $claim", fdroidDoc.contains(claim))
            }
        }
    }

    @Test
    fun capabilityDocumentsMatchStatesDerivedFromShippedSource() {
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/ui/screens/SceneEditorCanvas.kt",
            "SceneAlignmentGuides.findGuides",
            "onResizeElement",
            "selectedIndices",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/scenes/SceneOverlayService.kt",
            "class SceneOverlayService",
            "fireRunTask",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/ui/screens/AutomationFlowScreen.kt",
            "rememberTransformableState",
            "flow_subflow",
            "flow_branch",
            "onAddContext",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/power/ShizukuPowerBackend.kt",
            "Shizuku.requestPermission",
            "killSwitchEnabled",
            "no privileged user-service transport",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/power/ShizukuShellRunner.kt",
            "Shizuku allowlist",
            "ordinary app processes are never used as a fallback",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/actions/ScriptActions.kt",
            "class TermuxScriptAction",
            "Termux script completed",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/contexts/LocalePluginConditionContextSource.kt",
            "class LocalePluginConditionContextSource",
            "POLL_INTERVAL_MS = 30_000L",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/transfer/TaskerXmlExport.kt",
            "object TaskerXmlExporter",
            "TaskerXmlExportReport",
        )

        documentRules().forEach(::assertDocumentTruthWhenPresent)
    }

    @Test
    fun staleExampleProvesTheDocumentGateFailsClosed() {
        val rule = DocumentTruthRule(
            path = "example.md",
            required = listOf("zoom/pan", "picker-backed add commands"),
            forbidden = listOf("read-only visual flow baseline"),
        )
        val staleExample = "The read-only visual flow baseline renders a graph."

        assertEquals(
            listOf(
                "example.md is missing current claim: zoom/pan",
                "example.md is missing current claim: picker-backed add commands",
                "example.md contains stale claim: read-only visual flow baseline",
            ),
            documentTruthViolations(staleExample, rule),
        )
    }

    private fun documentRules(): List<DocumentTruthRule> = listOf(
        DocumentTruthRule(
            "README.md",
            required = listOf(
                "Scene element editor with drag-to-move, resize handles, multi-select, alignment guides",
                "Flow graphs with zoom/pan canvas previews, edge routing, branch/subflow markers",
                "persisted default-on kill switch",
                "elevated actions remain unsupported until a privileged user-service transport ships",
                "Termux 0.109+ `RUN_COMMAND` integration",
                "Locale/Tasker condition context",
            ),
            forbidden = listOf(
                "gated Termux script run (not yet wired)",
                "Power-user readiness (detection only)",
                "Read-only flow graphs",
                "Scene multi-select layout edits, alignment guides, and overlay launch",
                "Visual flow editor authoring",
            ),
        ),
        DocumentTruthRule(
            "docs/ARCHITECTURE.md",
            required = listOf(
                "zoom/pan",
                "picker-backed Add Context/Add Step commands",
                "SceneOverlayService",
                "multi-select",
                "alignment guides",
                "links the Shizuku API/provider",
                "default-on kill switch",
                "no privileged user-service/Binder transport",
                "Termux scripting boundary dispatches",
                "ContextType.PLUGIN",
                "every 30 seconds",
                "Tasker XML import and bounded best-effort export",
            ),
            forbidden = listOf(
                "Optional Shizuku manager readiness detection",
                "Optional Termux script bridge readiness detection",
                "The read-only visual flow baseline",
                "without launching overlay windows",
                "The Shizuku readiness baseline",
                "does not link the Shizuku API",
                "first-class condition-context picker row",
                "Tasker XML export remains roadmap work",
            ),
        ),
        DocumentTruthRule(
            "docs/SCENES.md",
            required = listOf("SceneOverlayService", "multi-select", "alignment guides"),
            forbidden = listOf("Overlay window launch.", "Resize handles and multi-select layout editing."),
        ),
        DocumentTruthRule(
            "docs/VISUAL_FLOW.md",
            required = listOf("zoom/pan", "edge routing", "branch/subflow markers", "picker-backed add commands"),
            forbidden = listOf("keeps the graph layout read-only", "No branching editor.", "Add zoom gestures only if"),
        ),
        DocumentTruthRule(
            "docs/SHIZUKU.md",
            required = listOf("Shizuku API/provider", "allowlisted", "kill switch", "no privileged user-service"),
            forbidden = listOf("readiness baseline", "does not execute privileged work", "No Shizuku API dependency is linked"),
        ),
        DocumentTruthRule(
            "docs/TERMUX_SCRIPTING.md",
            required = listOf("RUN_COMMAND", "TermuxScriptAction", "dispatch", "SHA-256"),
            forbidden = listOf("readiness baseline", "not a script runner yet", "No Termux `RUN_COMMAND` service intent is dispatched"),
        ),
        DocumentTruthRule(
            "docs/LOCALE_PLUGIN_HOST.md",
            required = listOf(
                "Supported in v0.2.75",
                "LocaleSettingEditActivity",
                "LocalePluginConditionContextSource",
                "every 30 seconds",
            ),
            forbidden = listOf(
                "Supported in v0.2.54",
                "Persisted plugin-backed Condition context rows that automatically re-query on `REQUEST_QUERY`.",
            ),
        ),
    )

    private fun assertDocumentTruthWhenPresent(rule: DocumentTruthRule) {
        val text = optionalDoc(rule.path) ?: return
        val violations = documentTruthViolations(text, rule)
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    private fun requireSourceEvidence(path: String, vararg tokens: String) {
        val text = read(path)
        tokens.forEach { token ->
            assertTrue("$path no longer proves shipped state: $token", text.contains(token, ignoreCase = true))
        }
    }

    private fun optionalCurrentDoc(path: String, historicalHeader: String): String? =
        optionalDoc(path)?.substringBefore(historicalHeader)

    private fun optionalDoc(path: String): String? =
        repoRoot.resolve(path).takeIf(Files::exists)?.readText()

    private fun read(path: String): String = repoRoot.resolve(path).readText()

    private fun gradleValue(text: String, pattern: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1)
            ?: error("Missing value for pattern $pattern")

    private fun catalogVersion(text: String, key: String): String =
        gradleValue(text, """(?m)^$key\s*=\s*"([^"]+)"""")

    private fun metadataValue(text: String, key: String): String =
        text.lineSequence()
            .map(String::trim)
            .map { line -> line.removePrefix("- ") }
            .firstOrNull { line -> line.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"')
            ?: error("Missing F-Droid metadata value for $key")

    private fun assertMetadataValue(text: String, key: String, expected: String) {
        assertEquals("F-Droid $key", expected, metadataValue(text, key))
    }

    private fun assertTableValue(text: String, property: String, expected: String) {
        assertTrue("README $property should be $expected", text.contains("| $property | $expected |"))
    }

    private fun assertDocTableValue(text: String, property: String, expected: String) {
        assertTrue("Dependency doc $property should be $expected", text.contains("| $property | $expected |"))
    }
}

internal data class DocumentTruthRule(
    val path: String,
    val required: List<String>,
    val forbidden: List<String>,
)

internal fun documentTruthViolations(text: String, rule: DocumentTruthRule): List<String> = buildList {
    rule.required.forEach { claim ->
        if (!text.contains(claim, ignoreCase = true)) add("${rule.path} is missing current claim: $claim")
    }
    rule.forbidden.forEach { claim ->
        if (text.contains(claim, ignoreCase = true)) add("${rule.path} contains stale claim: $claim")
    }
}
