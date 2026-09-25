package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.bubbles.FreezeBubbleTarget
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.dialog.DialogOutcome
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.policy.AppFreeze
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
 *
 * ## An app is its PACKAGE, and a label is not an identity
 *
 * Both halves of this action used to key off the app's LABEL, and 白い熊's phone is where that broke
 * (2026-09-20): `com.rovio.angrybirdsgo`, `com.rovio.angrybirdstransformers`,
 * `com.rovio.angrybirdsstarwarsii.ads` and `com.rovio.angrybirdsstarwarshd.premium.iap` **all four
 * report the label `Angry Birds`**. One of them already had a task, so every other one generated the
 * name `Angry Birds -- [1707][7107]`, matched the "already there" check, and was silently skipped —
 * the action reported success, created nothing, and said so only in a run-log line nobody was looking
 * at. Worse was waiting behind it: tasks carry a UNIQUE (projectId, name) index, so had the check not
 * swallowed it the insert would have thrown.
 *
 * So the duplicate test is now **by package**, which is the only identity an app actually has, and a
 * name that is already taken anywhere in the project is qualified with the package —
 * `Angry Birds (com.rovio.angrybirdsgo) -- [1707][7107]` — rather than abandoned. A generator that
 * cannot name a thing must still make it.
 *
 * ## The grid says which apps already have one
 *
 * Every package the project already launches arrives **pre-ticked and listed first**, exactly as
 * 泡を選ぶ does it (白い熊, 2026-09-20: *"it should already show with a tick in the top right corner …
 * which app already has a task"*). The tick is a READING, not a switch: leaving it on creates nothing
 * twice, and taking it off deletes nothing. Generating tasks and destroying them are not the same
 * gesture, and this action only ever adds.
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

        // 2. What the project already launches, BY PACKAGE — the pre-ticks for the grid, and the
        // "do not make this twice" test. Project-wide rather than group-only on purpose: an app whose
        // task 白い熊 filed somewhere else still HAS a task, and the honest answer to "which of these
        // already has one" does not depend on where it was filed.
        val launchedAlready = withContext(Dispatchers.IO) {
            db.taskDao().getAll()
                .filter { it.projectId == pid }
                .mapNotNull { entity ->
                    // A task whose actions no longer decode names no package and drops out, rather
                    // than being retyped by this action — the decode fallback yields no actions.
                    val actions = entity.toDomainDecodeResult().value.actions
                    FreezeBubbleTarget.packageOf(actions) { ctx.variables.expand(it) }
                }
                .toSet()
        }

        // Ticked only where there is a tile to tick. This grid is the INSTALLED-apps list, and a
        // preselected package it cannot resolve is given a stand-in tile labelled by its bare id —
        // at the TOP, by that grid's own deliberate rule. 凍結融解's project is a workspace that
        // outlived a phone: 36 of its 56 launcher tasks name apps that were never installed here, so
        // preselecting the lot would open the generator on a screenful of package ids and bury every
        // app 白い熊 could actually make a task for. That is the exact failure the roster grid was
        // fixed for on 2026-09-13, and it is not worth re-creating in the other grid to mark apps
        // that are not there. MATCH_FROZEN, because 凍結融解's apps are hidden as their resting state
        // and a hidden package reads as not installed under plain flags.
        val ticked = withContext(Dispatchers.IO) {
            val pm = ctx.app.packageManager
            launchedAlready.filter { pkg ->
                runCatching { pm.getApplicationInfo(pkg, AppFreeze.MATCH_FROZEN) }.isSuccess
            }
        }

        // 3. Show the multi-select app picker, every already-covered app ticked and led with.
        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_APP_MULTISELECT)
            putExtra(DialogActivity.EXTRA_TITLE, "Select apps")
            putExtra(DialogActivity.EXTRA_PRESELECTED, ticked.joinToString("\n"))
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

        val (created, skipped) = withContext(Dispatchers.IO) {
            // Every name already spoken for in this PROJECT. The uniqueness Room enforces is
            // (projectId, name), so a clash two groups away is still a clash, and an insert that hits
            // it throws rather than returning -1 — this set is what keeps the generator off it.
            val takenNames = db.taskDao().getAll()
                .filter { it.projectId == pid }
                .map { it.name }
                .toMutableSet()
            val covered = launchedAlready.toMutableSet()

            var count = 0
            var already = 0
            for ((pkg, label) in picked) {
                // BY PACKAGE. The pre-ticked apps come back in `picked` — that is what a tick means —
                // so this is also the line that makes confirming the grid unchanged a no-op.
                if (pkg in covered) { already++; continue }
                val taskName = uniqueTaskName(label, suffix, pkg, takenNames)
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
                    // NOT defaulted on any more (2026-09-13). Which apps pop a bubble is
                    // %Toketsu_Bubbles, and a generator quietly adding itself to a roster 白い熊
                    // curates in 泡を選ぶ is the generator deciding a question that is not its own.
                    freezeBubble = false,
                )
                val newId = db.taskDao().insert(entity)
                db.itemMetaDao().upsert(
                    ItemMetaEntity(tab = "tasks", itemKey = newId.toString(), groupId = groupId),
                )
                takenNames += taskName
                covered += pkg
                count++
            }

            // 4. Keep the group at the BOTTOM and its tasks in alphabetical order, re-sorted on EVERY run
            // (shared with the `tasks.sort` action).
            sortGroupTasksAlphabetically(pid, groupId)
            count to already
        }

        // Both numbers, always. "Created 0" on its own is what a run looks like when every pick was
        // already covered AND what it looked like when the label check was eating them, and those two
        // have to be tellable apart from the run log alone.
        ctx.logger("Created $created launcher tasks in $groupName ($skipped already had one)")
        return ActionResult.Success
    }
}

/**
 * `<label><suffix>`, qualified with the package if something in the project already answers to it.
 *
 * Four of 白い熊's games are called `Angry Birds`. A generator that gives up on the second one — which
 * is what a bare duplicate check did — leaves an app the picker says it made a task for with no task,
 * and one that ploughs on hits the UNIQUE (projectId, name) index and throws. The package is the
 * disambiguator because it is the one thing that is certainly different, and it is in parentheses
 * BEFORE the suffix so the ` -- [1707][7107]` tail every name in this group ends with stays the tail.
 */
internal fun uniqueTaskName(
    label: String,
    suffix: String,
    pkg: String,
    taken: Set<String>,
): String {
    val plain = "$label$suffix"
    if (plain !in taken) return plain
    val qualified = "$label ($pkg)$suffix"
    if (qualified !in taken) return qualified
    // Only reachable if the qualified name is taken too, which means a task already names this exact
    // package — but a name is never returned unchecked: the insert behind it cannot be allowed to throw.
    var n = 2
    while ("$label ($pkg $n)$suffix" in taken) n++
    return "$label ($pkg $n)$suffix"
}
