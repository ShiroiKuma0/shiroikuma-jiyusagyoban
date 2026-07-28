package com.opentasker.core.progress

import com.opentasker.core.actions.backupVarSuffix
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** How a row is drawn: what already happened, what is happening, what is still to come. */
enum class ProgressRowState { PENDING, ACTIVE, DONE, FAIL, SKIP, CANCEL }

/**
 * One line of a progress pane. [key] identifies it (a package name in the outer pane, a category id in
 * the inner one) and is what the fold-out and the retry are keyed on; [detail] is the right-hand
 * annotation (a size, an error); [note] is an extra fold-out line (the path a backup was written to);
 * [children] is the item list captured when the row finished.
 */
data class ProgressRow(
    val key: String,
    val label: String,
    val state: ProgressRowState = ProgressRowState.PENDING,
    val detail: String = "",
    val note: String = "",
    // The last failure this row saw, kept even after a repair puts it back to running — the repair
    // buttons are chosen from it, so they stay correct while a retry is in flight.
    val lastError: String = "",
    // ── selection (prune) mode ──
    val marked: Boolean = false,     // ticked for the action at the bottom of the panel
    val bytes: Long = 0,             // this row's size on disk; parents sum their children
    val depth: Int = 0,              // sub-options sit one level in from their parent
    val children: List<ProgressRow> = emptyList(),
) {
    val failed: Boolean get() = state == ProgressRowState.FAIL || state == ProgressRowState.CANCEL

    /** A row worth opening: it has captured items, a path, or an error that is too long for one line. */
    val expandable: Boolean get() = children.isNotEmpty() || note.isNotBlank() || failed || lastError.isNotBlank()

    /** What the repair buttons are chosen from: the live error, or the one this row last failed with. */
    val errorForRepair: String get() = if (failed) detail else lastError
}

/**
 * The whole panel: an outer pane (the run's steps — e.g. one app per row), an inner pane (the current
 * step's items), counters for both, and an action button.
 *
 * [outerLines] / [innerLines] are how many rows each pane shows at once; the active row is kept
 * `(lines - 1) / 2` rows down, so a 10-line pane holds 4 finished rows above it and 5 pending ones
 * below. [innerNote] is the live counter the running app reports (「書籍 1234/8942」), drawn indented
 * under the active item.
 *
 * When [finished] the panel becomes the run's REPORT rather than its progress: the item pane goes
 * away, the button becomes OK, and each failed row can be opened to read the whole error and act on
 * it — grant the app storage access, or run [retryTask] for that row alone.
 */
data class ProgressPanelState(
    val title: String = "",
    val outer: List<ProgressRow> = emptyList(),
    val outerIndex: Int = -1,               // 0-based; -1 = nothing active yet
    val outerUnit: String = "",             // counter noun for the outer pane (「アプリ」)
    val inner: List<ProgressRow> = emptyList(),
    val innerIndex: Int = -1,
    val innerUnit: String = "",             // counter noun for the inner pane (「項目」)
    val innerNote: String = "",
    // The second counter, drawn after [innerNote]: an app exporting a corpus reports both how many
    // pieces it has written and how many bytes, and one without the other is half the picture.
    val innerBytes: Long = 0,
    val innerBytesTotal: Long = 0,
    val outerLines: Int = 10,
    val innerLines: Int = 8,
    // A run whose steps have no items of their own — the sweep that only asks each app for its list.
    // Drawing the item pane there leaves an empty box under the list, so it is dropped entirely and
    // the step list takes the whole window.
    val singlePane: Boolean = false,
    val icons: Boolean = false,             // outer keys are packages → draw each app's icon
    val cancelLabel: String = "",           // blank = no 中止 button
    val cancelVar: String = "",             // variable set to "1" when 中止 is pressed
    val projectId: Long = SUPER_GLOBAL_PROJECT_ID,
    val cancelled: Boolean = false,
    val expanded: Set<String> = emptySet(), // outer keys whose captured items are unfolded
    // ── report mode ──
    val finished: Boolean = false,
    val summary: String = "",               // e.g. 「合計 122 MB」, appended to the live ✓/✗ counts
    val retryTask: String = "",             // task name template; "{key}" → the row's key
    val rowVar: String = "",                // variable set to the row NUMBER before a retry
    val cleanupDir: String = "",            // where a repair sweeps that app's half-written backups
    val okLabel: String = "OK",
    // ── selection (prune) mode: the same window, used to CHOOSE rather than to report ──
    val selecting: Boolean = false,
    val rowsSelectable: Boolean = false,    // the OUTER rows are ticked too (the backup plan), not
                                            // just their children (the prune list)
    val confirmTask: String = "",           // task the confirm button runs (blank = act natively)
    val confirmLabel: String = "",          // the action button, e.g. 「削除」 / 「保存開始」
    val itemsMode: Boolean = false,         // confirm SAVES each app's ticked items as its default
                                            // selection instead of starting a run
    val settingsTask: String = "",          // itemsMode: the 01 task the selections are persisted into
    // ── the destination pill: where this run writes, changeable for THIS run only ──
    val dirPath: String = "",               // shown in the pill; blank = no pill
    val dirVar: String = "",                // variable the per-run choice is written to (never %BR_Dir)
    val dirChanged: Boolean = false,        // the pill has been pointed somewhere else than the setting
    val browsePath: String = "",            // non-blank = the folder browser is open at this path
    val browseDirs: List<String> = emptyList(), // sub-folder names of browsePath, in display order
    val emptyNote: String = "",             // shown instead of the list when there is nothing to do
    val textScale: Float = 1f,              // 1.5 for the prune list — it is read, not glanced at
    val fillHeight: Boolean = false,        // take the whole window, splitting it between the panes
) {
    val failedRows: List<Int> get() = outer.indices.filter { outer[it].failed }
    val doneCount: Int get() = outer.count { it.state == ProgressRowState.DONE }

    /** Every marked child across every row — what the confirm button acts on. */
    val markedChildren: List<ProgressRow> get() = outer.flatMap { row -> row.children.filter { it.marked } }
    val markedCount: Int get() = markedChildren.size
    val markedBytes: Long get() = markedChildren.sumOf { it.bytes }

    /** Apps ticked for the run, and the items ticked under each of them. */
    val markedRows: List<ProgressRow> get() = outer.filter { it.marked }

    /** Everything on disk under this panel — what the selection is measured AGAINST. */
    val totalBytes: Long get() = outer.sumOf { row -> row.children.sumOf { it.bytes } }
    val totalChildren: Int get() = outer.sumOf { it.children.size }
}

/**
 * The live progress panel — a single overlay shared by whatever task is running, driven by the
 * `progress.*` actions and rendered by `com.opentasker.progress.ProgressPanelManager`.
 *
 * It is also the **cancel bus**: 中止 flips [ProgressPanelState.cancelled], writes `1` into the task's
 * cancel variable, and fires every [onCancel] listener — which is how a pending `intent.send` reply
 * wait gives up at once instead of sitting out its (up to 600 s) timeout.
 */
object ProgressPanel {
    private val _state = MutableStateFlow<ProgressPanelState?>(null)
    val state: StateFlow<ProgressPanelState?> = _state.asStateFlow()

    private val cancelListeners = CopyOnWriteArrayList<() -> Unit>()

    /** True while 中止 has been pressed on the panel currently up. */
    fun isCancelled(): Boolean = _state.value?.cancelled == true

    fun show(initial: ProgressPanelState) {
        cancelListeners.clear()
        _state.value = initial.copy(cancelled = false, finished = false)
    }

    /** Mutate the panel in place; a no-op when no panel is up (so a stray update can't resurrect one). */
    fun update(block: (ProgressPanelState) -> ProgressPanelState) {
        val current = _state.value ?: return
        _state.value = block(current)
    }

    fun hide() {
        _state.value = null
        cancelListeners.clear()
    }

    /** Tap on a row — unfold / fold its captured items, path and error. */
    fun toggleExpanded(key: String) = update {
        it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key)
    }

    /**
     * Turn the panel into the run's report: drop the item pane, swap 中止 for OK, and arm the per-row
     * repair actions. The ✓/✗ counts in the header are computed from the rows themselves, so a row
     * repaired afterwards updates them.
     */
    fun finish(
        summary: String,
        retryTask: String,
        rowVar: String,
        okLabel: String,
        cleanupDir: String = "",
    ) = update {
        it.copy(
            finished = true,
            summary = summary,
            retryTask = retryTask,
            rowVar = rowVar,
            cleanupDir = cleanupDir,
            okLabel = okLabel,
            inner = emptyList(),
            innerIndex = -1,
            innerNote = "",
            outerIndex = -1,
            cancelled = false,
        )
    }

    /** Tick / untick one file in the prune list; its app row's "(9/10) · 1.2 GB" follows. */
    fun toggleMark(rowKey: String, childKey: String) = update { panel ->
        val index = panel.outer.indexOfFirst { it.key == rowKey }
        if (index < 0) return@update panel
        val row = panel.outer[index]
        val at = row.children.indexOfFirst { it.key == childKey }
        if (at < 0) return@update panel
        val target = !row.children[at].marked
        // A group carries its sub-options: toggling a parent item takes everything indented under it,
        // up to the next item at its own level.
        val span = generateSequence(at + 1) { it + 1 }
            .takeWhile { it < row.children.size && row.children[it].depth > row.children[at].depth }
            .toSet() + at
        val children = row.children.mapIndexed { i, child ->
            if (i in span) child.copy(marked = target) else child
        }
        val outer = panel.outer.toMutableList().also { it[index] = row.copy(children = children) }
        panel.copy(outer = outer)
    }

    /** Tick / untick one app for the run. */
    fun toggleRowMark(rowKey: String) = update { panel ->
        val index = panel.outer.indexOfFirst { it.key == rowKey }
        if (index < 0) return@update panel
        val row = panel.outer[index]
        val outer = panel.outer.toMutableList().also { it[index] = row.copy(marked = !row.marked) }
        panel.copy(outer = outer)
    }

    /** The master switch: every app, and (when rows carry their own mark) nothing else. */
    fun toggleAllRows() = update { panel ->
        val select = !panel.outer.all { it.marked }
        panel.copy(outer = panel.outer.map { it.copy(marked = select) })
    }

    /** Tick / untick every file of one app at once (tapping its summary). */
    fun toggleMarkAll(rowKey: String) = update { panel ->
        val index = panel.outer.indexOfFirst { it.key == rowKey }
        if (index < 0) return@update panel
        val row = panel.outer[index]
        val allMarked = row.children.isNotEmpty() && row.children.all { it.marked }
        val children = row.children.map { it.copy(marked = !allMarked) }
        val outer = panel.outer.toMutableList().also { it[index] = row.copy(children = children) }
        panel.copy(outer = outer)
    }

    /** 中止 pressed: mark the panel, publish the cancel variable, wake every waiter. */
    fun requestCancel() {
        val current = _state.value ?: return
        if (current.cancelled || current.finished) return
        _state.value = current.copy(cancelled = true)
        writeVar(current, current.cancelVar, "1")
        cancelListeners.forEach { runCatching { it() } }
    }

    /**
     * Write the plan's choice where the run can read it: `%BR_RunApps` = the ticked packages, and
     * `%BR_Run_<Suffix>` = that app's ticked item ids. The panel then turns into the run's progress
     * view, so the same window carries the job from choosing to reporting.
     */
    fun publishRunSelection() {
        val panel = _state.value ?: return
        val apps = panel.outer.filter { it.marked }
        writeVar(panel, "BR_RunApps", apps.joinToString(" ") { it.key })
        apps.forEach { app ->
            writeVar(
                panel,
                "BR_Run_${backupVarSuffix(app.key)}",
                app.children.filter { it.marked }.joinToString(",") { it.key },
            )
        }
        // From plan to progress: same window, no flicker, the rows already in place.
        _state.value = panel.copy(
            selecting = false,
            rowsSelectable = false,
            textScale = 1f,
            outer = apps.map { it.copy(marked = false, children = emptyList()) },
        )
    }

    /**
     * The item editor's counterpart: write each ticked app's ticked items into its SAVED selection,
     * `%BR_Items_<Suffix>` — the defaults every later run starts from, not a per-run override.
     *
     * Only ticked apps are touched, so unticking one leaves its saved selection exactly as it was.
     * Returns the (variable, value) pairs written, which the caller persists into the settings task
     * so they outlive the process.
     */
    fun publishItemSelection(): List<Pair<String, String>> {
        val panel = _state.value ?: return emptyList()
        val pairs = panel.outer.filter { it.marked }.map { app ->
            "BR_Items_${backupVarSuffix(app.key)}" to
                app.children.filter { it.marked }.joinToString(",") { it.key }
        }
        pairs.forEach { (name, value) -> writeVar(panel, name, value) }
        return pairs
    }

    /**
     * The destination pill: open the folder browser, walk it, and choose. The chosen path is written to
     * [ProgressPanelState.dirVar] — a per-run variable — so the configured export directory in the
     * settings task is never touched: change it here and it holds for this run only.
     */
    fun openBrowser(entries: List<String>) = update { panel ->
        panel.copy(browsePath = panel.dirPath.ifBlank { "/storage/emulated/0" }, browseDirs = entries)
    }

    fun browseTo(path: String, entries: List<String>) = update { panel ->
        panel.copy(browsePath = path, browseDirs = entries)
    }

    fun closeBrowser() = update { panel -> panel.copy(browsePath = "", browseDirs = emptyList()) }

    /** Take the browsed folder as this run's destination and close the browser. */
    fun chooseBrowsedDir() {
        val panel = _state.value ?: return
        val chosen = panel.browsePath.ifBlank { return }
        writeVar(panel, panel.dirVar, chosen)
        _state.value = panel.copy(
            dirPath = chosen,
            dirChanged = true,
            browsePath = "",
            browseDirs = emptyList(),
        )
    }

    /** Publish the 1-based row number a repair is about to re-run, so the task updates the right row. */
    fun publishRowNumber(index: Int) {
        val current = _state.value ?: return
        writeVar(current, current.rowVar, (index + 1).toString())
    }

    /** Put a row back to "running" the moment its repair starts, so the tap has an instant answer. */
    fun markRetrying(index: Int, prefix: String = "") = update { panel ->
        val row = panel.outer.getOrNull(index) ?: return@update panel
        val outer = panel.outer.toMutableList().also {
            it[index] = row.copy(
                state = ProgressRowState.ACTIVE,
                detail = if (prefix.isEmpty()) "保存し直しています…" else "$prefix — 保存し直しています…",
            )
        }
        panel.copy(outer = outer, outerIndex = index)
    }

    /** Record what a repair did on a row WITHOUT touching its state — it stays failed, and keeps its
     *  buttons, because the repair handed the user a setting to change rather than re-running. */
    fun annotate(index: Int, note: String) = update { panel ->
        val row = panel.outer.getOrNull(index) ?: return@update panel
        if (note.isBlank()) return@update panel
        val outer = panel.outer.toMutableList().also { it[index] = row.copy(note = note) }
        panel.copy(outer = outer, expanded = panel.expanded + row.key)
    }

    /**
     * Run [callback] when 中止 is pressed — immediately if it already was. Returns the un-hook, which
     * the caller MUST invoke when it stops caring (the listener list outlives a single action).
     */
    fun onCancel(callback: () -> Unit): () -> Unit {
        if (isCancelled()) {
            callback()
            return {}
        }
        cancelListeners.add(callback)
        return { cancelListeners.remove(callback) }
    }

    // Same scope rule as VariableStore: ALL-CAPS is a super-global, MixedCase belongs to the project
    // of the task that raised the panel (%BR_Cancel / %BR_N → 保存復元).
    private fun writeVar(panel: ProgressPanelState, rawName: String, value: String) {
        val name = rawName.trim().removePrefix("%")
        if (name.isEmpty()) return
        val allCaps = name.none { it.isLetter() && it.isLowerCase() }
        PersistentGlobalScope.set(if (allCaps) SUPER_GLOBAL_PROJECT_ID else panel.projectId, name, value)
    }
}
