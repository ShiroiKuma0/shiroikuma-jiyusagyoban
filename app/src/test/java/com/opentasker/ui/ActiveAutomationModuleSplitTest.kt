package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are split contracts, not layout contracts. They assert that the app shell delegates its
 * heavy workflows and that no single screen file grows past the ceiling — never that a particular
 * composable lives in a particular filename, which turned every extraction into a false failure.
 */
class ActiveAutomationModuleSplitTest {
    private val screensSourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker/ui/screens"),
        Path.of("app/src/main/java/com/opentasker/ui/screens"),
    ).first(Files::exists)

    private val shellFileName = "ActiveAutomationUi.kt"

    private fun screenSources(): Map<String, String> =
        Files.list(screensSourceRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .toList()
                .associate { it.fileName.toString() to it.readText() }
        }

    private fun declarationRegex(functionName: String) =
        Regex("""(?:private|internal|public)?\s*fun $functionName\b""")

    @Test
    fun activeAutomationShellDelegatesRunLogAndImportReviewWorkflows() {
        val sources = screenSources()
        val shellSource = sources.getValue(shellFileName)

        listOf(
            "RunLogScreenContent",
            "RunLogRetentionCard",
            "RunLogFilterCard",
            "RunLogCard",
            "RunLogTraceRow",
            "OpenTaskerBundleReviewDialog",
            "TaskerImportReviewDialog",
            "TaskerImportListSection",
        ).forEach { functionName ->
            val pattern = declarationRegex(functionName)
            assertFalse(
                "$shellFileName should not own $functionName",
                pattern.containsMatchIn(shellSource),
            )
            val owners = sources.filterKeys { it != shellFileName }.filterValues { pattern.containsMatchIn(it) }.keys
            assertEquals(
                "Expected exactly one screens file to declare $functionName, found $owners",
                1,
                owners.size,
            )
        }
    }

    @Test
    fun activeAutomationShellExposesSharedUiHelpersInternally() {
        // Asserted across the screens package: these helpers must exist and stay internal, but
        // pinning them to one filename turned extracting a shared component into a failure.
        val screenSources = screenSources().values.joinToString(separator = System.lineSeparator())

        listOf(
            "internal fun SummaryMetric",
            "internal fun StatusPill",
            "internal fun InlineNotice",
        ).forEach { helperDeclaration ->
            assertTrue("Missing shared helper: $helperDeclaration", screenSources.contains(helperDeclaration))
        }
    }

    @Test
    fun importReviewDialogsKeepScrollableContentBounded() {
        val boundedLists = screenSources().values.sumOf { source ->
            Regex("""heightIn\(max\s*=\s*460\.dp\)""").findAll(source).count()
        }

        assertTrue(
            "Import review dialogs must constrain long warning and action lists on small screens",
            boundedLists >= 2,
        )
    }

    @Test
    fun appShellKeepsPremiumCreateAndOnboardingActionsDiscoverable() {
        val sources = screenSources()
        val allSources = sources.values.joinToString(separator = System.lineSeparator())

        assertTrue(
            "Create actions should stay labeled, not icon-only",
            sources.getValue(shellFileName).contains("ExtendedFloatingActionButton"),
        )
        assertTrue(
            "First-run onboarding should recommend guided templates first",
            allSources.contains("R.string.action_browse_templates"),
        )
        assertTrue(
            "Empty-state actions should not stretch awkwardly on large screens",
            allSources.contains("widthIn(max = 420.dp)"),
        )
    }

    @Test
    fun activeAutomationShellStaysBelowModuleSplitCeiling() {
        val shellLines = Files.readAllLines(screensSourceRoot.resolve(shellFileName)).size

        assertTrue("$shellFileName should stay under 1,500 lines, was $shellLines", shellLines < 1_500)
    }

    @Test
    fun everyScreenSourceStaysBelowTheInterimResponsibilityCeiling() {
        Files.list(screensSourceRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }.forEach { source ->
                assertTrue(
                    "${source.fileName} should stay below the 2,400-line interim split ceiling",
                    Files.readAllLines(source).size < 2_400,
                )
            }
        }
    }
}
