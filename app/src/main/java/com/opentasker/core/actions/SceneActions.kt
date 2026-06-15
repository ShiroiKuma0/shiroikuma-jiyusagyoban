package com.opentasker.core.actions

import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.scenes.SceneActivity

/** `Show Scene` — display a scene (by name or id) as a modal overlay. Its elements run tasks on tap. */
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
        val intent = Intent(ctx.app, SceneActivity::class.java).apply {
            putExtra(SceneActivity.EXTRA_SCENE_ID, entity.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.app.startActivity(intent)
        ctx.logger("Show scene \"${entity.toDomain().name}\"")
        return ActionResult.Success
    }
}

/** `Hide Scene` — dismiss any scene currently shown. */
class HideSceneAction : Action {
    override val id = "scene.hide"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val n = SceneActivity.dismissAll()
        ctx.logger("Hid $n scene(s)")
        return ActionResult.Success
    }
}
