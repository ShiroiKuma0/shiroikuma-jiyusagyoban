package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.bubbles.FreezeBubbleTarget
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.dialog.DialogOutcome
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.widget.TaskShortcutClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Put one existing launcher task on the clipboard for 白い熊 雷起動盤's 「貼り付け」 add item.
 *
 * `tasks.launchers` copies the tasks it has just created; this is the way back to every task made
 * before that — or made, pasted, and later wanted in a second directory. The same icon grid as the
 * generator, restricted to the apps the launcher tasks in scope open, in single-select mode: **one tap
 * copies that app's task and closes the dialog**, nothing to confirm. Cancel copies nothing.
 *
 * Scope is `project` / `group`, read the way `tasks.freezebubbles` reads them (both = that group,
 * project alone = the project, neither = every task). An app whose task outlived its install still gets
 * a tile (its bare package), so its shortcut can be pasted again on a phone that has it.
 *
 * Two tasks launching the same app both go on the clipboard — a tile is an app, and choosing between
 * its tasks is what the launcher's own edit step is for.
 */
class CopyLauncherShortcutAction : Action {
    override val id = "tasks.copyshortcut"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val projectName = ctx.variables.expand(args["project"].orEmpty()).trim()
        val groupName = ctx.variables.expand(args["group"].orEmpty()).trim()
        val title = ctx.variables.expand(args["title"].orEmpty()).trim().ifEmpty { "Copy for 貼り付け" }
        val db = OpenTaskerApp_NoHilt.db

        // package → the names of the launcher tasks in scope that open it, in name order.
        val byPackage: Map<String, List<String>> = withContext(Dispatchers.IO) {
            val projectId: Long? = if (projectName.isEmpty()) {
                null
            } else {
                db.projectDao().getAll().firstOrNull { it.name == projectName }?.id
                    ?: return@withContext null
            }
            val groupTaskIds: Set<Long>? = if (groupName.isEmpty()) {
                null
            } else {
                val groupIds = db.itemGroupDao().getForTab("tasks")
                    .filter { it.name == groupName && (projectId == null || it.projectId == projectId) }
                    .map { it.id }
                    .toSet()
                if (groupIds.isEmpty()) return@withContext null
                db.itemMetaDao().getForTab("tasks")
                    .filter { it.groupId in groupIds }
                    .mapNotNull { it.itemKey.toLongOrNull() }
                    .toSet()
            }
            db.taskDao().getAll()
                .filter { entity ->
                    when {
                        groupTaskIds != null -> entity.id in groupTaskIds
                        projectId != null -> entity.projectId == projectId
                        else -> true
                    }
                }
                .mapNotNull { entity ->
                    val actions = entity.toDomainDecodeResult().value.actions
                    FreezeBubbleTarget.packageOf(actions) { ctx.variables.expand(it) }?.let { it to entity.name }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, names) -> names.sorted() }
        } ?: return ActionResult.Failure(
            if (groupName.isNotEmpty()) "group not found: $groupName" else "project not found: $projectName",
        )
        if (byPackage.isEmpty()) return ActionResult.Failure("no launcher tasks found — nothing to copy")

        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_APP_SINGLESELECT)
            putExtra(DialogActivity.EXTRA_TITLE, title)
            putExtra(DialogActivity.EXTRA_PACKAGES, byPackage.keys.joinToString("\n"))
            putExtra(DialogActivity.EXTRA_SHOW_MISSING, true)
        }
        val pkg = when (outcome) {
            is DialogOutcome.Confirmed -> outcome.value.trim()
            DialogOutcome.Cancelled -> return ActionResult.Success
        }
        val names = byPackage[pkg].orEmpty()
        if (names.isEmpty()) return ActionResult.Success

        withContext(Dispatchers.Main) { TaskShortcutClip.copy(ctx.app, names) }
        ctx.logger("Copied for 白い熊 雷起動盤's 「貼り付け」: ${names.joinToString(", ")}")
        return ActionResult.Success
    }
}
