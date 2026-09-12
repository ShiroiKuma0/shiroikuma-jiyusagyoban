package com.opentasker.core.scenes

import com.opentasker.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom edge bars are the only overlays hosted by the accessibility service (they carry a
 * `widthFraction`, which is what buys them a TYPE_ACCESSIBILITY_OVERLAY and with it the bottom system
 * gesture). A window added through that service dies with it, and the framework tears every
 * accessibility service down and re-reads them on any package install.
 *
 * Before 2026-09-12 nothing noticed: `active` kept the dead scene ids, `show()`'s "already showing"
 * guard made every later 辺表示 a no-op for exactly those scenes, and 白い熊's bottom toolbar was dead
 * until the next reboot with no log line anywhere to say why. These gates hold the recovery wiring in
 * place — each slices the source between markers, so deleting the code under test fails the gate
 * rather than silently widening it to the whole file.
 */
class SceneOverlayA11yRehostTest {

    private val manager = "com/opentasker/scenes/SceneOverlayManager.kt"
    private val service = "com/opentasker/core/accessibility/ShiroiKumaAccessibilityService.kt"

    @Test
    fun serviceTellsTheOverlayManagerWhenItBinds() {
        val connected = ProductionSources.block(
            service,
            start = "override fun onServiceConnected()",
            end = "override fun onUnbind(",
        )
        assertTrue(
            "onServiceConnected must re-host the overlays the previous binding owned",
            connected.contains("SceneOverlayManager.onAccessibilityServiceConnected()"),
        )
    }

    @Test
    fun serviceTellsTheOverlayManagerWhenItGoesAway() {
        val teardown = ProductionSources.block(
            service,
            start = "override fun onUnbind(",
            end = "override fun onAccessibilityEvent(",
        )
        val notifications = teardown.split("SceneOverlayManager.onAccessibilityServiceGone()").size - 1
        assertTrue(
            "Both onUnbind and onDestroy must release the hosted overlays (found $notifications)",
            notifications >= 2,
        )
    }

    @Test
    fun onlyAccessibilityHostedOverlaysCarryARespawn() {
        val recorded = ProductionSources.block(
            manager,
            start = "val respawn: (() -> Unit)? =",
            end = "runCatching { wm.addView",
        )
        assertTrue(
            "An app-context overlay is never taken away behind our back, so it needs no respawn",
            recorded.contains("if (a11y == null) null"),
        )
    }

    @Test
    fun teardownForgetsTheDeadOverlaysSoTheyCanBeShownAgain() {
        val gone = ProductionSources.block(
            manager,
            start = "fun onAccessibilityServiceGone()",
            end = "fun onAccessibilityServiceConnected()",
        )
        assertTrue(
            "The dead entry must leave `active`, or show() keeps refusing it as already showing",
            gone.contains("remove(id)"),
        )
        assertTrue(
            "Its respawn thunk must be kept for the rebind",
            gone.contains("pendingA11yRespawn[id] = thunk"),
        )
    }

    @Test
    fun rebindReplacesAnUntrustedFallbackRatherThanLeavingIt() {
        val back = ProductionSources.block(
            manager,
            start = "fun onAccessibilityServiceConnected()",
            end = "private fun runTask(",
        )
        assertTrue(
            "A 辺表示 run while the service was away leaves an untrusted window that must be dropped first",
            back.contains("remove(id)"),
        )
        assertTrue("The kept thunks must actually be invoked", back.contains("thunk()"))
        assertTrue(
            "Iterating the live map while remove() writes to it would throw",
            back.contains("pendingA11yRespawn.toMap()"),
        )
    }

    @Test
    fun hidingAnOverlayDropsItsPendingRespawn() {
        val remove = ProductionSources.block(
            manager,
            start = "private fun remove(sceneId: Long)",
            end = "fun onAccessibilityServiceGone()",
        )
        assertTrue(
            "A scene hidden while the service is away must not come back when it rebinds",
            remove.contains("pendingA11yRespawn.remove(sceneId)"),
        )
    }

    @Test
    fun aFailedAddViewIsNoLongerSilent() {
        val failure = ProductionSources.block(
            manager,
            start = ".onFailure {",
            end = "private fun sceneGravity(",
        )
        assertTrue(
            "A dropped overlay must say so — the silence is what made this bug invisible",
            failure.contains("AppLogger.warn("),
        )
    }
}
