package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.bubbles.FreezeBubbleStore
import com.opentasker.core.bubbles.FreezeBubbleTarget
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.dialog.DialogOutcome
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A launcher task paired with the app it launches — one tile in the picker. */
private data class BubbleCandidate(val entity: TaskEntity, val pkg: String)

/** What the scan found: either the tiles to show, or which scope argument named nothing. */
private sealed interface BubbleScan {
    data class Ready(val candidates: List<BubbleCandidate>) : BubbleScan
    data class Missing(val message: String) : BubbleScan
}

/**
 * Tick which apps pop a re-freeze bubble when you come back to the Desktop.
 *
 * The companion to `tasks.launchers`: that one picks apps and *creates* an unfreeze-then-launch task
 * for each, freeze-bubble already on. This one shows the same grid over the tasks that already exist
 * — every app whose bubble is on **pre-ticked and listed first**, every other launcher task in scope
 * unticked below it — and writes the ticks straight back to the tasks' `freezeBubble` flag. So one
 * pass turns bubbles off for apps that no longer want them and on for apps that newly do, without
 * opening a single task card.
 *
 * Scope: `project` and/or `group` narrow the list the way they name it (both = that group, project
 * alone = every task in the project, neither = every task there is). **A task that is currently
 * showing a bubble is always listed regardless of scope** — the purpose of this picker is to be able
 * to switch one off, and a scope that hid it would leave it ticked and unreachable.
 *
 * Cancel changes nothing. There is deliberately no "apply to everything" shortcut: the grid's own OK
 * count is the confirmation, and the write is a plain diff, so a task whose flag already matches is
 * not rewritten.
 */
class PickFreezeBubblesAction : Action {
    override val id = "tasks.freezebubbles"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val projectName = ctx.variables.expand(args["project"].orEmpty()).trim()
        val groupName = ctx.variables.expand(args["group"].orEmpty()).trim()
        val title = ctx.variables.expand(args["title"].orEmpty()).trim().ifEmpty { "Freeze bubbles" }
        val db = OpenTaskerApp_NoHilt.db

        val candidates = when (val scan = withContext(Dispatchers.IO) { scan(db, ctx, projectName, groupName) }) {
            is BubbleScan.Missing -> return ActionResult.Failure(scan.message)
            is BubbleScan.Ready -> scan.candidates
        }
        if (candidates.isEmpty()) {
            return ActionResult.Failure("no launcher tasks found — nothing to tick")
        }

        val packages = candidates.map { it.pkg }.distinct()
        val alreadyOn = candidates.filter { it.entity.freezeBubble }.map { it.pkg }.distinct()

        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_APP_MULTISELECT)
            putExtra(DialogActivity.EXTRA_TITLE, title)
            // The grid is exactly our launcher tasks — not the installed-apps list the generator shows.
            putExtra(DialogActivity.EXTRA_PACKAGES, packages.joinToString("\n"))
            putExtra(DialogActivity.EXTRA_PRESELECTED, alreadyOn.joinToString("\n"))
            // An app uninstalled since its task was made still gets a tile, or its bubble could never
            // be switched off again.
            putExtra(DialogActivity.EXTRA_SHOW_MISSING, true)
        }
        val picked: Set<String> = when (outcome) {
            is DialogOutcome.Confirmed -> outcome.value
                .split("\n")
                .mapNotNull { line -> line.split("\t", limit = 2).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } }
                .toSet()
            DialogOutcome.Cancelled -> {
                ctx.logger("Freeze bubbles unchanged (cancelled)")
                return ActionResult.Success
            }
        }

        val (turnedOn, turnedOff) = withContext(Dispatchers.IO) {
            var on = 0
            var off = 0
            for (candidate in candidates) {
                val wanted = candidate.pkg in picked
                if (wanted == candidate.entity.freezeBubble) continue
                db.taskDao().update(candidate.entity.copy(freezeBubble = wanted))
                if (wanted) on++ else off++
            }
            on to off
        }

        // Unticking has to take the bubble that is ALREADY queued with it. The flag only governs the
        // next run, so a bubble enqueued earlier would keep drawing on the Desktop until it was
        // tapped — which reads as the switch not having worked.
        candidates.asSequence()
            .map { it.pkg }
            .filterNot { it in picked }
            .distinct()
            .forEach { FreezeBubbleStore.remove(it) }

        ctx.logger("Freeze bubbles: ${picked.size} of ${packages.size} apps on (+$turnedOn / -$turnedOff)")
        return ActionResult.Success
    }

    /** Resolve the scope, then collect every task in it that names an app. */
    private suspend fun scan(
        db: AppDatabase,
        ctx: ActionContext,
        projectName: String,
        groupName: String,
    ): BubbleScan {
        val projectId: Long? = if (projectName.isEmpty()) {
            null
        } else {
            db.projectDao().getAll().firstOrNull { it.name == projectName }?.id
                ?: return BubbleScan.Missing("project not found: $projectName")
        }
        val groupTaskIds: Set<Long>? = if (groupName.isEmpty()) {
            null
        } else {
            val groupIds = db.itemGroupDao().getForTab("tasks")
                .filter { it.name == groupName && (projectId == null || it.projectId == projectId) }
                .map { it.id }
                .toSet()
            if (groupIds.isEmpty()) return BubbleScan.Missing("group not found: $groupName")
            db.itemMetaDao().getForTab("tasks")
                .filter { it.groupId in groupIds }
                .mapNotNull { it.itemKey.toLongOrNull() }
                .toSet()
        }

        val candidates = db.taskDao().getAll().mapNotNull { entity ->
            val inScope = when {
                groupTaskIds != null -> entity.id in groupTaskIds
                projectId != null -> entity.projectId == projectId
                else -> true
            }
            if (!inScope && !entity.freezeBubble) return@mapNotNull null
            // A task whose actions no longer decode keeps its flag rather than being silently retyped:
            // the decode fallback yields no actions, so it names no package and drops out here.
            val actions = entity.toDomainDecodeResult().value.actions
            val pkg = FreezeBubbleTarget.packageOf(actions) { ctx.variables.expand(it) }
                ?: return@mapNotNull null
            BubbleCandidate(entity, pkg)
        }
        return BubbleScan.Ready(candidates)
    }
}
