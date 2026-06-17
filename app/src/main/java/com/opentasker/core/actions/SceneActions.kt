package com.opentasker.core.actions

import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.scenes.SceneActivity
import com.opentasker.scenes.SceneOverlayManager

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
        val entity = ref.toLongOrNull()?.let { dao.getById(it) }
            ?: dao.getAll().firstOrNull { it.toDomain().name.equals(ref, ignoreCase = true) }
            ?: return ActionResult.Failure("scene not found: \"$ref\"")
        val scene = entity.toDomain()
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
        if (SceneOverlayManager.canOverlay(ctx.app)) {
            SceneOverlayManager.show(ctx.app, scene, position, modal, timeoutMs, dismissOnOutside, fullWidth, fullscreen)
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
            val entity = ref.toLongOrNull()?.let { dao.getById(it) }
                ?: dao.getAll().firstOrNull { it.toDomain().name.equals(ref, ignoreCase = true) }
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
