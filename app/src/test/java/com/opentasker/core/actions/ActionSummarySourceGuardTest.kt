package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Static guard for the redaction boundary: a display surface must never build its own string from
 * raw action arguments or context config. Every preview goes through
 * [ActionArgumentSensitivity], which is the only place that decides what is masked.
 *
 * Without this guard a new list row, flow node, or import preview can silently reintroduce the
 * plaintext-credential leak that the shared formatter exists to close.
 */
class ActionSummarySourceGuardTest {
    private val mainSourceRoot: Path = listOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).first(Files::exists)

    /** The formatter itself, plus the engine trace builder that owns its own secret-aware path. */
    private val allowlist = setOf(
        "ActionArgumentSensitivity.kt",
        "TaskRunner.kt",
    )

    private fun kotlinFiles(): List<Path> =
        Files.walk(mainSourceRoot).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }.toList()
        }

    @Test
    fun noSurfaceJoinsRawActionArgumentsForDisplay() {
        // `args`/`config` map rendered straight into a string: joinToString over its entries, or a
        // "$key=$value" interpolation of an entry destructuring.
        val rawJoins = Regex(
            """(args|config|expandedArguments)\s*\.\s*(entries\s*\.\s*)?(joinToString|map\s*\{[^}]*\$\{?it\.value)""",
        )
        val offenders = kotlinFiles()
            .filter { it.fileName.toString() !in allowlist }
            .filter { it.readText().contains(rawJoins) }
            .map { mainSourceRoot.relativize(it).toString() }

        assertTrue(
            "Raw action argument rendering in $offenders — use ActionArgumentSensitivity.summarize",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theRedactionPlaceholderHasASingleDefinition() {
        val literal = Regex(""""<redacted>"""")
        val offenders = kotlinFiles()
            .filter { it.fileName.toString() != "ActionArgumentSensitivity.kt" }
            .filter { it.readText().contains(literal) }
            .map { mainSourceRoot.relativize(it).toString() }

        assertTrue(
            "Duplicate redaction placeholder literal in $offenders — use ActionArgumentSensitivity.REDACTED",
            offenders.isEmpty(),
        )
    }

    @Test
    fun everyBuiltInActionHasAResourceBackedSummaryDeclaration() {
        val metadata = mainSourceRoot
            .resolve("com/opentasker/core/actions/ActionMetadata.kt")
            .readText()
        val actionIds = Regex("""(?m)^\s*id = \"([^\"]+)\"""")
            .findAll(metadata)
            .map { it.groupValues[1] }
            .toList()
        val declaration = metadata
            .substringAfter("private fun declaredActionSummaryRes")
            .substringBefore("else -> error")

        assertEquals("Built-in action IDs must be unique", actionIds.size, actionIds.toSet().size)
        val missing = actionIds.filterNot { id -> "\"$id\"" in declaration }
        assertTrue("Action summary declarations are missing for $missing", missing.isEmpty())
        assertTrue("Summary declarations must resolve a string resource", "R.string.action_parameter_summary" in declaration)
        assertTrue("Action metadata must retain the resolved summary resource", "summaryRes = summaryRes" in metadata)
    }

    @Test
    fun everyActionPreviewSurfaceUsesTheSharedSummaryFormatter() {
        val requiredCalls = mapOf(
            "com/opentasker/ui/screens/ActiveAutomationLists.kt" to "ActionSummaryFormatter.format",
            "com/opentasker/ui/screens/PreflightReviewDialog.kt" to "ActionSummaryFormatter.format",
            "com/opentasker/core/flow/AutomationFlowStrings.kt" to "ActionSummaryFormatter.format",
            "com/opentasker/core/flow/AutomationFlowGraph.kt" to "strings.actionSummary",
        )
        val missing = requiredCalls.filter { (relativePath, call) ->
            !mainSourceRoot.resolve(relativePath).readText().contains(call)
        }.keys

        assertTrue("Action preview surfaces bypass the shared formatter: $missing", missing.isEmpty())
    }
}
