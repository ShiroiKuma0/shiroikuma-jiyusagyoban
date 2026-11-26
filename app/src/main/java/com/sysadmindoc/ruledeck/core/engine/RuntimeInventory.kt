package com.sysadmindoc.ruledeck.core.engine

import android.content.Context
import com.sysadmindoc.ruledeck.core.bubbles.FlashBubbleOverlayManager
import com.sysadmindoc.ruledeck.core.bubbles.FreezeBubbleOverlayManager
import com.sysadmindoc.ruledeck.core.progress.ProgressPanel
import com.sysadmindoc.ruledeck.core.scenes.SceneOverlayService
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.progress.ProgressPanelManager
import com.sysadmindoc.ruledeck.scenes.SceneActivity
import com.sysadmindoc.ruledeck.scenes.SceneOverlayManager

/**
 * Everything this app has live right now, in one list — in-flight tasks, scenes on screen, bubbles, the
 * progress panel, the engine service itself — each with the handle that stops it.
 *
 * Two readers, deliberately the same code: the Monitor's "Live now" section (where a stray overlay can
 * be looked at and stopped **without** exiting), and the shutdown report (what survived the run-on-exit
 * tasks, which is by definition a leak worth seeing). Anything listed here after a clean teardown is a
 * bug — that is the whole point of showing it.
 */
object RuntimeInventory {

    enum class Kind(val title: String) {
        ENGINE("Engine"),
        TASK("Running task"),
        SCENE("Scene"),
        BUBBLE("Bubble"),
        PANEL("Panel"),
    }

    /**
     * One live thing. [expected] marks what is *supposed* to still be up when the shutdown sequence
     * takes its snapshot — only the engine service, which is stopped last on purpose. Everything else
     * should already be gone by then, so the report lists it.
     */
    data class LiveItem(
        val key: String,
        val kind: Kind,
        val label: String,
        val detail: String,
        val expected: Boolean = false,
        val stop: (Context) -> Unit,
    )

    /** What is live right now. Cheap enough for the Monitor's once-a-second refresh. */
    fun snapshot(context: Context): List<LiveItem> = buildList {
        val now = System.currentTimeMillis()

        if (AutomationService.isRunning) {
            add(
                LiveItem(
                    key = "engine",
                    kind = Kind.ENGINE,
                    label = tr("Automation engine"),
                    detail = tr("foreground service"),
                    expected = true,
                    stop = { ctx -> AutomationService.stop(ctx) },
                ),
            )
        }

        RunningTasks.snapshot().forEach { run ->
            add(
                LiveItem(
                    key = "task-${run.runId}",
                    kind = Kind.TASK,
                    label = run.name,
                    detail = tr("%1\$s · running %2\$ss", run.source.lowercase(), (now - run.startedAt) / 1000),
                    stop = { RunningTasks.cancel(run.runId) },
                ),
            )
        }

        SceneOverlayManager.shownScenes().forEach { (id, name) ->
            add(
                LiveItem(
                    key = "scene-$id",
                    kind = Kind.SCENE,
                    label = name,
                    detail = tr("overlay window"),
                    stop = { SceneOverlayManager.hide(id) },
                ),
            )
        }

        val sceneActivities = SceneActivity.openCount()
        if (sceneActivities > 0) {
            add(
                LiveItem(
                    key = "scene-activities",
                    kind = Kind.SCENE,
                    label = tr("Scene activities"),
                    detail = tr("%1\$s open", sceneActivities),
                    stop = { SceneActivity.dismissAll() },
                ),
            )
        }

        if (SceneOverlayService.isRunning) {
            add(
                LiveItem(
                    key = "scene-service",
                    kind = Kind.SCENE,
                    label = tr("Scene overlay service"),
                    detail = tr("foreground service"),
                    stop = { ctx -> SceneOverlayService.dismiss(ctx) },
                ),
            )
        }

        FreezeBubbleOverlayManager.shownPackages().forEach { pkg ->
            add(
                LiveItem(
                    key = "freeze-$pkg",
                    kind = Kind.BUBBLE,
                    label = pkg,
                    detail = tr("freeze bubble"),
                    stop = { FreezeBubbleOverlayManager.stop() },
                ),
            )
        }

        FlashBubbleOverlayManager.shownKeys().forEach { key ->
            add(
                LiveItem(
                    key = "flash-$key",
                    kind = Kind.BUBBLE,
                    label = key,
                    detail = tr("flash bubble"),
                    stop = { FlashBubbleOverlayManager.stop() },
                ),
            )
        }

        if (ProgressPanelManager.isShowing() || ProgressPanel.state.value != null) {
            add(
                LiveItem(
                    key = "progress-panel",
                    kind = Kind.PANEL,
                    label = tr("Progress panel"),
                    detail = if (ProgressPanelManager.isShowing()) tr("on screen") else tr("state left behind"),
                    stop = {
                        ProgressPanelManager.hide()
                        ProgressPanel.hide()
                    },
                ),
            )
        }
    }

    /** The leftovers a shutdown report should name: everything except the engine we stop ourselves. */
    fun leftovers(context: Context): List<LiveItem> = snapshot(context).filterNot { it.expected }

    /**
     * Stop everything, in the order that avoids re-arming: in-flight tasks first (they are what would
     * put an overlay back up), then the visible layers, then the engine last so nothing it owns is
     * orphaned mid-teardown.
     */
    fun teardown(context: Context) {
        RunningTasks.cancelAll()
        ProgressPanelManager.hide()
        ProgressPanel.hide()
        SceneOverlayManager.hideAll()
        SceneActivity.dismissAll()
        SceneOverlayService.dismiss(context)
        FreezeBubbleOverlayManager.stop()
        FlashBubbleOverlayManager.stop()
        AutomationService.stop(context)
    }

    /** One-line-per-item rendering for the run log, so the shutdown report survives the dialog. */
    fun describe(items: List<LiveItem>): String =
        if (items.isEmpty()) {
            "Nothing was left running."
        } else {
            items.joinToString("; ") { "${it.kind.title}: ${it.label} (${it.detail})" }
        }
}
