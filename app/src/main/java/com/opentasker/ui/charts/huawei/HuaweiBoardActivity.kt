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
import com.opentasker.core.huawei.HuaweiWalkLibrary
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
                                    state = state.copy(sync = BoardSync(running = true))
                                    runTask(tile.task) {
                                        // Stop the watcher BEFORE publishing the result, not after:
                                        // both run on this scope, so a tick still queued would
                                        // otherwise land on top of the finished state and put the
                                        // dialog back to "running" for good.
                                        stopWatchingSync()
                                        state = state.copy(sync = readSync(false))
                                    }
                                    watchSync { state = state.copy(sync = it) }
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
                            onSyncAction = { task ->
                                state = state.copy(sync = BoardSync(running = true))
                                runTask(task) {
                                    stopWatchingSync()
                                    state = state.copy(sync = readSync(false))
                                }
                                watchSync { state = state.copy(sync = it) }
                            },
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
                        "The band did not take that language${why?.let { " — $it" }.orEmpty()}",
                        "バンドはその言語を受け付けませんでした${why?.let { "— $it" }.orEmpty()}",
                    )
                },
            )
            scope?.launch { onDone(next) }
        }
    }

    /** The sync's own published progress, read straight off the globals it writes as it goes. */
    private fun readSync(running: Boolean): BoardSync {
        val g = PersistentGlobalScope.snapshotAll()
        return BoardSync(
            phase = g["HUAWEI_Phase"].orEmpty(),
            pct = g["HUAWEI_Pct"].orEmpty(),
            records = g["HUAWEI_Records"].orEmpty(),
            summary = g["HUAWEI_Summary"].orEmpty(),
            running = running,
        )
    }

    /**
     * Poll while it runs. The globals change from another thread; the dialog wants to see them.
     *
     * Held as a job, and there is only ever one. A poll loop with no handle on it is a loop nothing
     * can stop: this was `while (true)` with its result thrown at the dialog's state, which made
     * closing the dialog impossible — OK cleared it, the next tick wrote it back — and left the
     * board polling for as long as it was open, whether or not anything was still syncing.
     */
    private var syncWatch: kotlinx.coroutines.Job? = null

    private fun watchSync(onTick: (BoardSync) -> Unit) {
        stopWatchingSync()
        syncWatch = scope?.launch {
            // isActive, not true: cancelling the job has to end the loop, and a `while (true)` only
            // stops at the next suspension point by luck of where it is standing.
            while (isActive) {
                delay(700)
                onTick(readSync(true))
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
     * A piece of a real walk's map — the middle of it, where the route is.
     *
     * Cropped rather than scaled: the maps are tall and mostly ground, and a whole one shrunk into a
     * 4:3 card is a green rectangle. The centre crop is where 地図 draws the track.
     */
    private fun walkCutout(): ByteArray? = runCatching {
        val walk = HuaweiWalkLibrary.list(File(HuaweiWalkLibrary.DEFAULT_DIR))
            .firstOrNull { it.hasMap && !it.mapIsBlank } ?: return null
        val src = BitmapFactory.decodeFile(walk.mapPath ?: return null) ?: return null
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

        fun open(context: Context) {
            context.startActivity(
                Intent(context, HuaweiBoardActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }
}
