package com.opentasker.core.actions

import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.variables.expandAgainstGlobals
import com.opentasker.core.model.Scene
import com.opentasker.core.storage.SceneDao
import com.opentasker.core.storage.SceneEntity
import com.opentasker.scenes.SceneActivity
import com.opentasker.scenes.SceneOverlayManager
import com.opentasker.scenes.WakedanceActivity

/**
 * Resolve a scene reference (from `scene.show` / `scene.hide`) to a stored scene.
 *
 * Scenes are linked by **name**, keyed by `(project, name)` — a name is unique within a project, but
 * the same name may exist in different projects. So a reference resolves, in order:
 *   1. a scene with that name in the **caller's project** (the common case — survives re-imports that
 *      re-id the scene, and disambiguates same-name scenes across projects);
 *   2. otherwise any scene with that name (cross-project show), chosen deterministically (lowest
 *      position, then id) so it never flips between equally-named candidates;
 *   3. finally, a purely-numeric ref as a raw id — back-compat only; a real name always wins.
 *
 * [callerProjectId] is the running task's project (0 = Unfiled/super); a scene's null projectId is
 * also Unfiled, so the two are compared as `(projectId ?: 0)`.
 */
internal suspend fun resolveScene(dao: SceneDao, ref: String, callerProjectId: Long): SceneEntity? {
    val all = dao.getAll()
    all.firstOrNull { (it.projectId ?: 0L) == callerProjectId && it.name.equals(ref, ignoreCase = true) }
        ?.let { return it }
    all.filter { it.name.equals(ref, ignoreCase = true) }
        .minByOrNull { it.position.toLong() * 10_000_000L + it.id }
        ?.let { return it }
    return ref.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } }
}

/** Expand %vars in the scene's PANEL colors (bg / border) against the persisted globals at show time.
 *  Element configs are deliberately left RAW: `SceneElementView.v()` re-reads + expands them LIVE on
 *  every variable change (the 電池線 battery line / charge sweep, live text, …). Pre-expanding the
 *  element configs here froze them to their show-time snapshot — the cause of stale battery/clock. */
private fun Scene.withGlobalsExpanded(): Scene = copy(
    bgColor = bgColor?.let { expandAgainstGlobals(it) },
    borderColor = borderColor?.let { expandAgainstGlobals(it) },
)

/**
 * `Show Scene` — display a scene (by name or id). With the "Display over other apps" permission it
 * shows as a system-wide overlay (works over other apps and from background triggers); without it,
 * it falls back to a foreground Activity (only visible while this app is in front).
 */
class ShowSceneAction : Action {
    override val id = "scene.show"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ref = args["scene"]?.trim().orEmpty()
        if (ref.isEmpty()) return ActionResult.Failure("missing scene name")
        val dao = OpenTaskerApp_NoHilt.db.sceneDao()
        val entity = resolveScene(dao, ref, ctx.variables.projectId)
            ?: return ActionResult.Failure("scene not found: \"$ref\"")
        val scene = entity.toDomain().withGlobalsExpanded()
        // Resolution for the presentation flags: an explicit arg wins; otherwise fall back to the
        // scene's own remembered default (set in the editor).
        fun boolArg(key: String): Boolean? = args[key]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { it !in setOf("false", "0", "off", "no") }
        val position = args["position"]?.trim()?.lowercase()?.ifBlank { null } ?: scene.defaultPosition
        val modal = boolArg("modal") ?: scene.defaultModal
        val timeoutMs = (args["timeout"]?.trim()?.toDoubleOrNull()?.times(1000))?.toLong()?.coerceAtLeast(0L) ?: 0L
        val dismissOnOutside = boolArg("dismissOnOutside") ?: scene.defaultDismissOnOutside
        // fullWidth: span the screen (status-bar style); ignored for modal scenes. Used by the battery line.
        val fullWidth = boolArg("fullWidth") ?: false
        // fullscreen: cover the whole screen, fully tap-through (a purely visual overlay). The music edge-light.
        val fullscreen = boolArg("fullscreen") ?: false
        // edgeCenter: for a left/right overlay, sit vertically centred (default left/right drops lower for media HUDs).
        val edgeCenter = boolArg("edgeCenter") ?: false
        // inset: dp to pull a left/right overlay in from the very edge (clears the OEM edge-gesture region).
        val insetDp = args["inset"]?.trim()?.toIntOrNull() ?: 0
        // heightFraction: 0..1 of the screen height (re-sized on fold/rotation). Used by the edge strips.
        val heightFraction = args["heightFraction"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        // vAlign: top/center/bottom — which third a left/right edge strip sits in.
        val vAlign = args["vAlign"]?.trim()?.lowercase()?.ifBlank { null }
        // widthFraction + hAlign: a bottom edge strip's width (0..1 of the screen) and which third (left/center/right).
        val widthFraction = args["widthFraction"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        val hAlign = args["hAlign"]?.trim()?.lowercase()?.ifBlank { null }
        // showWhenLocked: render over the lockscreen without unlocking (the tsuchi wakedance).
        val showWhenLocked = boolArg("showWhenLocked") ?: false
        if (showWhenLocked) {
            // Over the lockscreen: an Activity with setShowWhenLocked is the path EMUI honours (the
            // accessibility overlay sits UNDER the keyguard there, and FLAG_SHOW_WHEN_LOCKED on it is
            // ignored). Renders the scene fullscreen + wakes the screen; auto-dismisses on the timeout.
            val intent = Intent(ctx.app, WakedanceActivity::class.java).apply {
                putExtra(SceneActivity.EXTRA_SCENE_ID, entity.id)
                putExtra(SceneActivity.EXTRA_SHOW_WHEN_LOCKED, true)
                putExtra(SceneActivity.EXTRA_FULLSCREEN, true)
                if (timeoutMs > 0) putExtra(SceneActivity.EXTRA_TIMEOUT_MS, timeoutMs)
                // Opaque WakedanceActivity self-finishes at the end of each pulse, so it's gone before the
                // next — a fresh instance without CLEAR_TASK (which revealed the launcher/wallpaper).
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            ctx.app.startActivity(intent)
            ctx.logger("Show scene \"${scene.name}\" over lockscreen (Activity)")
        } else if (SceneOverlayManager.canOverlay(ctx.app)) {
            SceneOverlayManager.show(ctx.app, scene, position, modal, timeoutMs, dismissOnOutside, fullWidth, fullscreen, edgeCenter, insetDp, heightFraction, vAlign, widthFraction, hAlign, showWhenLocked)
            ctx.logger("Show scene \"${scene.name}\" (overlay, ${if (modal) "modal" else "tap-through"})")
        } else {
            val intent = Intent(ctx.app, SceneActivity::class.java).apply {
                putExtra(SceneActivity.EXTRA_SCENE_ID, entity.id)
                position?.let { putExtra(SceneActivity.EXTRA_POSITION, it) }
                if (timeoutMs > 0) putExtra(SceneActivity.EXTRA_TIMEOUT_MS, timeoutMs)
                putExtra(SceneActivity.EXTRA_DISMISS_OUTSIDE, dismissOnOutside)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.app.startActivity(intent)
            ctx.logger("Show scene \"${scene.name}\" (foreground; grant Display over other apps for system-wide)")
        }
        return ActionResult.Success
    }
}

/**
 * `Hide Scene` — dismiss shown scenes. With no args, hides every scene; with a `scene` arg
 * (name or id), hides just that one (so e.g. hiding the music edge-light leaves the battery line up).
 */
class HideSceneAction : Action {
    override val id = "scene.hide"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ref = args["scene"]?.trim().orEmpty()
        if (ref.isNotEmpty()) {
            val dao = OpenTaskerApp_NoHilt.db.sceneDao()
            val entity = resolveScene(dao, ref, ctx.variables.projectId)
                ?: return ActionResult.Failure("scene not found: \"$ref\"")
            SceneOverlayManager.hide(entity.id)
            ctx.logger("Hid scene \"${entity.toDomain().name}\"")
            return ActionResult.Success
        }
        val n = SceneOverlayManager.hideAll() + SceneActivity.dismissAll()
        ctx.logger("Hid $n scene(s)")
        return ActionResult.Success
    }
}
