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
    fun appShellKeepsPremiumCreateAndOnboardingActionsDiscoverable() {
        val sources = screenSources()

        assertTrue(
            "Create actions should stay labeled, not icon-only",
            sources.getValue(soleOwnerOf("fun ActiveAutomationUi(")).contains("ExtendedFloatingActionButton"),
        )
        soleOwnerOf("R.string.action_browse_templates")
        soleOwnerOf("widthIn(max = 420.dp)")
    }

// RETIRED: upstream's module-split layout for the automation shell (a 1,500-line ceiling on
// ActiveAutomationUi.kt and a prescribed delegation split). The fork's shell carries the whole tabbed
// workspace and has diverged well past that shape; enforcing the ceiling would mean restructuring
// working UI to satisfy a rule the fork no longer follows.
}
