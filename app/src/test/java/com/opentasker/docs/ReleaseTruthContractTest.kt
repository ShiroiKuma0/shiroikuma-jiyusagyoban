package com.opentasker.docs

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
    fun gradleBootstrapMatchesPinnedOfficialChecksums() {
        val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
        val gate = read("tools/verify-local-release.ps1")
        val wrapperJar = Files.readAllBytes(repoRoot.resolve("gradle/wrapper/gradle-wrapper.jar"))
        val wrapperJarHash = MessageDigest.getInstance("SHA-256")
            .digest(wrapperJar)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        val distributionHash = gradleValue(
            wrapper,
            """(?m)^distributionSha256Sum=([0-9a-f]{64})$""",
        )
        val pinnedJarHash = gradleValue(
            gate,
            """\${'$'}ExpectedGradleWrapperJarSha256 = "([0-9a-f]{64})""",
        )

        assertEquals(OFFICIAL_GRADLE_9_7_0_BIN_SHA256, distributionHash)
        assertEquals(OFFICIAL_GRADLE_9_7_0_WRAPPER_JAR_SHA256, pinnedJarHash)
        assertEquals(pinnedJarHash, wrapperJarHash)
        assertTrue(gate.contains("\$ExpectedGradleDistributionSha256 = \"$distributionHash\""))
    }

    @Test
    fun generatedReleaseTruthManifestOwnsArtifactAndCapabilityClaims() {
        val truth = read("tools/release-truth.json")
        val gradle = read("app/build.gradle.kts")
        val versions = read("gradle/libs.versions.toml")
        val wrapper = read("gradle/wrapper/gradle-wrapper.properties")
        val fdroid = read("fdroid/metadata/com.opentasker.app.yml")
        val actionCatalog = read("app/src/main/java/com/opentasker/core/actions/ActionCatalog.kt")
        val contextSpec = read("app/src/main/java/com/opentasker/core/model/ContextSpec.kt")
        val flowStructure = read("app/src/main/java/com/opentasker/core/engine/FlowStructure.kt")
        val taskRunner = read("app/src/main/java/com/opentasker/core/engine/TaskRunner.kt")
        val database = read("app/src/main/java/com/opentasker/core/storage/AppDatabase.kt")
        val bundle = read("app/src/main/java/com/opentasker/core/transfer/OpenTaskerBundle.kt")
        val generator = read("tools/generate-release-truth.ps1")
        val artifactCommit = jsonValue(truth, "requiredArtifactCommit")
        val versionName = jsonValue(truth, "versionName")
        val releaseTag = jsonValue(truth, "releaseTag")
        val releaseTagCommit = jsonValue(truth, "releaseTagCommit")

        assertEquals("1", jsonValue(truth, "schemaVersion"))
        assertEquals(gradleValue(gradle, """val\s+appVersionName\s*=\s*"([^"]+)"""), jsonValue(truth, "versionName"))
        assertEquals(gradleValue(gradle, """val\s+appVersionCode\s*=\s*(\d+)"""), jsonValue(truth, "versionCode"))
        assertEquals(gradleValue(gradle, "minSdk\\s*=\\s*(\\d+)"), jsonValue(truth, "minSdk"))
        assertEquals(gradleValue(gradle, "compileSdk\\s*=\\s*(\\d+)"), jsonValue(truth, "compileSdk"))
        assertEquals(gradleValue(gradle, "targetSdk\\s*=\\s*(\\d+)"), jsonValue(truth, "targetSdk"))
        assertEquals(gradleValue(gradle, "buildToolsVersion\\s*=\\s*\"([^\"]+)\""), jsonValue(truth, "buildTools"))
        assertEquals(catalogVersion(versions, "kotlin"), jsonValue(truth, "kotlin"))
        assertEquals(gradleValue(wrapper, """gradle-([0-9.]+)-"""), jsonValue(truth, "gradle"))
        assertEquals(catalogVersion(versions, "agp"), jsonValue(truth, "agp"))
        assertEquals(catalogVersion(versions, "ksp"), jsonValue(truth, "ksp"))
        assertEquals(catalogVersion(versions, "room"), jsonValue(truth, "room"))
        assertEquals(catalogVersion(versions, "composeBom"), jsonValue(truth, "composeBom"))
        assertEquals(catalogVersion(versions, "work"), jsonValue(truth, "work"))
        assertEquals(
            Regex("(?m)^\\s+const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
                .find(bundle)?.groupValues?.get(1),
            jsonValue(truth, "bundleSchemaVersion"),
        )
        assertEquals(
            Regex("(?m)^const val OPEN_TASKER_DATABASE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
                .find(database)?.groupValues?.get(1),
            jsonValue(truth, "roomSchemaVersion"),
        )
        // The bundle compatibility promise lives in three places - the codec, the generated release
        // truth, and the published format document. A codec change that moves the accepted range
        // has to move all three.
        val minimumBundleSchema = Regex("(?m)^\\s*const val MIN_SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(bundle)?.groupValues?.get(1)
        val currentBundleSchema = jsonValue(truth, "bundleSchemaVersion")
        assertEquals(
            "$minimumBundleSchema..$currentBundleSchema",
            jsonValue(truth, "bundleSupportedSchemaVersions"),
        )
        assertTrue(
            "docs/OPEN_JSON_BUNDLE.md must publish the same supported import range",
            read("docs/OPEN_JSON_BUNDLE.md")
                .contains("Supported for import: `$minimumBundleSchema..$currentBundleSchema`"),
        )
        assertEquals(
            Regex("(?m)^\\s*define\\(\\\"").findAll(actionCatalog).count().toString(),
            jsonValue(truth, "registeredActions"),
        )
        val flowBody = gradleValue(flowStructure, """(?s)\bval\s+ALL\s*=\s*setOf\(([^)]*)\)""")
        val flowCount = Regex("\\b[A-Z][A-Z0-9_]*\\b").findAll(flowBody).map { it.value }.toSet().size
        val engineCount = flowCount + if ("const val SUB_TASK_ACTION_ID" in taskRunner) 1 else 0
        assertEquals(engineCount.toString(), jsonValue(truth, "engineHandledActions"))
        val contextBody = gradleValue(contextSpec, """(?s)enum class ContextType\s*\{(.*?)\}""")
        assertEquals(
            Regex("(?m)^\\s+[A-Z][A-Z_]+\\s*(,|//)").findAll(contextBody).count().toString(),
            jsonValue(truth, "contextFamilies"),
        )
        assertTrue("verifyReleaseTruth" in gradle)
        assertEquals(artifactCommit, metadataValue(fdroid, "commit"))
        assertTrue(Regex("[0-9a-f]{40}").matches(artifactCommit))
        assertEquals("v$versionName", releaseTag)
        assertTrue(Regex("[0-9a-f]{40}").matches(releaseTagCommit))
        assertTrue(Files.exists(repoRoot.resolve("tools/generate-release-truth.ps1")))
        assertTrue("\$flowControlIds.Count" in generator)
        assertTrue("SUB_TASK_ACTION_ID" in generator)
        assertTrue("OPEN_TASKER_DATABASE_SCHEMA_VERSION" in generator)
        assertTrue("ActionCatalog.kt" in generator)
        assertTrue("RequireReleaseTag" in generator)
        assertTrue("releaseTagCommit" in generator)
        assertFalse("engineHandledActions = 7" in generator)
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
            "ShizukuShellRunner.initialize",
            "ShizukuShellRunner.shutdown",
        )
        requireSourceEvidence(
            "app/src/main/java/com/opentasker/core/power/ShizukuShellRunner.kt",
            "ShizukuCommandPolicy",
            "bindUserService",
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
                "six elevated actions run through a separately bound AIDL user service",
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
                "versioned AIDL user service",
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
            required = listOf("Shizuku API/provider", "AIDL user service", "allowlisted", "kill switch", "fail closed"),
            forbidden = listOf("readiness baseline", "does not execute privileged work", "No Shizuku API dependency is linked", "All elevated candidates remain `Unsupported`"),
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

    private fun jsonValue(text: String, key: String): String =
        Regex("\\\"$key\\\"\\s*:\\s*(?:\\\"([^\\\"]+)\\\"|(\\d+))")
            .find(text)
            ?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }
            ?: error("Missing release truth value for $key")

    private fun assertMetadataValue(text: String, key: String, expected: String) {
        assertEquals("F-Droid $key", expected, metadataValue(text, key))
    }

    private fun assertTableValue(text: String, property: String, expected: String) {
        assertTrue("README $property should be $expected", text.contains("| $property | $expected |"))
    }

    private fun assertDocTableValue(text: String, property: String, expected: String) {
        assertTrue("Dependency doc $property should be $expected", text.contains("| $property | $expected |"))
    }

    private companion object {
        const val OFFICIAL_GRADLE_9_7_0_BIN_SHA256 =
            "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"
        const val OFFICIAL_GRADLE_9_7_0_WRAPPER_JAR_SHA256 =
            "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
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
