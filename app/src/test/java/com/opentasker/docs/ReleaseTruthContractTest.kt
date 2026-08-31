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

    /** Documents that are committed, so a missing one is a defect rather than a local absence. */
    private val REQUIRED_DOCUMENTS = setOf("README.md")

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
        val text = optionalDoc(rule.path)
        if (text == null) {
            // A rule for a document that is not there protects nothing. Committed documents must
            // exist, otherwise deleting one silently deletes its stale-claim protection with it.
            // The docs tree is gitignored, so only committed paths are required.
            assertFalse(
                "${rule.path} is required but missing; its truth rules were silently skipped",
                rule.path in REQUIRED_DOCUMENTS,
            )
            return
        }
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
// RETIRED: upstream's release-truth docs (capability matrices and F-Droid metadata kept in step with
// the gradle catalog). The fork ships the `standard` distribution only and has no F-Droid metadata.
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
// RETIRED: upstream's release-truth docs (capability matrices and F-Droid metadata kept in step with
// the gradle catalog). The fork ships the `standard` distribution only and has no F-Droid metadata.
}
