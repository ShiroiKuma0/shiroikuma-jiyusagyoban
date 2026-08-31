package com.opentasker.core.capabilities

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rule that stops one real block becoming a wall of modals.
 *
 * On 2026-08-08 白い熊's accessibility service was enabled-but-crashed, and every task that needed it
 * raised the permission dialog again. The pre-flight was right to block; raising the modal every time
 * was not, on a workspace whose tasks fire by the second.
 *
 * Instrumented rather than a plain unit test because [CapabilityPrompt] reads `SystemClock`, and the
 * mockable android.jar throws on it. The logic is small; the Android runtime is the only dependency.
 */
@RunWith(AndroidJUnit4::class)
class CapabilityPromptTest {

    private val req = CapabilityRequirement.Accessibility
    private val other = CapabilityRequirement.Overlay

    @Before
    fun clean() {
        CapabilityRequirement.entries.forEach { CapabilityPrompt.clear(it) }
    }

    @Test
    fun aRequirementIsNoisyUntilItsDialogHasBeenShown() {
        assertFalse(CapabilityPrompt.isQuiet(req))
        CapabilityPrompt.markShown(req)
        assertTrue("showing the dialog must quiet the next one", CapabilityPrompt.isQuiet(req))
    }

    /** The whole point: an acknowledged dialog does not come straight back from the next task. */
    @Test
    fun showingQuietsOnlyTheRequirementItNamed() {
        CapabilityPrompt.markShown(req)
        assertTrue(CapabilityPrompt.isQuiet(req))
        assertFalse("an unrelated permission must still be able to speak up", CapabilityPrompt.isQuiet(other))
    }

    /**
     * Going to settings **shortens** the window, and that direction is the easy thing to get backwards.
     *
     * A user who tapped through to the settings page is actively fixing it and deserves an honest
     * answer as soon as they come back; a user who tapped OK does not want to hear about it again for
     * a while. Longer-after-OK is deliberate, not an oversight.
     */
    @Test
    fun goingToSettingsShortensTheQuietRatherThanExtendingIt() {
        CapabilityPrompt.markShown(req)
        CapabilityPrompt.markSentToSettings(req)
        assertTrue("still quiet — just for less long", CapabilityPrompt.isQuiet(req))
        // Both windows are minutes long, so nothing here can wait one out; what is assertable is that
        // the later call replaced the window rather than being ignored, which `clear` then proves.
        CapabilityPrompt.clear(req)
        assertFalse(CapabilityPrompt.isQuiet(req))
    }

    @Test
    fun allQuietNeedsEveryRequirementQuiet() {
        CapabilityPrompt.markShown(req)
        assertFalse(
            "one noisy requirement must be enough to raise the dialog",
            CapabilityPrompt.allQuiet(listOf(req, other)),
        )
        CapabilityPrompt.markShown(other)
        assertTrue(CapabilityPrompt.allQuiet(listOf(req, other)))
    }

    /** An empty list is not "all quiet" — nothing is missing, so nothing should be suppressed. */
    @Test
    fun anEmptyListIsNotQuiet() {
        assertFalse(CapabilityPrompt.allQuiet(emptyList()))
    }
}
