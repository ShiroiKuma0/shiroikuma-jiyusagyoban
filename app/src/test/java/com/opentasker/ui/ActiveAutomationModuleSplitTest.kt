package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun declarationRegex(visibility: String, functionName: String) =
        Regex("""$visibility fun $functionName\b""")

    /** The one file that declares [functionName]; fails when zero or several files do. */
    private fun ownerOf(visibility: String, functionName: String): String {
        val owners = screenSources()
            .filterValues { declarationRegex(visibility, functionName).containsMatchIn(it) }
            .keys
        assertEquals(
            "Expected exactly one screens file to declare `$visibility fun $functionName`, found $owners",
            1,
            owners.size,
        )
        return owners.single()
    }

    /** Fails when the token is absent from the package, and when two files both claim it. */
    private fun soleOwnerOf(token: String): String {
        val owners = screenSources().filterValues { it.contains(token) }.keys
        assertEquals("Expected exactly one screens file to contain `$token`, found $owners", 1, owners.size)
        return owners.single()
    }

    @Test
    fun activeAutomationShellDelegatesRunLogAndImportReviewWorkflows() {
        val shellSource = screenSources().getValue(shellFileName)

        listOf(
            "internal" to "RunLogScreenContent",
            "private" to "RunLogRetentionCard",
            "private" to "RunLogFilterCard",
            "private" to "RunLogCard",
            "private" to "RunLogTraceRow",
            "internal" to "OpenTaskerBundleReviewDialog",
            "internal" to "TaskerImportReviewDialog",
            "private" to "TaskerImportListSection",
        ).forEach { (visibility, functionName) ->
            assertFalse(
                "$shellFileName should not own $functionName",
                declarationRegex(visibility, functionName).containsMatchIn(shellSource),
            )
            assertNotEquals(
                "$shellFileName should not own $functionName",
                shellFileName,
                ownerOf(visibility, functionName),
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
        // Anchored on the file that declares the bundle review dialog rather than on a filename,
        // and counted within that file: summing across the package let one dialog's bound stand in
        // for another's.
        val owner = ownerOf("internal", "OpenTaskerBundleReviewDialog")
        val boundedLists = Regex("""heightIn\(max\s*=\s*460\.dp\)""")
            .findAll(screenSources().getValue(owner))
            .count()

        assertTrue(
            "Import review dialogs must constrain long warning and action lists on small screens, " +
                "$owner bounds $boundedLists list(s)",
            boundedLists >= 2,
        )
    }

    @Test
    fun appShellKeepsPremiumCreateAndOnboardingActionsDiscoverable() {
        val sources = screenSources()

        assertTrue(
            "Create actions should stay labeled, not icon-only",
            sources.getValue(soleOwnerOf("fun ActiveAutomationUi(")).contains("ExtendedFloatingActionButton"),
        )
        soleOwnerOf("R.string.action_browse_templates")
        soleOwnerOf("widthIn(max = 420.dp)")
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
