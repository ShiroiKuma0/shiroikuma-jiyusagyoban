package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `Set Action Field` — write a value into ANOTHER task's action argument, persisting it to the DB. Lets a
 * task edit a "config" task in place: e.g. a picker writes its result back into the `…の設定` task's
 * `var.set value`, so the choice survives the next startup instead of being clobbered by the baked default.
 *
 * Target the action by 0-based [index], or by [matchType] (its action id) and/or [matchName] (its `name`
 * arg — the variable a `var.set`/`var.clear` writes). The first action satisfying the given matchers wins.
 * The written [value] is %-expanded first, so `value=%SC_Blacklist` bakes the CURRENT value in literally.
 */
class EditActionAction : Action {
    override val id = "task.editaction"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val taskName = ctx.variables.expand(args["task"].orEmpty()).trim()
        if (taskName.isEmpty()) return ActionResult.Failure("task name is required")
        val key = args["key"]?.trim().orEmpty().ifEmpty { "value" }
        val value = ctx.variables.expand(args["value"].orEmpty())
        val index = args["index"]?.trim()?.toIntOrNull()
        val matchType = args["matchType"]?.trim()?.takeIf { it.isNotEmpty() }
        val matchName = args["matchName"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() }

        val db = OpenTaskerApp_NoHilt.db
        val error = withContext(Dispatchers.IO) {
            val task = db.taskDao().getByName(taskName)?.toDomain()
                ?: return@withContext "no task named \"$taskName\""
            val actions = task.actions
            val i = if (index != null) index else actions.indexOfFirst { a ->
                (matchType == null || a.type == matchType) &&
                    (matchName == null || a.args["name"]?.trim()?.removePrefix("%") == matchName)
            }
            if (i < 0 || i >= actions.size) return@withContext "no matching action in \"$taskName\""
            val updated = actions.toMutableList().also { it[i] = it[i].copy(args = it[i].args + (key to value)) }
            db.taskDao().update(task.copy(actions = updated).toEntity())
            null
        }
        return if (error == null) {
            ctx.logger("Set $taskName #$key")
            ActionResult.Success
        } else {
            ActionResult.Failure(error)
        }
    }
}
