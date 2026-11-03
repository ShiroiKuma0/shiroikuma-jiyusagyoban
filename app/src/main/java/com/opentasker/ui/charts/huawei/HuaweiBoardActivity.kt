package com.opentasker.ui.charts.huawei

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.huawei.HuaweiFaceLibrary
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.core.huawei.HuaweiSyncState
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「健康 -- [727]」 — the window the whole band is driven from.
 *
 * ## Why tapping a card closes this page
 *
 * Every card opens something: a window of its own, a dialog, or a task that flashes its result. A
 * board that stayed on top would put itself between 白い熊 and whatever he just asked for — and the
 * one thing a launcher must never do is obscure what it launched. So a card runs its task and this
 * page finishes. Coming back is one tap of the same shortcut.
 *
 * The sync card is the exception, and finishes nothing: it opens its own dialog HERE, because a sync
 * is the one action with nothing to show for itself except progress.
 */
class HuaweiBoardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            val chartStyle = remember(themePrefs) { ChartStyle.from(themePrefs) }
            var state by remember {
                mutableStateOf(
                    HuaweiBoardState(lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))),
                )
            }
            val scope = androidx.compose.runtime.rememberCoroutineScope()

            // The two real photographs, read once and off the main thread: the earth watch face out
            // of the library's own archive, and a piece of a walk 地図 has drawn a map for. Both are
            // optional — a missing one falls through to the drawn artwork rather than a hole.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val face = withContext(Dispatchers.IO) { faceArt() }
                val walk = withContext(Dispatchers.IO) { walkCutout() }
                state = state.copy(faceArt = face, walkArt = walk)
            }

            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(
                    LocalBandLanguage provides state.lang,
                    LocalChartStyle provides chartStyle,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        HuaweiBoardScreen(
                            state = state,
                            contentPadding = WindowInsets.systemBars.asPaddingValues(),
                            onRun = { tile ->
                                // A tile that is ALREADY running and knows how to show itself again
                                // is a way back, not a refusal. Checked before the busy guard, since
                                // the guard is exactly the thing that was swallowing the tap.
                                if (state.busy == tile.task && tile.reopenTask != null) {
                                    runTask(tile.reopenTask) {}
                                    return@HuaweiBoardScreen
                                }
                                if (state.anyBusy) return@HuaweiBoardScreen
                                if (tile.key == "sync") {
                                    // Opens the dialog and nothing else: a sync starts from one of
                                    // its buttons (白い熊, 2026-11-03).
                                    state = state.copy(sync = BoardSync())
                                } else if (tile.key == "lang") {
                                    // Opens at once, with no radio: there is nothing to ask the band
                                    // (it cannot report its language — see [BoardLanguage]), so the
                                    // dialog would have been spending a whole Bluetooth session to
                                    // learn nothing. The band is only touched when a switch is asked
                                    // for.
                                    state = state.copy(
                                        language = BoardLanguage(
                                            remembered = HuaweiSettings.bandLocale(applicationContext),
                                        ),
                                    )
                                } else {
                                    state = state.copy(busy = tile.task)
                                    // Closes as soon as the task has been HANDED OVER, not when it
                                    // finishes: most of these open a window of their own, and waiting
                                    // for that to close before this one does would stack two windows.
                                    runTask(tile.task) { finish() }
                                }
                            },
                            onSyncAction = { job ->
                                if (state.anyBusy) return@HuaweiBoardScreen
                                startSync(job, { state.sync }) { next -> state = state.copy(sync = next) }
                            },
                            onSyncInfo = { job -> state = state.copy(sync = state.sync?.copy(info = job)) },
                            onSwitchLanguage = { target ->
                                state = state.copy(
                                    language = state.language?.copy(switching = true, failed = null),
                                )
                                switchBandLanguage(target) { state = state.copy(language = it) }
                            },
                            onCloseLanguage = { state = state.copy(language = null) },
                            onCloseSync = {
                                // The watcher outlived the dialog it was feeding, and every tick
                                // wrote the sync state back — so OK cleared it and 700 ms later it
                                // returned. The dialog could not be dismissed at all (白い熊,
                                // 2026-08-29). Closing it stops the thing that reopens it.
                                stopWatchingSync()
                                state = state.copy(sync = null, busy = null)
                            },
                        )
                    }
                }
            }
            // Kept out of the composable's own scope: a task run must survive this window closing,
            // exactly as the watch-face installs do.
            this.scope = scope
        }
    }

    private var scope: kotlinx.coroutines.CoroutineScope? = null

    private fun runTask(name: String, onDone: () -> Unit) {
        HuaweiSyncRunner.scope.launch {
            val db = OpenTaskerApp_NoHilt.db
            // By name, and decoded the way every other caller decodes: a task whose stored JSON no
            // longer parses is skipped rather than thrown from inside the runner.
            val entity = db.taskDao().getByName(name)
            val decoded = entity?.toDomainDecodeResult()
            if (decoded != null && decoded.issue == null) {
                runCatching {
                    executeAndLogTask(
                        appContext = applicationContext, db = db, task = decoded.value,
                        source = "健康 board", logTag = "HuaweiBoard",
                    )
                }
            }
            scope?.launch { onDone() }
        }
    }

    /**
     * Push the other language.
     *
     * Not read back, because there is nothing to read back with — the band has no way to report its
     * language, which the full product-info sweep settled on 2026-08-29. What CAN be trusted is that
     * the push itself now works: it goes through the announcement first, the way Huawei Health sends
     * it, and 白い熊 confirmed the band changing on 2026-08-29. So the record is updated from what
     * was sent, and the dialog says that is what it is.
     */
    private fun switchBandLanguage(to: BandLanguage, onDone: (BoardLanguage) -> Unit) {
        HuaweiSyncRunner.scope.launch {
            val address = HuaweiSettings.address(applicationContext)
            val pushed = runCatching {
                HuaweiSyncRunner.setBandLocale(applicationContext, address, to.tag, imperial = false)
            }.getOrElse { Result.failure(it) }
            val ok = pushed.getOrNull() == true
            val next = BoardLanguage(
                remembered = HuaweiSettings.bandLocale(applicationContext),
                told = to.tag.takeIf { ok },
                failed = if (ok) {
                    null
                } else {
                    val why = pushed.exceptionOrNull()?.message
                    Loc(
                        "The watch did not take that language${why?.let { " — $it" }.orEmpty()}",
                        "時計はその言語を受け付けませんでした${why?.let { "— $it" }.orEmpty()}",
                    )
                },
            )
            scope?.launch { onDone(next) }
        }
    }

    /**
     * Run one of the sync dialog's jobs and keep the dialog fed while it runs.
     *
     * [current] and [publish] go through the composable's state so the dialog and this agree on
     * one value; every write happens on the window's own scope.
     */
    private fun startSync(job: SyncJob, current: () -> BoardSync?, publish: (BoardSync) -> Unit) {
        val startedAt = System.currentTimeMillis()
        fun at() = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        fun update(f: (BoardSync) -> BoardSync) {
            scope?.launch { current()?.let { publish(f(it)) } }
        }
        // A step as a new line — only when it is actually a new step, since the runner re-announces
        // its phase with every record counted.
        fun step(phase: String, message: String = "") = update { s ->
            val last = s.lines.lastOrNull()
            // A full sync walks many windows, each counting then reading: one line for each of the
            // two, with the window number in the counter, rather than two lines per window.
            val repeating = phase in REPEATING_PHASES && s.lines.any { it.phase == phase }
            if (repeating || (last != null && last.phase == phase && last.message == message)) s.copy(phase = phase)
            else s.copy(phase = phase, message = message, lines = s.lines + SyncLine(at(), phase, message))
        }
        publish(
            (current() ?: BoardSync()).copy(
                running = job, phase = "starting", message = "", percent = null, count = "",
                lines = listOf(SyncLine(0, "starting")), startedAtMs = startedAt, summary = "",
            ),
        )
        fun finish(summary: String) {
            stopWatchingSync()
            update { s ->
                s.copy(
                    running = null, last = job, phase = "done", percent = null, count = "",
                    summary = summary, lines = s.lines + SyncLine(at(), "done"),
                )
            }
        }
        when (job) {
            SyncJob.NORMAL, SyncJob.FULL -> {
                watchSync(startedAt, ::step) { pct, count -> update { it.copy(percent = pct, count = count) } }
                runTask(if (job == SyncJob.NORMAL) BOARD_TILES.first { it.key == "sync" }.task else SYNC_FULL_TASK) {
                    finish(PersistentGlobalScope.snapshotAll()["HUAWEI_Summary"].orEmpty())
                }
            }
            SyncJob.STATE -> runTask(SYNC_STATE_TASK) { finish(syncStateReadout()) }
            SyncJob.WORKOUTS -> HuaweiSyncRunner.scope.launch {
                val now = System.currentTimeMillis() / 1000
                val result = HuaweiSyncRunner.fetchWorkouts(
                    applicationContext,
                    HuaweiSettings.address(applicationContext),
                    now - WORKOUT_DAYS * 86_400L,
                    now,
                    OpenTaskerApp_NoHilt.db.huaweiWorkoutDao(),
                    onPhase = { step(it) },
                    onProgress = { done, total ->
                        if (done == 0) step("workouts")
                        update {
                            it.copy(
                                percent = if (total > 0) done * 100 / total else 100,
                                count = "$done / $total",
                            )
                        }
                    },
                )
                val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))
                val summary = result.fold(
                    onSuccess = { fetched ->
                        HuaweiWorkoutStore.Kind.entries.joinToString(" · ") { kind ->
                            "${HuaweiText.titleFor(kind)[lang]} ${fetched.count { kind.matches(it.summary.type) }}"
                        }
                    },
                    onFailure = { it.message ?: it::class.java.simpleName },
                )
                scope?.launch { finish(summary) }
            }
        }
    }

    /** What 同期状態 decided, said in the dialog too — the task itself only raises or clears a notification. */
    private fun syncStateReadout(): String {
        val g = PersistentGlobalScope.snapshotAll()
        val last = g["HUAWEI_LastSuccess"].orEmpty()
        if (last.isBlank()) return Loc("No sync has ever succeeded.", "一度も同期に成功していません。")[
            BandLanguage.parse(HuaweiSettings.language(applicationContext))]
        val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))
        return listOfNotNull(
            Loc("Last success %s", "最後の成功 %s")[lang].format(last),
            g["HUAWEI_AgeHours"]?.takeIf { it.isNotBlank() }?.let { Loc("%s h ago", "%s 時間前")[lang].format(it) },
            g["Huawei_WarnAfterHours"]?.takeIf { it.isNotBlank() && !it.startsWith("%") }
                ?.let { Loc("warns after %s h", "警告 %s 時間後")[lang].format(it) },
            g["HUAWEI_BatteryPct"]?.takeIf { it.isNotBlank() }?.let { Loc("battery %s %%", "電池 %s %%")[lang].format(it) },
            g["HUAWEI_SyncCount"]?.takeIf { it.isNotBlank() }?.let { Loc("%s syncs", "同期 %s 回")[lang].format(it) },
        ).joinToString(" · ")
    }

    /**
     * Follow the runner's own progress flow while a sync task runs.
     *
     * Held as a job, and there is only ever one. A watcher with no handle on it is a watcher nothing
     * can stop: the old poll loop was `while (true)` writing into the dialog's state, which made
     * closing the dialog impossible — OK cleared it, the next tick wrote it back.
     *
     * Only emissions from a run that began after [since] count: until the runner calls `begin`, the
     * flow still holds the previous sync's finished state, and showing that as this run's progress
     * would be the same lie as the old "starting · 0 %".
     */
    private var syncWatch: kotlinx.coroutines.Job? = null

    private val REPEATING_PHASES = setOf("counting", "reading")

    private fun watchSync(
        since: Long,
        onStep: (phase: String, message: String) -> Unit,
        onCount: (percent: Int?, count: String) -> Unit,
    ) {
        stopWatchingSync()
        syncWatch = scope?.launch {
            HuaweiSyncState.progress.collect { p ->
                if (!p.running || p.startedAtMillis < since - 1_000) return@collect
                onStep(p.phase, p.message)
                val reading = p.phase == "reading" && p.recordCount > 0
                val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))
                onCount(
                    if (reading) p.percent else null,
                    listOfNotNull(
                        p.windowCount.takeIf { it > 1 && p.windowIndex > 0 }
                            ?.let { Loc("window %d / %d", "窓 %d / %d")[lang].format(p.windowIndex, it) },
                        if (reading) Loc("record %d / %d", "記録 %d / %d")[lang].format(p.recordIndex, p.recordCount) else null,
                        p.samples.takeIf { it > 0 }?.let { Loc("%d samples", "標本 %d")[lang].format(it) },
                    ).joinToString(" · "),
                )
            }
        }
    }

    private fun stopWatchingSync() {
        syncWatch?.cancel()
        syncWatch = null
    }

    /** Nothing may outlive the window that owns it. */
    override fun onDestroy() {
        stopWatchingSync()
        super.onDestroy()
    }

    /**
     * One face's own preview, out of the archive that holds it.
     *
     * Matched by name rather than by asset id: the library is 白い熊's, its files are named the way he
     * named them, and an id would break the moment a face is re-captured. Which face is 白い熊's
     * choice — the earth first, the clownfish since (2026-08-28) — so it is one constant, not a
     * search for something that merely looks nice.
     */
    private fun faceArt(): ByteArray? = runCatching {
        val dir = File(HuaweiFacesActivity.DEFAULT_DIR)
        val entry = HuaweiFaceLibrary.list(dir).firstOrNull {
            it.name.contains(FACE_ON_THE_CARD, ignoreCase = true)
        } ?: return null
        square(HuaweiFaceLibrary.preview(entry.zip) ?: return null)
    }.getOrNull()

    /**
     * The TOP square of a picture, not the middle one.
     *
     * A watch face is a tall rectangle and the card is a wide one, so the whole face can only be
     * shown in bars of dead space or stretched out of shape. Which square to keep is not arbitrary:
     * these faces put the art at the top and the readouts below it, so the TOP square is the reef
     * and the fish with the time along its bottom edge, while a centred one cuts the art in half and
     * fills the space it gains with the steps-and-battery grid — data, not a picture.
     *
     * Measured on the Clownfish face 白い熊 chose (290 x 494): a 290-square from the top ends at
     * y=290, and the "10:08" sits at y=228..288 — cut, as he put it, right under the time.
     */
    private fun square(png: ByteArray): ByteArray? = runCatching {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val side = minOf(src.width, src.height)
        val cut = Bitmap.createBitmap(src, (src.width - side) / 2, 0, side, side)
        java.io.ByteArrayOutputStream().use { out ->
            cut.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * A piece of a real base map — the middle of one, where the walking is.
     *
     * Cropped rather than scaled: a cutout is five tiles by five and mostly ground, and a whole one
     * shrunk into a 4:3 card is a green rectangle. The centre is where the streets 白い熊 actually
     * walks are.
     *
     * Any cutout will do. They are shared by area rather than kept per walk, so the first one is a
     * picture of the neighbourhood, which is exactly what the card wants to be.
     */
    private suspend fun walkCutout(): ByteArray? = runCatching {
        val dao = OpenTaskerApp_NoHilt.db.huaweiWorkoutDao()
        val key = dao.cutoutKeys().firstOrNull() ?: return null
        val png = HuaweiWorkoutStore.cutout(dao, key) ?: return null
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val w = src.width
        val h = (w * 3f / 4f).toInt().coerceAtMost(src.height)
        val crop = Bitmap.createBitmap(src, 0, ((src.height - h) / 2).coerceAtLeast(0), w, h)
        java.io.ByteArrayOutputStream().use { out ->
            crop.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        /** The face whose picture stands for the whole library on the board. */
        const val FACE_ON_THE_CARD = "Clownfish"

        /** How far back the dialog's workout fetch asks — the same 30 days the walks window lists. */
        const val WORKOUT_DAYS = 30

        fun open(context: Context) {
            context.startActivity(
                Intent(context, HuaweiBoardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }
}
