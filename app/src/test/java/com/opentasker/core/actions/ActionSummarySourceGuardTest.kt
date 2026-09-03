package com.opentasker.core.actions

import com.opentasker.ProductionSources
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

    /** The formatter itself, plus the engine trace builder that owns its own secret-aware path. */
    private val allowlist = setOf(
        "ActionArgumentSensitivity.kt",
        "TaskRunner.kt",
    )

    // Every production source root: pointing this at app/ alone stopped covering the files the
    // core modules own, and a guard that scans less still reports green.
    private fun kotlinFiles(): List<Path> = ProductionSources.allKotlinFiles()

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
            .map { ProductionSources.repoRoot.relativize(it).toString() }

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
            .map { ProductionSources.repoRoot.relativize(it).toString() }

        assertTrue(
            "Duplicate redaction placeholder literal in $offenders — use ActionArgumentSensitivity.REDACTED",
            offenders.isEmpty(),
        )
    }

    @Test
    fun everyBuiltInActionHasAResourceBackedSummaryDeclaration() {
        val metadata = ProductionSources.read("com/opentasker/core/actions/ActionMetadata.kt")
        val actionIds = Regex("""(?m)^\s*id = \"([^\"]+)\"""")
            .findAll(metadata)
            .map { it.groupValues[1] }
            .toList()
        val declaration = ProductionSources.block(
            "com/opentasker/core/actions/ActionMetadata.kt",
            "private fun declaredActionSummaryRes",
            "else -> error",
        )

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
            !ProductionSources.read(relativePath).contains(call)
        }.keys

        assertTrue("Action preview surfaces bypass the shared formatter: $missing", missing.isEmpty())
    }
}
