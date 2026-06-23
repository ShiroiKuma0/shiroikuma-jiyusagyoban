package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.dialog.DialogOutcome
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.storage.ItemGroupEntity
import com.opentasker.core.storage.ItemMetaEntity
import com.opentasker.core.storage.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pick apps with a multi-select picker, then generate one "unfreeze-then-launch" task per chosen app,
 * filed alphabetically into a named group of a named project's Tasks tab.
 *
 * Each generated task runs `app.unfreeze` (= `pm enable`, a harmless no-op when the app is already
 * enabled) followed by `app.launch`, so a frozen app is thawed and started in one tap.
 */
class MakeLauncherTasksAction : Action {
    override val id = "tasks.launchers"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val projectName = args["project"]?.trim().orEmpty()
        if (projectName.isEmpty()) return ActionResult.Failure("project name is required")
        val groupName = args["group"]?.trim().orEmpty()
        if (groupName.isEmpty()) return ActionResult.Failure("group name is required")
        val suffix = args["suffix"].orEmpty()

        val db = OpenTaskerApp_NoHilt.db

        // 1. Resolve the project + group (creating the group if missing) on IO.
        val resolved = withContext(Dispatchers.IO) {
            val project = db.projectDao().getAll().firstOrNull { it.name == projectName }
                ?: return@withContext null
            val pid = project.id
            val tasksGroups = db.itemGroupDao().getForTab("tasks")
            val existing = tasksGroups.firstOrNull {
                it.projectId == pid && it.tab == "tasks" && it.name == groupName
            }
            val groupId = existing?.id ?: db.itemGroupDao().upsert(
                ItemGroupEntity(
                    projectId = pid,
                    tab = "tasks",
                    name = groupName,
                    position = tasksGroups.size,
                ),
            )
            pid to groupId
        } ?: return ActionResult.Failure("project not found: $projectName")
        val (pid, groupId) = resolved

        // 2. Show the multi-select app picker.
        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_APP_MULTISELECT)
            putExtra(DialogActivity.EXTRA_TITLE, "Select apps")
        }
        val picked: List<Pair<String, String>> = when (outcome) {
            is DialogOutcome.Confirmed -> outcome.value
                .split("\n")
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("\t", limit = 2)
                    val pkg = parts.getOrNull(0)?.trim().orEmpty()
                    val label = parts.getOrNull(1)?.trim().orEmpty()
                    if (pkg.isEmpty()) null else pkg to label.ifEmpty { pkg }
                }
            DialogOutcome.Cancelled -> return ActionResult.Success
        }

        val created = withContext(Dispatchers.IO) {
            // Existing task names already in this group — to skip duplicates on re-run.
            val metaForTasks = db.itemMetaDao().getForTab("tasks")
            val groupTaskIds = metaForTasks.filter { it.groupId == groupId }
                .mapNotNull { it.itemKey.toLongOrNull() }
                .toSet()
            val allTasks = db.taskDao().getAll()
            val existingNamesInGroup = allTasks
                .filter { it.id in groupTaskIds }
                .map { it.name }
                .toMutableSet()

            var count = 0
            for ((pkg, label) in picked) {
                val taskName = "$label$suffix"
                if (taskName in existingNamesInGroup) continue
                val actions = listOf(
                    ActionSpec(type = "app.unfreeze", args = mapOf("package" to pkg)),
                    ActionSpec(type = "app.launch", args = mapOf("package" to pkg)),
                )
                val entity = TaskEntity(
                    id = 0,
                    name = taskName,
                    priority = 5,
                    collisionMode = "ABORT_NEW",
                    actionsJson = Json.encodeToString(actions),
                    projectId = pid,
                    position = 0,
                    // Default the task's icon to the selected app's icon (snapshotted to a PNG).
                    iconPath = TaskIconStore.saveFromApp(pkg),
                )
                val newId = db.taskDao().insert(entity)
                db.itemMetaDao().upsert(
                    ItemMetaEntity(tab = "tasks", itemKey = newId.toString(), groupId = groupId),
                )
                existingNamesInGroup += taskName
                count++
            }

            // 3. Keep the group at the BOTTOM and its tasks in alphabetical order, re-sorted on EVERY
            // run: give each task in the group a position strictly above every NON-group task in the
            // project (so the group sits below the standalone generator task), assigned in name order.
            // The grouped list orders items by TaskEntity.position; ItemMetaEntity.position is set too.
            val metaNow = db.itemMetaDao().getForTab("tasks")
            val idsInGroup = metaNow.filter { it.groupId == groupId }
                .mapNotNull { it.itemKey.toLongOrNull() }
                .toSet()
            val allNow = db.taskDao().getAll()
            val maxOther = allNow
                .filter { it.projectId == pid && it.id !in idsInGroup }
                .maxOfOrNull { it.position } ?: -1
            val sorted = allNow.filter { it.id in idsInGroup }.sortedBy { it.name.lowercase() }
            sorted.forEachIndexed { index, task ->
                val newPos = maxOther + 1 + index
                if (task.position != newPos) db.taskDao().setPosition(task.id, newPos)
                val meta = db.itemMetaDao().get("tasks", task.id.toString())
                if (meta != null && meta.position != index) {
                    db.itemMetaDao().upsert(meta.copy(position = index))
                }
            }
            count
        }

        ctx.logger("Created $created launcher tasks in $groupName")
        return ActionResult.Success
    }
}
