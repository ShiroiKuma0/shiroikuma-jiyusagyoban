package com.opentasker.ui

import com.opentasker.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A disclosure row built from a bare `Modifier.clickable { expanded = !expanded }` announces only
 * "double tap to activate": no role, and nothing that says whether the section is already open, so
 * the same words are read whether the next tap opens or closes it. Seven cards shipped that way.
 *
 * The fix is `Modifier.expandCollapseToggle`, which carries the role, the state and the click
 * label. This gate keeps the bare form from coming back, because the difference is invisible on
 * screen and only a screen reader notices.
 */
class ExpandCollapseSemanticsSourceTest {

    private val screensDirectory = ProductionSources.path("com/opentasker/ui/screens")

    private fun screenSources(): List<Pair<String, String>> =
        Files.list(screensDirectory).use { stream ->
            stream.filter { it.name.endsWith(".kt") }
                .map { it.name to it.readText() }
                .toList()
        }

    @Test
    fun noScreenTogglesAnExpandedFlagFromABareClickable() {
        // Matches `.clickable { <name>Expanded = !<name>Expanded }` and the plain `expanded` case,
        // in either the trailing-lambda or the onClick form, with no role or semantics attached.
        val bare = Regex("""\.clickable\s*(?:\(\s*\))?\s*\{\s*(\w*[eE]xpanded)\s*=\s*!\1\s*}""")
        val offenders = screenSources().mapNotNull { (name, source) ->
            bare.find(source)?.let { "$name: ${it.value.trim()}" }
        }

        assertTrue(
            "A disclosure row must use Modifier.expandCollapseToggle so a screen reader gets the " +
                "role and the expanded state. Bare toggles found: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun theSharedToggleCarriesRoleStateAndClickLabel() {
        // The helper is the only thing standing behind the gate above, so its own contents are the
        // real assertion: if it stopped setting any of the three, every call site would go quiet
        // again and the regex gate would still pass.
        val helper = ProductionSources.read("com/opentasker/ui/utils/ExpandCollapseSemantics.kt")

        assertTrue("the toggle must set a state description", helper.contains("stateDescription = stateLabel"))
        assertTrue("the toggle must declare the button role", helper.contains("role = Role.Button"))
        assertTrue("the toggle must label the click", helper.contains("onClickLabel = actionLabel"))
        assertTrue("expanded and collapsed must be distinct strings", helper.contains("R.string.a11y_expanded"))
        assertTrue("expanded and collapsed must be distinct strings", helper.contains("R.string.a11y_collapsed"))
    }

    @Test
    fun asyncResultSurfacesAnnounceThemselves() {
        // Each of these replaces its own text when work the user started finishes, without moving
        // focus, so without a live region the completion is silent.
        val surfaces = mapOf(
            "com/opentasker/ui/screens/PermissionOnboardingScreen.kt" to "the backup banner",
            "com/opentasker/ui/screens/ImportReviewDialogs.kt" to "the import and export stage label",
            "com/opentasker/ui/screens/PreflightReviewDialog.kt" to "the preflight report title",
        )

        surfaces.forEach { (path, description) ->
            val source = ProductionSources.read(path)
            assertTrue(
                "$description must be a polite live region so a finished run is announced",
                source.contains("liveRegion = LiveRegionMode.Polite"),
            )
        }
    }
}
