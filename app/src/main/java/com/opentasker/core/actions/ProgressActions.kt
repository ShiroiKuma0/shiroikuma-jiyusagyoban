package com.opentasker.core.actions

import android.content.pm.PackageManager
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.progress.ProgressPanel
import com.opentasker.core.progress.ProgressPanelState
import com.opentasker.core.progress.ProgressRow
import com.opentasker.core.progress.ProgressRowState
import com.opentasker.progress.ProgressPanelManager

// ---------------------------------------------------------------------------------------------
// The progress panel — a two-pane, self-scrolling "what happened / what is happening / what is
// left" overlay for long batch tasks (the 保存復元 sister-app backup run is its first caller).
//
// The task never does list arithmetic: it declares the steps once with progress.show, then says
// which step and which item is current. The panel handles the window (the active row sits
// (lines-1)/2 down, so finished work stays visible above it), the styling, the fold-out of a
// finished step's items, and the 中止 button.
// ---------------------------------------------------------------------------------------------

/** Split an argument list on [separator], trimming and dropping empties (a run of spaces = one gap). */
private fun splitList(raw: String?, separator: String): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
}

private fun parseState(raw: String?): ProgressRowState = when (raw?.trim()?.lowercase()) {
    "done", "ok", "true" -> ProgressRowState.DONE
    "fail", "error", "false" -> ProgressRowState.FAIL
    "skip", "skipped" -> ProgressRowState.SKIP
    "cancel", "cancelled", "canceled" -> ProgressRowState.CANCEL
    "pending", "todo" -> ProgressRowState.PENDING
    else -> ProgressRowState.ACTIVE
}

/** Display label for a package, frozen ones included; falls back to the package name itself. */
internal fun appLabel(pm: PackageManager, pkg: String): String = runCatching {
    pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).loadLabel(pm).toString()
}.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg

/**
 * `Progress Panel — Show` — raise the panel and declare the outer list (the run's steps).
 *
 * Args:
 *   - "title": panel heading (e.g. 「保存」)
 *   - "rows": the steps, joined by "separator" (required)
 *   - "labels": optional display labels parallel to "rows"
 *   - "separator": how "rows"/"labels" are split (default ",")
 *   - "packages": "true" = the row keys are package names — resolve each app's label (when "labels"
 *     is absent) and draw its icon
 *   - "unit" / "item_unit": counter nouns for the two panes (「アプリ」/「項目」)
 *   - "lines" / "item_lines": visible rows per pane (default 10 / 8)
 *   - "cancel_var": variable set to "1" when 中止 is pressed — blank = no button
 *   - "cancel_label": button text (default 「中止」)
 */
class ShowProgressPanelAction : Action {
    override val id = "progress.show"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val separator = args["separator"]?.takeUnless { it.isEmpty() } ?: ","
        val keys = splitList(args["rows"], separator)
        if (keys.isEmpty()) return ActionResult.Failure("no rows to show")
        val labels = splitList(args["labels"], separator)
        val asPackages = args["packages"]?.trim()?.lowercase() in setOf("true", "1", "yes", "on")
        val pm = ctx.app.packageManager
        val rows = keys.mapIndexed { index, key ->
            val label = labels.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: if (asPackages) appLabel(pm, key) else key
            ProgressRow(key = key, label = label)
        }
        val cancelVar = args["cancel_var"]?.trim()?.removePrefix("%").orEmpty()
        // Clear a stale cancel flag from a previous run, or this one would abort on its first wait.
        if (cancelVar.isNotEmpty()) ctx.variables.set(cancelVar, "")
        ProgressPanel.show(
            ProgressPanelState(
                title = args["title"].orEmpty(),
                outer = rows,
                outerIndex = -1,
                outerUnit = args["unit"].orEmpty(),
                innerUnit = args["item_unit"].orEmpty(),
                outerLines = args["lines"]?.trim()?.toIntOrNull()?.coerceIn(3, 30) ?: 10,
                innerLines = args["item_lines"]?.trim()?.toIntOrNull()?.coerceIn(3, 30) ?: 8,
                icons = asPackages,
                cancelLabel = if (cancelVar.isEmpty()) "" else args["cancel_label"]?.trim()?.ifBlank { null } ?: "中止",
                cancelVar = cancelVar,
                textScale = args["scale"]?.trim()?.toFloatOrNull()?.coerceIn(0.8f, 2.5f) ?: 1f,
                // Same window as the plan it followed: starting the run must not shrink it.
                fillHeight = args["fill"]?.trim()?.lowercase() in setOf("true", "1", "yes", "on"),
                // No item pane at all — for a run whose steps have nothing to list under them.
                singlePane = args["single"]?.trim()?.lowercase() in setOf("true", "1", "yes", "on"),
                projectId = ctx.variables.projectId,
            ),
        )
        if (!ProgressPanelManager.canOverlay(ctx.app)) {
            ProgressPanel.hide()
            return ActionResult.Failure("the progress panel needs \"Display over other apps\"")
        }
        ProgressPanelManager.show(ctx.app)
        ctx.logger("Progress panel: ${rows.size} rows")
        return ActionResult.Success
    }
}

/**
 * `Progress Panel — Step` — set the state of one outer row (1-based "index"). Activating a row also
 * replaces the inner pane with that step's items ("items"/"item_labels"); finishing one (done / fail /
 * cancel) captures the inner pane into the row, which is what tapping it later unfolds.
 *
 * Args: "index", "state" (active|done|fail|skip|cancel|pending), "detail", "items", "item_labels",
 * "parents" (positional parent id per item — a non-empty one marks a sub-option, which is dropped),
 * "only" (keep just these item keys; empty = all), "separator", "label".
 */
class ProgressPanelStepAction : Action {
    override val id = "progress.row"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val index = (args["index"]?.trim()?.toIntOrNull() ?: return ActionResult.Failure("missing index")) - 1
        if (index < 0) return ActionResult.Failure("index is 1-based")
        val state = parseState(args["state"])
        val separator = args["separator"]?.takeUnless { it.isEmpty() } ?: ","
        // The item list is filtered here rather than in the calling task, which has no set arithmetic:
        // "parents" drops sub-options (a row per top-level item only, which is what an app counts as it
        // exports), and "only" keeps just what was selected. Empty "only" = everything.
        val rawKeys = splitList(args["items"], separator)
        // Labels and parents are positional (an empty entry is meaningful), so they are NOT compacted.
        val rawLabels = args["item_labels"]?.split(separator)?.map { it.trim() } ?: emptyList()
        val rawParents = args["parents"]?.split(separator)?.map { it.trim() } ?: emptyList()
        val only = splitList(args["only"], separator).toSet()
        val keep = rawKeys.indices.filter { i ->
            rawParents.getOrNull(i).isNullOrEmpty() && (only.isEmpty() || rawKeys[i] in only)
        }
        val itemKeys = keep.map { rawKeys[it] }
        val itemLabels = keep.map { rawLabels.getOrNull(it).orEmpty() }
        var applied = false
        ProgressPanel.update { panel ->
            val row = panel.outer.getOrNull(index) ?: return@update panel
            applied = true
            val finished = state in setOf(ProgressRowState.DONE, ProgressRowState.FAIL, ProgressRowState.CANCEL, ProgressRowState.SKIP)
            // Settle the item pane with the step: a step that succeeded exported everything still
            // showing as pending, and one that died did so on whichever item was running. Saves the
            // caller from ticking off the tail by hand, and makes the captured fold-out truthful.
            val settledInner = when (state) {
                ProgressRowState.DONE -> panel.inner.map {
                    if (it.state == ProgressRowState.PENDING || it.state == ProgressRowState.ACTIVE) {
                        it.copy(state = ProgressRowState.DONE)
                    } else {
                        it
                    }
                }
                ProgressRowState.FAIL, ProgressRowState.CANCEL -> panel.inner.map {
                    if (it.state == ProgressRowState.ACTIVE) it.copy(state = state) else it
                }
                else -> panel.inner
            }
            val updated = row.copy(
                label = args["label"]?.trim()?.takeIf { it.isNotBlank() } ?: row.label,
                state = state,
                detail = args["detail"]?.trim() ?: row.detail,
                // An extra fold-out line — where a backup was written, kept off the row itself so the
                // list stays readable but nothing the old summary dialog showed is lost.
                note = args["note"]?.trim() ?: row.note,
                // Remember why it failed, so a repair in flight keeps offering the right buttons.
                lastError = if (state == ProgressRowState.FAIL || state == ProgressRowState.CANCEL) {
                    args["detail"]?.trim() ?: row.detail
                } else {
                    row.lastError
                },
                // Freeze what this step actually did, so the fold-out shows it after the panes move on.
                children = if (finished && settledInner.isNotEmpty()) settledInner else row.children,
            )
            val outer = panel.outer.toMutableList().also { it[index] = updated }
            val activating = state == ProgressRowState.ACTIVE
            panel.copy(
                outer = outer,
                outerIndex = if (activating) index else panel.outerIndex,
                // A new step starts with a fresh item pane; an explicit list replaces it, and no list
                // clears it (so a step's leftovers never show under the next one).
                inner = when {
                    activating && itemKeys.isNotEmpty() -> itemKeys.mapIndexed { i, key ->
                        ProgressRow(key = key, label = itemLabels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: key)
                    }
                    activating -> emptyList()
                    else -> settledInner
                },
                innerIndex = if (activating) -1 else panel.innerIndex,
                innerNote = if (activating) "" else panel.innerNote,
            )
        }
        // Addressing a panel that isn't up (or a row it doesn't have) is a NO-OP, never a failure: the
        // panel is decoration, and the work a task is really doing must not be reported as failed just
        // because nothing is on screen. Running one 「保存 ⇨ <pkg>」 wrapper by hand did exactly that —
        // the backup was written correctly and the run still logged ✗ (白い熊, 2026-07-27).
        //
        // No panel at all is the ordinary standalone case and stays SILENT (a task that also drives a
        // panel must read the same in the run log whether or not one is showing). A panel that IS up
        // without that row is a real mismatch between task and panel, so that one is logged.
        if (!applied && ProgressPanel.state.value != null) {
            ctx.logger("Progress panel: no row ${index + 1} — skipped")
        }
        return ActionResult.Success
    }
}

/**
 * `Progress Panel — Item` — set the state of one inner row (1-based "index"), or, with "note" alone,
 * just refresh the live counter line under the active item (「書籍 1234/8942」 straight from the
 * app's own progress broadcast).
 *
 * Args: "index", "state", "detail", "label", "note".
 */
class ProgressPanelItemAction : Action {
    override val id = "progress.item"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawIndex = args["index"]?.trim()?.toIntOrNull()
        val note = args["note"]
        ProgressPanel.update { panel ->
            var next = panel
            if (rawIndex != null && rawIndex >= 1) {
                val index = rawIndex - 1
                val row = panel.inner.getOrNull(index)
                if (row != null) {
                    val state = parseState(args["state"])
                    val updated = row.copy(
                        label = args["label"]?.trim()?.takeIf { it.isNotBlank() } ?: row.label,
                        state = state,
                        detail = args["detail"]?.trim() ?: row.detail,
                    )
                    val inner = panel.inner.toMutableList().also { it[index] = updated }
                    next = next.copy(
                        inner = inner,
                        innerIndex = if (state == ProgressRowState.ACTIVE) index else panel.innerIndex,
                    )
                }
            }
            if (note != null) next = next.copy(innerNote = note.trim())
            next
        }
        return ActionResult.Success
    }
}

/**
 * `Progress Panel — Finish` — turn the running panel into the run's REPORT and leave it up: the item
 * pane goes away, the button becomes OK, and the whole list stays browsable, with every row still
 * unfolding to its items, its written path and — when it failed — the full error.
 *
 * A failed row also gets repair buttons: 「保存し直す」 re-runs "retry_task" for that row alone (its key
 * substituted for `{key}`, with its row number published into "row_var" first, so the task updates
 * that row in place), and, for a storage-permission failure, a button that opens the app's All-files
 * access page. So a run that half-failed is fixed from the report instead of by starting over.
 *
 * Args: "summary" (appended to the live ✓/✗ counts, e.g. 「合計 122 MB」), "retry_task", "row_var",
 * "cleanup_dir" (where a repair sweeps that app's half-written archives), "ok" (button label).
 */
class FinishProgressPanelAction : Action {
    override val id = "progress.finish"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (ProgressPanel.state.value == null) return ActionResult.Success  // nothing showing: no-op
        ProgressPanel.finish(
            summary = args["summary"]?.trim().orEmpty(),
            retryTask = args["retry_task"]?.trim().orEmpty(),
            rowVar = args["row_var"]?.trim()?.removePrefix("%").orEmpty(),
            okLabel = args["ok"]?.trim()?.ifBlank { null } ?: "OK",
            cleanupDir = args["cleanup_dir"]?.trim().orEmpty(),
        )
        ctx.logger("Progress panel → report")
        return ActionResult.Success
    }
}

/** `Progress Panel — Hide` — take the panel down (typically just before the summary dialog). */
class HideProgressPanelAction : Action {
    override val id = "progress.hide"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ProgressPanelManager.hide()
        ProgressPanel.hide()
        ctx.logger("Progress panel hidden")
        return ActionResult.Success
    }
}
