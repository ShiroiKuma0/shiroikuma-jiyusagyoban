package com.opentasker.core.bubbles

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The freeze bubble's gestures are workspace tasks, and the fallbacks that keep them honest.
 *
 * 白い熊's goal for this refactor (2026-09-13) was that what a bubble DOES should be readable and
 * editable in the workspace rather than compiled in. These assertions are on the source, because the
 * behaviour they pin needs a window manager, an overlay permission and a real touch to exercise —
 * none of which a JVM test has — while the property that matters is structural.
 */
class FreezeBubbleGesturesTest {

    private val src = ProductionSources.read("com/opentasker/core/bubbles/FreezeBubbleOverlayManager.kt")

    /** The three names are the whole binding between a gesture and the workspace. */
    @Test
    fun `each gesture names the task it runs`() {
        assertEquals("凍結泡 ⇨ 凍結", FreezeBubbleOverlayManager.TASK_FREEZE)
        assertEquals("凍結泡 ⇨ 捨てる", FreezeBubbleOverlayManager.TASK_DISMISS)
        assertEquals("凍結泡 ⇨ 起動", FreezeBubbleOverlayManager.TASK_LAUNCH)
    }

    /**
     * The per-app override is tried BEFORE the general task.
     *
     * Reversing the two would make the override unreachable while quietly still compiling — the
     * general task always resolves, so it would always win.
     */
    @Test
    fun `the per-app override is tried first`() {
        val freeze = ProductionSources.block(
            "com/opentasker/core/bubbles/FreezeBubbleOverlayManager.kt",
            "private fun freezeAndRemove(",
            "FreezeBubbleStore.remove(entry.pkg)",
        )
        val override = freeze.indexOf("overrideNameFor(")
        val general = freeze.indexOf("TASK_FREEZE")
        assertTrue("the override must be attempted at all", override >= 0)
        assertTrue("and before the general task", override < general)
    }

    /**
     * Every gesture keeps a fallback for a workspace that has no such task.
     *
     * Without it a renamed task turns the bubble into a control that retires itself and freezes
     * nothing — indistinguishable from working.
     */
    @Test
    fun `freeze falls back to the built-in behaviour`() {
        val freeze = ProductionSources.block(
            "com/opentasker/core/bubbles/FreezeBubbleOverlayManager.kt",
            "private fun freezeAndRemove(",
            "FreezeBubbleStore.remove(entry.pkg)",
        )
        assertTrue("still freezes when no task resolves", "app.freeze" in freeze)
        assertTrue("over every target, not just the bubble's own package", "targets.map" in freeze)
    }

    @Test
    fun `launch falls back to the built-in behaviour`() {
        val launch = ProductionSources.block(
            "com/opentasker/core/bubbles/FreezeBubbleOverlayManager.kt",
            "private fun launchApp(",
            "\n    }",
        )
        assertTrue("app.launch" in launch)
    }

    /**
     * Long-tap retires the bubble ITSELF, not via its task.
     *
     * The dismissal is the gesture's meaning; the task is for whatever should happen besides it. A
     * workspace with no 捨てる task must still be able to get rid of a bubble.
     */
    @Test
    fun `dismissal does not depend on a task existing`() {
        val dismiss = ProductionSources.block(
            "com/opentasker/core/bubbles/FreezeBubbleOverlayManager.kt",
            "private fun dismissOnly(",
            "\n    }",
        )
        val remove = dismiss.indexOf("FreezeBubbleStore.remove(pkg)")
        val run = dismiss.indexOf("runNamed(")
        assertTrue("the bubble goes regardless", remove >= 0)
        assertTrue("and goes before the task is even looked for", remove < run)
    }

    /**
     * The bubble's context reaches the task as event-LOCALS.
     *
     * `%APP_PACKAGE` as a super-global is rewritten by every foreground change on this phone,
     * including the ones our own overlays provoke, so a task reading it would be reading whichever
     * window moved last rather than the bubble that was tapped.
     */
    @Test
    fun `context is threaded as per-invocation locals`() {
        assertTrue("eventLocals = locals" in src)
        for (key in listOf("APP_PACKAGE", "APP_LABEL", "FREEZE_PACKAGES")) {
            assertTrue("$key must be passed to the task", "\"$key\" to" in src)
        }
        assertTrue(
            "the freeze set is space-separated, the roster convention var.split is pointed at",
            "joinToString(\" \")" in src,
        )
    }
}
