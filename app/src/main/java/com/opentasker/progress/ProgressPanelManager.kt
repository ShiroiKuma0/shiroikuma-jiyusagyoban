package com.opentasker.progress

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.actions.FlashOverlay
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.resolveTaskByName
import com.opentasker.core.progress.ProgressPanel
import com.opentasker.core.progress.ProgressPanelState
import com.opentasker.core.progress.ProgressRow
import com.opentasker.core.progress.ProgressRowState
import com.opentasker.core.shizuku.ShizukuShell
import com.opentasker.core.storage.toEntity
import com.opentasker.scenes.OverlayLifecycleOwner
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The progress panel's controller — two auto-following list panes (the run's steps on top, the current
 * step's items below), real counters, and a 中止 button, all driven by [ProgressPanel]'s state flow so
 * the running task only has to say "row 7 is active now".
 *
 * The window itself is [ProgressPanelActivity]: an ordinary Activity, so Home backgrounds it, recents
 * lists it, and switching back resumes it. It was a system overlay until 2026-07-28, which floated over
 * every app and answered to none of those gestures — during an hour-long backup there was no way to put
 * it aside. Nothing about a run depends on the window: the state flow outlives it.
 */
object ProgressPanelManager {
    private val main = Handler(Looper.getMainLooper())
    private var activity: ProgressPanelActivity? = null

    /** Kept for callers that still ask; the panel no longer needs the overlay permission. */
    fun canOverlay(context: Context): Boolean = true

    /** Bring the panel up, or to the front if it is already running. */
    fun show(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            // singleTask + NEW_TASK: an existing instance is resumed rather than duplicated, so this is
            // also how the panel is brought back after Home.
            runCatching {
                app.startActivity(
                    Intent(app, ProgressPanelActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /** Take the panel off screen. Safe to call when nothing is showing. */
    fun hide() {
        main.post { activity?.finish() }
    }

    internal fun attach(a: ProgressPanelActivity) { activity = a }

    internal fun detach(a: ProgressPanelActivity) { if (activity === a) activity = null }

    fun isShowing(): Boolean = activity != null

    // ── Repairs from the finished panel ──────────────────────────────────────────────────────────
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    /**
     * Re-run one row's task — 「保存し直す」 on a failed app. The row number is published first (into the
     * same variable the batch drives, so the task updates THAT row) and the row is put back to running
     * at once, so the tap answers immediately instead of after the app replies.
     */
    fun retryRow(index: Int, stopFirst: Boolean = false) {
        val panel = ProgressPanel.state.value ?: return
        val row = panel.outer.getOrNull(index) ?: return
        val ref = panel.retryTask.replace(ROW_KEY_PLACEHOLDER, row.key).trim()
        if (ref.isEmpty()) return
        ProgressPanel.publishRowNumber(index)
        io.launch {
            val note = prepareRetry(row.key, panel.cleanupDir, stopFirst)
            ProgressPanel.markRetrying(index, note)
            runTaskByName(ref, panel.projectId)
        }
    }

    /**
     * Clear the wreckage a killed export leaves behind, so a repair starts from a clean slate:
     *
     *  - **half-written backups** — a process killed mid-ZIP leaves a file with no end-of-archive
     *    record. It is unrestorable but looks like a real backup sitting in 白い熊's folder (one was
     *    329 MB), so every retry sweeps that app's invalid archives out of the backup directory. Only
     *    provably-broken files are touched; a readable ZIP is never deleted.
     *  - **[stopFirst] the app itself** — a sister app whose export died with its "in progress" guard
     *    still set answers `ERROR:export already running` forever. The guard lives in its process, so
     *    force-stopping it (Shizuku) is what actually clears it. 書籍閲覧 sat wedged like this from
     *    19:19 onward on 2026-07-27 — alive, idle, and refusing every request.
     */
    private suspend fun prepareRetry(pkg: String, cleanupDir: String, stopFirst: Boolean): String {
        val notes = mutableListOf<String>()
        if (stopFirst && pkg.isNotBlank()) {
            val stopped = runCatching {
                ShizukuShell.available() && ShizukuShell.exec("am force-stop $pkg").exitCode == 0
            }.getOrDefault(false)
            notes += if (stopped) "アプリを停止" else "停止できず（Shizuku 未接続？）"
            if (stopped) delay(1_500)
        }
        val swept = sweepBrokenBackups(pkg, cleanupDir)
        if (swept > 0) notes += "壊れた保存 $swept 件を削除"
        return notes.joinToString(" · ")
    }

    /** Delete this app's unreadable archives in [dir]; returns how many went. */
    private fun sweepBrokenBackups(pkg: String, dir: String): Int {
        if (dir.isBlank() || pkg.isBlank()) return 0
        // shiroikuma.shosekietsuran → shiroikuma-shosekietsuran_… (the family's file-name convention)
        val prefix = pkg.replace('.', '-') + "_"
        val folder = java.io.File(dir)
        if (!folder.isDirectory) return 0
        var removed = 0
        folder.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.startsWith(prefix) || !file.name.endsWith(".zip")) return@forEach
            if (isReadableZip(file)) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    /** A ZIP is complete only if its end-of-central-directory record is there to be found. */
    private fun isReadableZip(file: java.io.File): Boolean = runCatching {
        val tail = minOf(file.length(), 66_000L).toInt()
        if (tail < 22) return false
        val bytes = ByteArray(tail)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - tail)
            raf.readFully(bytes)
        }
        for (i in bytes.size - 4 downTo 0) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()
            ) {
                return true
            }
        }
        false
    }.getOrDefault(true)   // unreadable for any other reason: leave it alone

    /**
     * 「停止して起動管理へ」 — the repair for an app the OEM is starving. Force-stops the wedged process
     * (Shizuku), sweeps its half-written archives, and opens Huawei's **アプリ起動管理**, where the app
     * must be switched to 手動管理 with バックグラウンドで実行 ON. It deliberately does **not** re-run the
     * export — nothing would change until that setting does; the re-run is the button next to it.
     *
     * Why not the battery whitelist: exemption from AOSP battery optimisation does not move an app out
     * of EMUI's freezer. Measured on 白い熊's Mate XT, 2026-07-27 — a starved app sits in
     * `freezer:/Group_…` + `cpuset:/background` with its wakelock force-released a second after it is
     * taken, while an app allowed to run in the background sits in `freezer:/` + `cpuset:/vip`. Setting
     * 起動管理 to manual is what moved a 2.25 GB export from "always dies part-way" to finishing.
     */
    fun stopAndOpenLaunchManager(index: Int) {
        val panel = ProgressPanel.state.value ?: return
        val row = panel.outer.getOrNull(index) ?: return
        io.launch {
            val done = prepareRetry(row.key, panel.cleanupDir, stopFirst = true)
            ProgressPanel.annotate(
                index,
                listOfNotNull(done.takeIf { it.isNotBlank() }, "起動管理へ — 手動管理にして「バックグラウンドで実行」をON、そのあと［保存し直す］")
                    .joinToString(" · "),
            )
            openAppLaunchManagement(row.key)
        }
    }

    /**
     * 「削除」 in the prune list: delete every ticked archive, then turn the same window into the
     * report of what went — so the outcome is read where the choice was made.
     */
    fun deleteMarked() {
        val panel = ProgressPanel.state.value ?: return
        if (panel.markedCount == 0) return
        io.launch {
            var removed = 0
            var freed = 0L
            var failed = 0
            val outer = panel.outer.map { row ->
                var rowRemoved = 0
                var rowFreed = 0L
                val kept = row.children.filter { child ->
                    if (!child.marked) return@filter true
                    val ok = runCatching { java.io.File(child.key).delete() }.getOrDefault(false)
                    if (ok) { rowRemoved++; rowFreed += child.bytes } else failed++
                    !ok
                }
                removed += rowRemoved
                freed += rowFreed
                row.copy(
                    children = kept.map { it.copy(marked = false) },
                    state = if (rowRemoved > 0) ProgressRowState.DONE else ProgressRowState.SKIP,
                    detail = if (rowRemoved > 0) "$rowRemoved 件削除 · ${humanSize(rowFreed)}" else "そのまま",
                    bytes = row.bytes - rowFreed,
                )
            }
            ProgressPanel.update {
                it.copy(
                    outer = outer,
                    selecting = false,
                    finished = true,
                    okLabel = "OK",
                    summary = buildString {
                        append("$removed 件削除 · ${humanSize(freed)} 解放")
                        if (failed > 0) append(" · $failed 件失敗")
                    },
                )
            }
        }
    }

    /**
     * The plan's action button: publish what was ticked and hand over to the run.
     *
     * The choice is written to per-run variables — `%BR_RunApps` and `%BR_Run_<Suffix>` — rather than
     * over `%BR_Items_<Suffix>`, so narrowing one run (or adding an item the saved selection leaves
     * out) never disturbs the saved defaults.
     */
    fun confirmSelection() {
        val panel = ProgressPanel.state.value ?: return
        val apps = panel.markedRows
        // Silence here reads as a dead button. Say which of the two it is.
        if (apps.isEmpty()) {
            flash(if (panel.itemsMode) "アプリが選ばれていません — 保存する項目がありません" else "アプリが選ばれていません")
            return
        }
        if (panel.itemsMode) {
            val pairs = ProgressPanel.publishItemSelection()
            hide()
            ProgressPanel.hide()
            io.launch {
                val saved = persistItemSelection(panel.settingsTask, pairs)
                flash(
                    if (saved < 0) "保存しました（${pairs.size} アプリ）— 設定[01]に書き戻せませんでした"
                    else "保存項目を保存しました — ${pairs.size} アプリ",
                )
            }
            return
        }
        if (panel.confirmTask.isBlank()) return
        ProgressPanel.publishRunSelection()
        io.launch { runTaskByName(panel.confirmTask, panel.projectId) }
    }

    /**
     * Bake each `%BR_Items_<Suffix>` into the settings task's matching `var.set`, so the choice
     * survives the next startup instead of being clobbered by the baked-in default — the same
     * write-back `task.editaction` does for one app, done here for the whole roster in one pass.
     *
     * Returns how many lines were rewritten, or -1 when the settings task could not be found. A name
     * with no `var.set` in that task is skipped rather than added: the roster's lines are created by
     * 「保存対象選択」, and inventing one here would hide a missing app instead of showing it.
     */
    private suspend fun persistItemSelection(taskName: String, pairs: List<Pair<String, String>>): Int {
        val name = taskName.trim()
        if (name.isEmpty() || pairs.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val db = OpenTaskerApp_NoHilt.db
            val task = db.taskDao().getByName(name)?.toDomain() ?: return@withContext -1
            val wanted = pairs.toMap()
            var changed = 0
            val updated = task.actions.map { action ->
                if (action.type != "var.set") return@map action
                val varName = action.args["name"]?.trim()?.removePrefix("%") ?: return@map action
                val value = wanted[varName] ?: return@map action
                if (action.args["value"] == value) return@map action
                changed++
                action.copy(args = action.args + ("value" to value))
            }
            if (changed > 0) db.taskDao().update(task.copy(actions = updated).toEntity())
            changed
        }
    }

    /** The engine's own flash, so panel feedback looks like every other flash 白い熊 sees. */
    private fun flash(text: String) {
        val context = appContext ?: return
        val prefs = ThemeStore.state.value
        main.post {
            FlashOverlay.show(
                context = context,
                text = text,
                backgroundColor = prefs.flashBackground,
                textColor = prefs.flashText,
                borderColor = prefs.flashBorder,
                borderWidthDp = prefs.flashBorderWidthDp,
                cornerRadiusDp = prefs.flashCornerRadiusDp,
                textSizeSp = prefs.flashTextSizeSp,
                fontWeight = prefs.flashFontWeight,
                gravity = Gravity.CENTER,
                xDp = 0,
                yDp = 0,
                longDuration = false,
            )
        }
    }

    /** Open the folder browser at the pill's current directory. */
    fun openDirBrowser() {
        val panel = ProgressPanel.state.value ?: return
        val start = panel.dirPath.ifBlank { EXTERNAL_ROOT }
        io.launch { ProgressPanel.openBrowser(listDirs(start)) }
    }

    /** Walk into a sub-folder, or up out of one. */
    fun browseInto(name: String) {
        val panel = ProgressPanel.state.value ?: return
        val current = java.io.File(panel.browsePath.ifBlank { EXTERNAL_ROOT })
        val next = if (name == "..") current.parentFile ?: current else java.io.File(current, name)
        io.launch { ProgressPanel.browseTo(next.absolutePath, listDirs(next.absolutePath)) }
    }

    /** Sub-folders only, hidden ones left out — this picks a backup destination, not a file. */
    private fun listDirs(path: String): List<String> = runCatching {
        java.io.File(path).listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            .orEmpty()
    }.getOrDefault(emptyList())

    private const val EXTERNAL_ROOT = "/storage/emulated/0"

    /** Huawei's App launch management screen; falls back to the app's own settings page. */
    fun openAppLaunchManagement(pkg: String = "") {
        val context = appContext ?: return
        val intents = buildList {
            add(Intent("huawei.intent.action.HSM_STARTUPAPP_MANAGER").addCategory(Intent.CATEGORY_DEFAULT))
            add(Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            add(Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
            if (pkg.isNotEmpty()) {
                add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** 「失敗をすべて保存し直す」 — every failed row in turn, one at a time (they talk to the same apps). */
    fun retryAllFailed() {
        val panel = ProgressPanel.state.value ?: return
        val targets = panel.failedRows
        if (targets.isEmpty() || panel.retryTask.isBlank()) return
        io.launch {
            for (index in targets) {
                val current = ProgressPanel.state.value ?: return@launch
                val row = current.outer.getOrNull(index) ?: continue
                val ref = current.retryTask.replace(ROW_KEY_PLACEHOLDER, row.key).trim()
                if (ref.isEmpty()) continue
                ProgressPanel.publishRowNumber(index)
                ProgressPanel.markRetrying(index, prepareRetry(row.key, current.cleanupDir, stopFirst = false))
                runTaskByName(ref, current.projectId)
            }
        }
    }

    private suspend fun runTaskByName(ref: String, projectId: Long) {
        val context = appContext ?: return
        val db = OpenTaskerApp_NoHilt.db
        val task = resolveTaskByName(db, ref, projectId) ?: return
        runCatching { executeAndLogTask(context, db, task, source = "Progress panel") }
    }

    /**
     * Open Settings' All-files-access page for one app — the fix for the `no-storage-access` family,
     * where a sister app cannot write into 白い熊's backup directory because the grant was declined.
     * The panel stays up behind it, so the repair is: grant → back → 保存し直す.
     */
    fun openAllFilesAccess(pkg: String) {
        val context = appContext ?: return
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$pkg"),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            // Last resort: the app's own details page, where the toggle also lives on EMUI.
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$pkg"),
            ),
        )
        for (intent in intents) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    /**
     * Open the battery-optimisation exemption page for one app — the fix for the reserved
     * `ERROR:no-foreground-start` key, where a sister app's export could not start its foreground
     * service because a broadcast is a **background start** on API 31+.
     *
     * The exemption is one of the allowances that sets `mAllowStartForeground`, so granting it is
     * the repair, and the panel stays up behind: grant → back → 保存し直す.
     *
     * **The key is reserved for the exemption-fixable case only** (`shiroikuma-handyrss`). The same
     * call site can also fail with a missing `FOREGROUND_SERVICE` permission, or — on this phone —
     * with EMUI's アプリ起動管理 set to 自動管理, which **no app can change for itself**. Those must
     * keep a descriptive `ERROR:` line instead, because a button that does not fix the fault is
     * worse than no button: 白い熊 presses it, nothing changes, and the row still fails.
     */
    fun openBatteryExemption(pkg: String) {
        val context = appContext ?: return
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            // EMUI keeps the per-app control on the details page rather than the global list.
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$pkg"),
            ),
        )
        for (intent in intents) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }

    internal const val ROW_KEY_PLACEHOLDER = "{key}"
}

// ── Palette — the fork's black panel / yellow accent, same look as the 進捗盤 scene it replaces ──
// Fully opaque: the panel sits over whatever is in front, and even a few percent of bleed-through made
// the app underneath (bright yellow on black) legible right across the rows.
private val PanelBg = Color(0xFF000000)
private val Accent = Color(0xFFFFFF00)
// Every item is the fork's pure yellow. Finished rows are separated by OPACITY alone (and their
// full-strength tick), never by a duller hue — a tinted "yellow" reads as olive next to the real one.
private val DoneColor = Color(0xFFFFFF00)
private val PendingColor = Color(0xFFFFFF00)
private val FailColor = Color(0xFFFF9E9E)
/** Wash behind anything red: thin red glyphs on pure black are the hardest thing here to read. */
private val FailBg = Color(0x33FF5A5A)
private val HeaderColor = Color(0xFFFFFFFF)
private val ActiveRowBg = Color(0x33FFFF00)

private val OuterRowHeight = 30.dp
private val InnerRowHeight = 26.dp

/** One rendered line: a row of either pane, an unfolded child, the live counter note, or — under an
 *  opened failed row — its full error text and the buttons that repair it. */
private data class PanelLine(
    val row: ProgressRow?,
    val note: String = "",
    val active: Boolean = false,
    val depth: Int = 0,
    val errorText: String = "",
    val repairIndex: Int = -1,      // ≥ 0 = the repair button row for that outer row
    val repairPackage: String = "", // non-blank = also offer the storage-access grant
    val repairStale: Boolean = false, // the app is wedged on a previous run: offer to stop it first
    val repairBattery: String = "", // non-blank = also offer the battery-optimisation exemption
    val parentKey: String = "",       // selection mode: tapping this child toggles its mark
    val masterToggle: Boolean = false,   // "select / deselect every app"
    val groupToggleKey: String = "",     // "select / deselect every item of this app"
)

/**
 * Errors that mean the app never got to finish: it is wedged on a previous attempt, or the OEM is
 * freezing it mid-export. Both are cured the same way — kill the process, then allow it to run in
 * the background — and neither is cured by simply asking again.
 */
private fun isStaleRunError(detail: String): Boolean {
    val d = detail.lowercase()
    return "already running" in d || "in progress" in d || "busy" in d ||
        "stalled" in d || "silent" in d || "作業停止" in detail || "応答途絶" in detail || "応答なし" in detail
}

/** Errors that mean "this app may not write into 白い熊's backup directory" — the grant is the fix. */
private fun isStorageAccessError(detail: String): Boolean {
    val d = detail.lowercase()
    return "no-storage-access" in d || "eacces" in d || "permission denied" in d ||
        "open failed" in d || "no-directory" in d
}

/**
 * The one error that the battery-optimisation button actually repairs.
 *
 * **Matched on the reserved key alone, never on the exception text.** An app that catches
 * `Throwable` and emits the key unconditionally is the easy implementation and the one that produces
 * a useless button, so the contract reserves `no-foreground-start` for the exemption-fixable case and
 * requires every other start failure to carry a descriptive line. (`shiroikuma-handyrss`.)
 */
private fun isForegroundStartError(detail: String): Boolean =
    "no-foreground-start" in detail.lowercase()

@Composable
internal fun ProgressPanelUi(state: ProgressPanelState) {
    // Keep the whole panel inside the screen: if the two panes plus chrome don't fit (the folded cover
    // display), shrink both pane heights proportionally rather than letting it run off the edge.
    val context = LocalContext.current
    val screenHeight = remember {
        // The overlay is not inside the app's content area, so LocalConfiguration under-reports the
        // height by the system bars — measure the display itself.
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        m.heightPixels / m.density
    }
    val chrome = 110 + 70 * state.textScale          // header + buttons + padding, which also scale
    // A selection list is the whole job, so it takes the whole window — nearly top to bottom, as it
    // already nearly reaches the sides — and shows as many lines as that height holds. Its chrome is
    // just the header and one button row, so it is measured separately from the report's.
    val selectionChrome = 46f + 26f * state.textScale + 46f
    val selectionRowHeight = listRowHeight(state.textScale).value + SELECTION_LINE_GAP.value
    val fillLines = (((screenHeight * 0.96f) - selectionChrome) / selectionRowHeight).toInt().coerceIn(4, 60)
    // Starting the run must not shrink the window: the two panes divide the same height between them,
    // ~60/40, at the same text size the plan was read at.
    val runChrome = 40f + 40f * state.textScale + 44f      // two headers, one button, padding
    val runRow = listRowHeight(state.textScale).value + SELECTION_LINE_GAP.value
    val runBudget = (screenHeight * 0.96f) - runChrome
    val runInnerLines = ((runBudget * 0.4f) / runRow).toInt().coerceIn(2, 40)
    val runOuterLines = ((runBudget - runInnerLines * runRow) / runRow).toInt().coerceIn(3, 40)
    val wanted = (state.outerLines * OuterRowHeight.value + state.innerLines * InnerRowHeight.value) *
        state.textScale
    val budget = (screenHeight - chrome).coerceAtLeast(200f)
    val scale = if (!state.selecting && !state.fillHeight && wanted > budget) budget / wanted else 1f

    val outerLines = buildList {
        if (state.selecting && state.rowsSelectable) {
            add(PanelLine(row = null, masterToggle = true))
        }
        state.outer.forEachIndexed { index, row ->
            val shown = if (!state.selecting) row else {
                val marked = row.children.filter { it.marked }
                // Selected out of what is there — the number only means something against the total.
                // A plan counts items; the prune list counts files, which have a size worth showing.
                row.copy(
                    detail = if (state.rowsSelectable) {
                        "${marked.size}/${row.children.size} 項目"
                    } else {
                        "(${marked.size}/${row.children.size}) · " +
                            humanPair(marked.sumOf { it.bytes }, row.children.sumOf { it.bytes })
                    },
                )
            }
            add(PanelLine(row = shown, active = index == state.outerIndex))
            if (shown.key in state.expanded) {
                // Where it was written, then what went into it — and, when it failed, the whole
                // error plus the buttons that fix it.
                if (row.note.isNotBlank()) add(PanelLine(row = null, note = row.note, depth = 1))
                if (state.selecting) add(PanelLine(row = null, depth = 1, groupToggleKey = row.key))
                row.children.forEach { child ->
                    add(
                        PanelLine(
                            row = child,
                            depth = 1 + child.depth,
                            parentKey = if (state.selecting) row.key else "",
                        ),
                    )
                }
                if (row.failed && row.detail.isNotBlank()) {
                    add(PanelLine(row = null, errorText = row.detail))
                }
                // The repair line stays on an opened row whatever its state — including WHILE a retry
                // is running. Losing it mid-retry left a stuck row with no way to act on it at all.
                if (state.finished && state.retryTask.isNotBlank()) {
                    val why = row.errorForRepair
                    add(
                        PanelLine(
                            row = null,
                            repairIndex = index,
                            repairPackage = if (state.icons && isStorageAccessError(why)) row.key else "",
                            repairStale = state.icons && isStaleRunError(why),
                            repairBattery =
                                if (state.icons && isForegroundStartError(why)) row.key else "",
                        ),
                    )
                }
            }
        }
    }
    // Both counters on one line under the running item: what it has written, and how much of it.
    // 「ファイル 1234/8942 · 512 MB / 4.2 GB」 — the app supplies the left half as text, the byte pair
    // is rendered here so the sizes read the same as everywhere else in the panel.
    val innerCounter = buildString {
        append(state.innerNote)
        if (state.innerBytesTotal > 0 || state.innerBytes > 0) {
            if (isNotEmpty()) append(" · ")
            append(humanSize(state.innerBytes))
            if (state.innerBytesTotal > 0) append(" / ").append(humanSize(state.innerBytesTotal))
        }
    }
    val innerLines = buildList {
        state.inner.forEachIndexed { index, row ->
            val active = index == state.innerIndex
            // Sub-options sit indented under the group they belong to, as they do in the plan window.
            add(PanelLine(row = row, active = active, depth = row.depth))
            if (active && innerCounter.isNotBlank()) {
                add(PanelLine(row = null, note = innerCounter, depth = row.depth))
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(PanelBg, RoundedCornerShape(14.dp))
            .border(2.dp, Accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        PaneHeader(
            scale = state.textScale,
            title = state.title,
            counter = if (state.selecting) {
                if (state.rowsSelectable) {
                    "アプリ ${state.markedRows.size}/${state.outer.size} · " +
                        "項目 ${state.markedRows.sumOf { r -> r.children.count { it.marked } }}/" +
                        state.markedRows.sumOf { it.children.size }
                } else {
                    "削除 ${state.markedCount}/${state.totalChildren} 件 · " +
                        humanPair(state.markedBytes, state.totalBytes)
                }
            } else if (state.finished) {
                // Live from the rows, so repairing one afterwards updates the tally.
                val failed = state.failedRows.size
                buildString {
                    append("完了 ${state.doneCount} ✓")
                    if (failed > 0) append(" · 失敗 $failed ✗")
                    if (state.summary.isNotBlank()) append(" · ${state.summary}")
                }
            } else {
                counterText(state.outerUnit, state.outerIndex, state.outer.size)
            },
            emphasise = true,
        )
        // Where this run will write. Tapping it opens the folder browser; whatever is chosen holds for
        // THIS run only — it goes to a per-run variable, never over the configured export directory.
        if (state.selecting && (state.dirPath.isNotBlank() || state.browsePath.isNotBlank())) {
            // While browsing, the pill follows the folder being walked — it is the only place the
            // current path is shown, so it has to track it rather than sit on the old destination.
            DirPill(
                path = state.browsePath.ifBlank { state.dirPath },
                changed = state.dirChanged,
                browsing = state.browsePath.isNotBlank(),
                scale = state.textScale,
                onClick = { ProgressPanelManager.openDirBrowser() },
            )
        }
        if (state.browsePath.isNotBlank()) {
            FolderBrowser(state)
            return@Column
        }
        Pane(
            // The report gets the item pane's height as well — the whole panel becomes the list.
            lines = outerLines,
            visibleLines = when {
                state.selecting -> fillLines
                // Report and single-pane runs both own the item pane's height: the list is the panel.
                (state.finished || state.singlePane) && state.fillHeight -> runOuterLines + runInnerLines
                state.finished || state.singlePane -> state.outerLines + state.innerLines
                state.fillHeight -> runOuterLines
                else -> state.outerLines
            },
            // A selection list is read line by line, so it is set tight: the row is the text plus a
            // hair, instead of a progress row's roomier box.
            rowHeight = if (state.selecting || state.fillHeight) listRowHeight(state.textScale)
            else OuterRowHeight * scale * state.textScale,
            textScale = state.textScale,
            lineGap = if (state.selecting || state.fillHeight) SELECTION_LINE_GAP else 0.dp,
            rowsSelectable = state.rowsSelectable,
            icons = state.icons,
            expandedKeys = state.expanded,
            onTapRow = { row -> if (row.expandable) ProgressPanel.toggleExpanded(row.key) },
            onTapChild = { parentKey, child -> ProgressPanel.toggleMark(parentKey, child.key) },
        )
        if (!state.finished && !state.selecting && !state.singlePane) {
            Spacer(Modifier.height(10.dp))
            PaneHeader(
                title = state.outer.getOrNull(state.outerIndex)?.label.orEmpty(),
                counter = counterText(state.innerUnit, state.innerIndex, state.inner.size),
                emphasise = false,
                scale = state.textScale,
            )
            Pane(
                lines = innerLines,
                visibleLines = if (state.fillHeight) runInnerLines else state.innerLines,
                rowHeight = if (state.fillHeight) listRowHeight(state.textScale)
                else InnerRowHeight * scale * state.textScale,
                lineGap = if (state.fillHeight) SELECTION_LINE_GAP else 0.dp,
                icons = false,
                expandedKeys = emptySet(),
                onTapRow = null,
                textScale = state.textScale,
            )
        }
        if (state.selecting) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelButton(
                    label = state.cancelLabel.ifBlank { "キャンセル" },
                    modifier = Modifier.weight(1f),
                    onClick = { ProgressPanelManager.hide(); ProgressPanel.hide() },
                )
                PanelButton(
                    label = if (state.confirmTask.isNotBlank()) {
                        "${state.confirmLabel} ${state.markedRows.size} アプリ"
                    } else {
                        "${state.confirmLabel.ifBlank { "削除" }} ${state.markedCount} 件 · ${humanSize(state.markedBytes)}"
                    },
                    modifier = Modifier.weight(1f),
                    emphasise = true,
                    onClick = {
                        if (state.confirmTask.isNotBlank()) ProgressPanelManager.confirmSelection()
                        else ProgressPanelManager.deleteMarked()
                    },
                )
            }
        }
        if (state.finished && state.failedRows.size > 1 && state.retryTask.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            PanelButton(
                label = "失敗をすべて保存し直す (${state.failedRows.size})",
                onClick = { ProgressPanelManager.retryAllFailed() },
            )
        }
        if (state.finished) {
            Spacer(Modifier.height(8.dp))
            PanelButton(label = state.okLabel, emphasise = true, onClick = {
                ProgressPanelManager.hide()
                ProgressPanel.hide()
            })
        } else if (!state.selecting && state.cancelLabel.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            CancelButton(label = state.cancelLabel, cancelled = state.cancelled)
        }
    }
}

/** 「アプリ 7/31」 — the real numbers, never a percentage. */
private fun counterText(unit: String, index: Int, total: Int): String {
    if (total == 0) return ""
    val current = (index + 1).coerceIn(0, total)
    return if (unit.isBlank()) "$current/$total" else "$unit $current/$total"
}

@Composable
private fun PaneHeader(title: String, counter: String, emphasise: Boolean, scale: Float = 1f) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            color = if (emphasise) Accent else DoneColor,
            textDecoration = if (emphasise) TextDecoration.Underline else null,
            fontSize = (if (emphasise) 16f else 13f).times(scale).sp,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = counter,
            color = Accent,
            fontSize = (if (emphasise) 15f else 13f).times(scale).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * A scrolling pane that follows the active row: it is parked `(visibleLines - 1) / 2` rows down, so a
 * 10-line pane shows 4 finished rows above the active one and 5 still to come below it. Scrolling by
 * hand suspends the follow for [MANUAL_SCROLL_GRACE_MS], so you can look ahead or back without the
 * next update yanking you away.
 */
@Composable
private fun Pane(
    lines: List<PanelLine>,
    visibleLines: Int,
    rowHeight: Dp,
    icons: Boolean,
    expandedKeys: Set<String>,
    onTapRow: ((ProgressRow) -> Unit)?,
    onTapChild: ((String, ProgressRow) -> Unit)? = null,
    textScale: Float = 1f,
    lineGap: Dp = 0.dp,
    rowsSelectable: Boolean = false,
    rowsTappable: Boolean = false,
) {
    val listState = rememberLazyListState()
    var lastManualScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) lastManualScroll = SystemClock.elapsedRealtime()
        }
    }
    val activeLine = lines.indexOfFirst { it.active }
    LaunchedEffect(activeLine, lines.size) {
        if (activeLine < 0) return@LaunchedEffect
        if (SystemClock.elapsedRealtime() - lastManualScroll < MANUAL_SCROLL_GRACE_MS) return@LaunchedEffect
        val lead = (visibleLines - 1) / 2
        runCatching { listState.animateScrollToItem((activeLine - lead).coerceAtLeast(0)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height((rowHeight + lineGap) * visibleLines)
            .background(Color.Black, RoundedCornerShape(10.dp))
            .border(1.dp, Accent, RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(vertical = 2.dp, horizontal = 3.dp),
        verticalArrangement = Arrangement.spacedBy(lineGap),
    ) {
        items(lines) { line -> PanelLineView(line, rowHeight, icons, expandedKeys, onTapRow, onTapChild, textScale, rowsSelectable, rowsTappable) }
    }
}

/**
 * The four frames of the running-row spinner.
 *
 * Geometric shapes rather than the usual braille spinner: braille falls back to tofu on a phone whose
 * font never needed it, and a row of empty boxes is a worse "it is alive" signal than no animation at
 * all. These four are in every CJK font this app will ever meet.
 */
private val SPINNER_FRAMES = listOf("◐", "◓", "◑", "◒")

/** Slow enough to read as turning rather than flickering, fast enough to look alive. */
private const val SPINNER_FRAME_MS = 140L

/**
 * A glyph that turns on its own.
 *
 * Deliberately driven by its own coroutine rather than by panel updates: the case that matters is a
 * task blocked inside ONE long action, where by definition nothing is arriving to redraw the row. A
 * spinner that only moved when the panel was updated would stop precisely when it is needed.
 */
@Composable
private fun spinnerFrame(): String {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_FRAME_MS)
            index = (index + 1) % SPINNER_FRAMES.size
        }
    }
    return SPINNER_FRAMES[index]
}

@Composable
private fun PanelLineView(
    line: PanelLine,
    rowHeight: Dp,
    icons: Boolean,
    expandedKeys: Set<String>,
    onTapRow: ((ProgressRow) -> Unit)?,
    onTapChild: ((String, ProgressRow) -> Unit)? = null,
    textScale: Float = 1f,
    rowsSelectable: Boolean = false,
    rowsTappable: Boolean = false,
) {
    val row = line.row
    if (row == null) {
        when {
            // The whole error, wrapped — the row itself only had space for the first line of it.
            line.errorText.isNotEmpty() -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, bottom = 6.dp)
                    .background(FailBg, RoundedCornerShape(8.dp))
                    .border(1.dp, FailColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(text = line.errorText, color = FailColor, fontSize = 12.sp)
            }
            // Select / deselect everything, at the top of the list and inside each app.
            line.masterToggle -> ToggleLine(
                label = "すべて選択 / 解除",
                indent = 0.dp,
                rowHeight = rowHeight,
                textScale = textScale,
                onClick = { ProgressPanel.toggleAllRows() },
            )
            line.groupToggleKey.isNotEmpty() -> ToggleLine(
                label = "この項目をすべて選択 / 解除",
                indent = 20.dp * textScale,
                rowHeight = rowHeight,
                textScale = textScale,
                onClick = { ProgressPanel.toggleMarkAll(line.groupToggleKey) },
            )
            // Grant storage access / re-run this one app, without leaving the report.
            line.repairIndex >= 0 -> Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (line.repairPackage.isNotEmpty()) {
                    PanelButton(
                        label = "全ファイルアクセスを許可",
                        modifier = Modifier.weight(1f),
                        onClick = { ProgressPanelManager.openAllFilesAccess(line.repairPackage) },
                    )
                }
                if (line.repairBattery.isNotEmpty()) {
                    PanelButton(
                        label = "電池最適化を除外",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ProgressPanelManager.openBatteryExemption(line.repairBattery)
                        },
                    )
                }
                if (line.repairStale) {
                    PanelButton(
                        label = "停止して起動管理へ",
                        modifier = Modifier.weight(1f),
                        emphasise = true,
                        onClick = { ProgressPanelManager.stopAndOpenLaunchManager(line.repairIndex) },
                    )
                }
                PanelButton(
                    label = "保存し直す",
                    modifier = Modifier.weight(1f),
                    emphasise = !line.repairStale,
                    onClick = { ProgressPanelManager.retryRow(line.repairIndex) },
                )
            }
            // The live counter the running app reports, or a finished row's written path.
            else -> Row(
                Modifier.fillMaxWidth().height(rowHeight).padding(start = if (line.depth > 0) 24.dp else 34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.note,
                    color = if (line.depth > 0) PendingColor else Accent,
                    fontSize = if (line.depth > 0) 11.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    val glyph = when (row.state) {
        ProgressRowState.DONE -> "✓"
        ProgressRowState.FAIL -> "✗"
        // The running row TURNS. A static ▶ beside a step that takes twenty seconds is
        // indistinguishable from a wedged one, which is exactly how it read (白い熊, 2026-08-11) —
        // the animation is driven by the composition's own clock, so it keeps moving even while the
        // task is blocked inside a single action and nothing is updating the panel at all.
        ProgressRowState.ACTIVE -> spinnerFrame()
        ProgressRowState.CANCEL -> "■"
        ProgressRowState.SKIP -> "–"
        ProgressRowState.PENDING -> "·"
    }
    val color = when (row.state) {
        ProgressRowState.DONE -> DoneColor
        ProgressRowState.FAIL -> FailColor
        ProgressRowState.ACTIVE -> Accent
        ProgressRowState.CANCEL -> FailColor
        ProgressRowState.SKIP, ProgressRowState.PENDING -> PendingColor
    }
    // Finished rows step back so the active one and the work still to come stay legible.
    val alpha = when (row.state) {
        ProgressRowState.DONE, ProgressRowState.SKIP, ProgressRowState.CANCEL -> 0.62f
        else -> 1f
    }
    val selectable = line.parentKey.isNotEmpty() && onTapChild != null
    val rowSelectable = line.depth == 0 && rowsSelectable
    // A folder-browser entry has no children, so `expandable` is false — but the whole point of
    // the row is that it is tapped. rowsTappable says so explicitly.
    val tappable = (onTapRow != null && (row.expandable || rowsTappable)) || selectable
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(start = (line.depth * 20).dp * textScale)
            .background(
                when {
                    line.active -> ActiveRowBg
                    row.failed -> FailBg
                    else -> Color.Transparent
                },
                RoundedCornerShape(6.dp),
            )
            .then(
                when {
                    // An item's line selects it; an app's line opens it, and only its box selects.
                    selectable -> Modifier.clickable { onTapChild?.invoke(line.parentKey, row) }
                    tappable -> Modifier.clickable { onTapRow?.invoke(row) }
                    else -> Modifier
                },
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The box is its own target: on an app row it is the ONLY thing that selects, so the rest of
        // the line is free to fold the app open.
        Box(
            Modifier
                .width(26.dp * textScale)
                .then(if (rowSelectable) Modifier.clickable { ProgressPanel.toggleRowMark(row.key) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    selectable || rowSelectable -> if (row.marked) "☑" else "☐"
                    else -> glyph
                },
                // The tick stays at full strength even though its row is dimmed — the finished-or-not
                // read has to survive a glance down a list of thirty.
                color = if (row.state == ProgressRowState.DONE) Accent else color.copy(alpha = alpha),
                fontSize = (if (line.depth > 0) 11f else 13f).times(textScale).sp,
                fontWeight = if (row.state == ProgressRowState.DONE) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (icons && line.depth == 0) {
            AppIcon(row.key, (rowHeight.value * 0.72f).dp)
            Spacer(Modifier.width(6.dp))
        }
        // The label takes ALL the slack, so the detail below is pinned to the right edge and every
        // row's size lands in the same column instead of floating after its own label.
        Text(
            text = row.label,
            color = color.copy(alpha = alpha),
            fontSize = (if (line.depth > 0) 12f else if (line.active) 15f else 14f).times(textScale).sp,
            fontWeight = if (line.active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.detail.isNotBlank()) {
            // Capped: a long error must ellipsize HERE rather than squeeze the app's name to nothing
            // (a `…zip: open failed` reply once left a row showing no name at all). The full text is
            // one tap away in the fold-out.
            Text(
                text = row.detail,
                color = color.copy(alpha = alpha * 0.9f),
                fontSize = 11f.times(textScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth(if (selectable || line.depth == 0 && textScale > 1f) DETAIL_WIDE_FRACTION else DETAIL_MAX_WIDTH_FRACTION)
                    .padding(start = 8.dp),
            )
        }
        // The disclosure column is always reserved (empty when a row has nothing to unfold), so the
        // detail column stays put whether or not a row is expandable.
        Box(Modifier.width(26.dp * textScale), contentAlignment = Alignment.Center) {
            if (onTapRow != null && row.expandable) {
                Text(
                    text = if (row.key in expandedKeys) "▾" else "▸",
                    color = Accent,
                    fontSize = 14f.times(textScale).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AppIcon(pkg: String, size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(pkg) {
        runCatching {
            val pm = context.packageManager
            // MATCH_DISABLED_COMPONENTS: a frozen app still gets its icon drawn in the roster.
            val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            info.loadIcon(pm).toBitmap(72, 72).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Spacer(Modifier.size(size))
    }
}

/** The "everything / nothing" line that heads the list and each unfolded app. */
@Composable
private fun ToggleLine(label: String, indent: Dp, rowHeight: Dp, textScale: Float, onClick: () -> Unit) {
    // A PILL, hugging its own words (白い熊, 2026-09-03), not a full-width row.
    //
    // It sat in a list of checkbox rows and looked like one of them — a line of text at the top of
    // the column, indistinguishable from the apps below it except by reading it. It is not a row:
    // it is the one control that acts on ALL of them, so it takes the shape this panel already uses
    // for a control (the 保存先 pill) rather than the shape it uses for a list entry.
    //
    // The outer Row keeps [rowHeight], so the list's own metrics are untouched and nothing below
    // shifts; only the thing drawn inside it changed.
    Row(
        Modifier.fillMaxWidth().height(rowHeight).padding(start = indent, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .border(2.dp, Accent, RoundedCornerShape(999.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "⇄",
                color = Accent,
                fontSize = 14f.times(textScale).sp,
            )
            Text(
                text = label,
                color = Accent,
                fontSize = 13f.times(textScale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A panel action — OK, 保存し直す, the storage grant, retry-all. */
/**
 * Where this run writes, as a tappable pill above the list. Tapping opens [FolderBrowser]; the choice
 * lands in a per-run variable, so the configured export directory is never overwritten — hence the
 * 「今回のみ」 once it has been pointed somewhere else.
 */
@Composable
private fun DirPill(
    path: String,
    changed: Boolean,
    browsing: Boolean = false,
    scale: Float,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .border(2.dp, Accent, RoundedCornerShape(999.dp))
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = when {
                    browsing -> "選択中  $path"
                    changed -> "保存先（今回のみ）  $path"
                    else -> "保存先  $path"
                },
                color = if (changed || browsing) Accent else DoneColor,
                fontSize = (12f * scale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The folder browser the pill opens: sub-folders of the current path, 「‥ 上へ」, and the two buttons. */
@Composable
private fun FolderBrowser(state: ProgressPanelState) {
    val lines = buildList {
        add(PanelLine(row = ProgressRow(key = "..", label = "‥ 上へ")))
        state.browseDirs.forEach { add(PanelLine(row = ProgressRow(key = it, label = it))) }
    }
    Pane(
        lines = lines,
        visibleLines = 12,
        rowHeight = listRowHeight(state.textScale),
        icons = false,
        expandedKeys = emptySet(),
        onTapRow = { row -> ProgressPanelManager.browseInto(row.key) },
        textScale = state.textScale,
        lineGap = SELECTION_LINE_GAP,
        rowsTappable = true,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton(
            label = "戻る",
            modifier = Modifier.weight(1f),
            onClick = { ProgressPanel.closeBrowser() },
        )
        PanelButton(
            label = "ここに保存",
            modifier = Modifier.weight(1f),
            emphasise = true,
            onClick = { ProgressPanel.chooseBrowsedDir() },
        )
    }
}

@Composable
private fun PanelButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasise: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .then(if (modifier == Modifier) Modifier.fillMaxWidth() else Modifier)
            .height(38.dp)
            .background(if (emphasise) Color(0x22FFFF00) else Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            .border(1.dp, if (emphasise) Accent else PendingColor, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (emphasise) Accent else HeaderColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun CancelButton(label: String, cancelled: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(
                if (cancelled) Color(0x22FF7A7A) else Color(0x22FFFF00),
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (cancelled) FailColor else Accent,
                RoundedCornerShape(10.dp),
            )
            .then(if (cancelled) Modifier else Modifier.clickable { ProgressPanel.requestCancel() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (cancelled) "$label 中…" else label,
            color = if (cancelled) FailColor else Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "554.8/605.2 MB" — both numbers in the larger one's unit, far shorter than two full sizes. */
internal fun humanPair(part: Long, whole: Long): String {
    val (div, name) = when {
        whole >= 1_073_741_824L -> 1_073_741_824.0 to "GB"
        whole >= 1_048_576L -> 1_048_576.0 to "MB"
        whole >= 1024L -> 1024.0 to "KB"
        else -> 1.0 to "B"
    }
    val digits = if (div >= 1_073_741_824.0) 2 else 1
    return "%.${digits}f/%.${digits}f %s".format(part / div, whole / div, name)
}

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val MANUAL_SCROLL_GRACE_MS = 5_000L

/** How much of a row the right-hand detail may take before it ellipsizes (the name keeps the rest). */
private val SELECTION_LINE_GAP = 2.dp

/** One line's height, everywhere: the text plus a hair. Keeps the run view identical to the plan it
 *  came from, rather than the roomier box a progress list used to get. */
private fun listRowHeight(textScale: Float) = (14f * textScale + 10f).dp

private const val DETAIL_MAX_WIDTH_FRACTION = 0.42f

/** The prune list's rows are mostly number: "(11/12) · 554.8 MB / 605.2 MB" must not ellipsize. */
private const val DETAIL_WIDE_FRACTION = 0.62f
