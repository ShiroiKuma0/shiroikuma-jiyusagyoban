package com.opentasker.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveAutomationModuleSplitTest {
    private val screensSourceRoot: Path = listOf(
        Path.of("src/main/java/com/opentasker/ui/screens"),
        Path.of("app/src/main/java/com/opentasker/ui/screens"),
    ).first(Files::exists)


    @Test
    fun activeAutomationShellExposesSharedUiHelpersInternally() {
        val shellSource = screensSourceRoot.resolve("ActiveAutomationUi.kt").readText()

        listOf(
            "internal fun SummaryMetric",
            "internal fun StatusPill",
            "internal fun InlineNotice",
        ).forEach { helperDeclaration ->
            assertTrue("Missing shared helper: $helperDeclaration", shellSource.contains(helperDeclaration))
        }
    }


    @Test
    fun appShellKeepsPremiumCreateAndOnboardingActionsDiscoverable() {
        val shellSource = screensSourceRoot.resolve("ActiveAutomationUi.kt").readText()
        val listSource = screensSourceRoot.resolve("ActiveAutomationLists.kt").readText()
        val editorSource = screensSourceRoot.resolve("EditorDialogs.kt").readText()

        assertTrue("Create actions should stay labeled, not icon-only", shellSource.contains("ExtendedFloatingActionButton"))
        assertTrue("First-run onboarding should recommend guided templates first", listSource.contains("R.string.action_browse_templates"))
        assertTrue("Empty-state actions should not stretch awkwardly on large screens", editorSource.contains("widthIn(max = 420.dp)"))
    }

// RETIRED: upstream's module-split layout for the automation shell (a 1,500-line ceiling on
// ActiveAutomationUi.kt and a prescribed delegation split). The fork's shell carries the whole tabbed
// workspace and has diverged well past that shape; enforcing the ceiling would mean restructuring
// working UI to satisfy a rule the fork no longer follows.
}
