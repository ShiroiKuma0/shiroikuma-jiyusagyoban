package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WindowModeActionTest {
    // Trimmed from a real `dumpsys activity activities` on the Mate XT: tasks top of the z-order first.
    private fun task(id: Int, type: String, affinity: String, visible: Boolean, mode: String, component: String) = """
        |    * Task{3cdb10a #$id type=$type A=10222:$affinity U=0 visible=$visible mode=$mode translucent=false sz=1}
        |      mBounds=Rect(0, 0 - 0, 0)
        |      affinity=10222:$affinity
        |      mActivityComponent=$component
        |      * Hist #0: ActivityRecord{a5cad75 u0 $component t$id}
        |          rootOfTask=true task=Task{3cdb10a #$id type=$type A=10222:$affinity U=0 visible=$visible mode=$mode translucent=false sz=1}
        |          mActivityComponent=$component
    """.trimMargin()

    @Test
    fun theFullScreenAppBehindAFloatingWindowIsTheOneMoved() {
        val dump = listOf(
            task(3, "standard", "shiroikuma.jami", true, "hwMultiwindow-freeform", "shiroikuma.jami/.Main"),
            task(2, "standard", "emacs.primary_frame", true, "fullscreen", "shiroikuma.emacs/org.gnu.emacs.EmacsActivity"),
            task(1, "home", "shiroikuma.raikidoban", false, "fullscreen", "shiroikuma.raikidoban/.Dashboard"),
        ).joinToString("\n")
        assertEquals("shiroikuma.emacs/org.gnu.emacs.EmacsActivity", WindowModeAction.topFullscreenComponent(dump))
    }

    @Test
    fun homeOnScreenMeansNothingToMove() {
        val dump = listOf(
            task(1, "home", "shiroikuma.raikidoban", true, "fullscreen", "shiroikuma.raikidoban/.Dashboard"),
            task(2, "standard", "com.termux", false, "fullscreen", "com.termux/.app.TermuxActivity"),
        ).joinToString("\n")
        assertNull(WindowModeAction.topFullscreenComponent(dump))
    }

    @Test
    fun theActionIsRegistered() {
        assertNotNull(ActionCatalog.get("window.mode"))
    }
}
