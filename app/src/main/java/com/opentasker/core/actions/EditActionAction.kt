package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.ActionSpec
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

/**
 * `Add Action` — insert an action into ANOTHER task, but only if it isn't there already. Identity is
 * (action type + `name` arg) — the same pair [EditActionAction] matches on — so a task can GROW a config
 * task's roster idempotently: 保存対象選択 gives every newly picked app its `%BR_Token_<App>` /
 * `%BR_Items_<App>` var.set pair in `保存復元の設定 -- [979][01]`, run after run, without ever
 * duplicating a line. (An action type that has no `name` arg falls back to "same type + identical args".)
 *
 * Placement — [at]: `end` (default), `start`, a 0-based index, or `sorted`. `sorted` needs [sortPattern],
 * a regex applied to the `name` arg of every action of the same type; its first capture group is that
 * action's sort key. The new action is appended after the last such action and the whole matched region is
 * then stable-sorted by key, so a new entry lands alphabetically, equal keys keep the order they were added
 * in (Token before Items), and actions outside the region never move.
 *
 * The inserted action's args are [name], [value], plus any `arg:<key>` pass-through; [label] becomes its
 * label and `onError=continue` its continue-on-error flag. Every arg arrives ALREADY %-expanded from the
 * runner and is written through verbatim — deliberately NOT expanded a second time, so a label built with
 * the `%pct☆` trick 保存作成 uses lands in the target task as a LITERAL `%BR_Token_Jami` instead of that
 * variable's value. [store] receives `added` or `exists`.
 */
class AddActionAction : Action {
    override val id = "task.addaction"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val taskName = args["task"].orEmpty().trim()
        if (taskName.isEmpty()) return ActionResult.Failure("task name is required")
        val type = args["type"].orEmpty().trim()
        if (type.isEmpty()) return ActionResult.Failure("action type is required")
        val name = args["name"].orEmpty().trim().removePrefix("%")
        val label = args["label"]?.takeIf { it.isNotEmpty() }
        val at = args["at"].orEmpty().trim().lowercase().ifEmpty { "end" }
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() }
        val sortPattern = args["sortPattern"]?.takeIf { it.isNotEmpty() }?.let {
            runCatching { Regex(it) }.getOrNull()
                ?: return ActionResult.Failure("invalid sortPattern: $it")
        }
        if (at == "sorted" && sortPattern == null) return ActionResult.Failure("at=sorted needs a sortPattern")

        val newArgs = buildMap {
            if (name.isNotEmpty()) put("name", name)
            args["value"]?.let { put("value", it) }
            args.forEach { (k, v) -> if (k.startsWith("arg:")) put(k.removePrefix("arg:"), v) }
        }
        val spec = ActionSpec(
            type = type,
            label = label,
            args = newArgs,
            continueOnError = args["onError"].orEmpty().trim().lowercase() == "continue",
        )
        // A same-type action's sort key, or null when it is outside the sorted region.
        fun keyOf(a: ActionSpec): String? {
            if (sortPattern == null || a.type != type) return null
            val m = sortPattern.find(a.args["name"].orEmpty()) ?: return null
            return m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: m.value
        }

        val db = OpenTaskerApp_NoHilt.db
        val outcome = withContext(Dispatchers.IO) {
            val task = db.taskDao().getByName(taskName)?.toDomain()
                ?: return@withContext "no task named \"$taskName\""
            val already = task.actions.any { a ->
                a.type == type &&
                    if (name.isEmpty()) a.args == newArgs
                    else a.args["name"]?.trim()?.removePrefix("%") == name
            }
            if (already) return@withContext "exists"
            val list = task.actions.toMutableList()
            when {
                at == "start" -> list.add(0, spec)
                at.toIntOrNull() != null -> list.add(at.toInt().coerceIn(0, list.size), spec)
                at == "sorted" -> {
                    val last = list.indexOfLast { keyOf(it) != null }
                    if (last < 0) list.add(spec) else list.add(last + 1, spec)
                    val slots = list.indices.filter { keyOf(list[it]) != null }
                    val sorted = slots.map { list[it] }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { keyOf(it).orEmpty() })
                    slots.forEachIndexed { i, slot -> list[slot] = sorted[i] }
                }
                else -> list.add(spec)
            }
            db.taskDao().update(task.copy(actions = list).toEntity())
            "added"
        }
        if (outcome != "added" && outcome != "exists") return ActionResult.Failure(outcome)
        store?.let { ctx.variables.set(it, outcome) }
        ctx.logger("$outcome: $type ${name.ifEmpty { "" }} → $taskName")
        return ActionResult.Success
    }
}

/**
 * `Task Exists` — store `true`/`false` for whether a task of this name exists, optionally scoped to one
 * [project]. Lets a task decide whether to generate something before calling it: 保存対象選択 checks for
 * each picked app's `保存 ⇨ <pkg>` wrapper and hands the missing ones to 保存作成, instead of leaving
 * 保存項目選択 to die on "sub-task not found".
 */
class TaskExistsAction : Action {
    override val id = "task.exists"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val taskName = args["task"].orEmpty().trim()
        if (taskName.isEmpty()) return ActionResult.Failure("task name is required")
        val projectName = args["project"].orEmpty().trim()
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "exists"

        val db = OpenTaskerApp_NoHilt.db
        val found = withContext(Dispatchers.IO) {
            val pid = if (projectName.isEmpty()) null else
                db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }?.id
                    ?: return@withContext null
            db.taskDao().getAll().any {
                it.name.equals(taskName, ignoreCase = true) && (pid == null || it.projectId == pid)
            }
        } ?: return ActionResult.Failure("no such project: $projectName")

        ctx.variables.set(store, found.toString())
        ctx.logger("Task \"$taskName\" exists=$found")
        return ActionResult.Success
    }
}

/**
 * `Sort Group Tasks` — put one group's tasks back in alphabetical order. Needed because tasks generated
 * one-per-app land wherever they were created: 保存復元's 保存タスク group grows a
 * `保存 ⇨ <pkg>` wrapper per sister app, so without this the newest app sits at the bottom instead of
 * next to its neighbours.
 */
class SortGroupTasksAction : Action {
    override val id = "tasks.sort"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val projectName = args["project"].orEmpty().trim()
        if (projectName.isEmpty()) return ActionResult.Failure("project name is required")
        val groupName = args["group"].orEmpty().trim()
        if (groupName.isEmpty()) return ActionResult.Failure("group name is required")

        val db = OpenTaskerApp_NoHilt.db
        val sorted = withContext(Dispatchers.IO) {
            val project = db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }
                ?: return@withContext -1
            val group = db.itemGroupDao().getForTab("tasks")
                .firstOrNull { it.projectId == project.id && it.name == groupName }
                ?: return@withContext -2
            sortGroupTasksAlphabetically(project.id, group.id)
        }
        return when (sorted) {
            -1 -> ActionResult.Failure("no such project: $projectName")
            -2 -> ActionResult.Failure("no such task group: $groupName")
            else -> {
                ctx.logger("Sorted $sorted tasks in $groupName")
                ActionResult.Success
            }
        }
    }
}

/**
 * Renumber one group's tasks into alphabetical order, positioned BELOW every non-group task of the
 * project — the layout `tasks.launchers` has always produced for its generated group, lifted out here so
 * [SortGroupTasksAction] can apply the same rule to a hand-built group. The grouped list orders items by
 * `TaskEntity.position`; `ItemMetaEntity.position` is kept in step. Returns how many tasks were ordered.
 */
internal suspend fun sortGroupTasksAlphabetically(projectId: Long, groupId: Long): Int {
    val db = OpenTaskerApp_NoHilt.db
    val idsInGroup = db.itemMetaDao().getForTab("tasks")
        .filter { it.groupId == groupId }
        .mapNotNull { it.itemKey.toLongOrNull() }
        .toSet()
    val all = db.taskDao().getAll()
    val maxOther = all.filter { it.projectId == projectId && it.id !in idsInGroup }
        .maxOfOrNull { it.position } ?: -1
    val sorted = all.filter { it.id in idsInGroup }.sortedBy { it.name.lowercase() }
    sorted.forEachIndexed { index, task ->
        val newPos = maxOther + 1 + index
        if (task.position != newPos) db.taskDao().setPosition(task.id, newPos)
        val meta = db.itemMetaDao().get("tasks", task.id.toString())
        if (meta != null && meta.position != index) db.itemMetaDao().upsert(meta.copy(position = index))
    }
    return sorted.size
}
