package com.opentasker.ui.charts.huawei

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.core.huawei.HuaweiSyncEngine
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.huawei.HuaweiGpsTrack
import com.opentasker.core.huawei.HuaweiWorkoutImport
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.core.huawei.maps.WalkPlot
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「運動（Huawei）」 — the walks, in their own window.
 *
 * A window rather than a dialog for the same reason the watch-face picker is one: asking the band
 * for walks runs for tens of seconds over Bluetooth, and the grid has to still be there afterwards.
 *
 * Work runs on `HuaweiSyncRunner.scope` rather than the composition's, so closing the window
 * mid-download does not abandon a half-written walk.
 */
/** Everything one load produces, so the IO block hands back one value rather than four. */
private data class Loaded(
    val walks: List<HuaweiWorkoutStore.Workout>,
    val efforts: Map<String, HuaweiWorkoutStore.Effort>,
    val plots: Map<String, WalkPlot>,
    val bases: Map<String, ImageBitmap>,
)

/** Everything this app writes for 白い熊 lands here — the probe, the exports, the bundles. */
private const val EXPORT_DIR = "/sdcard/tmp"

/**
 * Marks the busy state as "asking 地図 for an area", not "sharing a walk".
 *
 * Both use the same field because both mean the same thing to every button on the screen — one
 * 地図 round trip is in flight — but the cells need to tell them apart to say which is happening.
 */
private const val CUTOUT = "cutout:"

class HuaweiWalksActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        renderFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFrom(intent)
    }

    private fun renderFrom(intent: Intent?) {
        val days = intent?.getIntExtra(EXTRA_DAYS, 0)?.takeIf { it > 0 } ?: 7
        // Which half of the library this window is for. One activity rather than two, because a
        // lift and a walk differ in what is drawn, not in how they are fetched, listed, opened,
        // annotated or stored — and a second copy of this file would have to be kept in step with
        // the first for ever.
        val strength = intent?.getBooleanExtra(EXTRA_STRENGTH, false) ?: false
        val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))

        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            val chartStyle = remember(themePrefs) { ChartStyle.from(themePrefs) }
            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(
                    LocalBandLanguage provides lang,
                    LocalChartStyle provides chartStyle,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        var state by remember { mutableStateOf(HuaweiWalksState(strength = strength)) }
                        var open by remember { mutableStateOf<String?>(null) }
                        val scope = rememberCoroutineScope()
                        val dao = remember { OpenTaskerApp_NoHilt.db.huaweiWorkoutDao() }

                        /**
                         * Load the list AND everything it draws, in one pass off the main thread.
                         *
                         * The efforts, the routes and the cutout bitmaps are resolved here rather
                         * than by the cells. Two reasons, and the second is the load-bearing one:
                         * a blob read per cell per recomposition is waste, and an ASYNCHRONOUS read
                         * inside a cell never runs under the screenshot engine, so every preview of
                         * this grid would draw empty and stop being evidence of anything.
                         */
                        suspend fun reload() {
                            val loaded = withContext(Dispatchers.IO) {
                                // Filtered here rather than in the store: the store holds every
                                // workout the band recorded, and which of them a window shows is
                                // the window's business.
                                val walks = HuaweiWorkoutStore.ofKind(dao, strength)
                                val efforts = walks.mapNotNull { w ->
                                    HuaweiWorkoutStore.effortOf(dao, w)?.let { w.id to it }
                                }.toMap()
                                val keys = dao.cutoutKeys()
                                val plots = walks.filter { it.hasTrack }.mapNotNull { w ->
                                    val raw = HuaweiWorkoutStore.trackOf(dao, w) ?: return@mapNotNull null
                                    val track = HuaweiGpsTrack.decode(raw) ?: return@mapNotNull null
                                    val points = track.points.map { p -> p.latitude to p.longitude }
                                    w.id to com.opentasker.core.huawei.maps.WalkTrack.plot(points, keys)
                                }.toMap()
                                // One decode per cutout, not per walk: a neighbourhood's worth of
                                // walking shares one megabyte of picture, and decoding it forty
                                // times is forty megabytes of bitmap for one image.
                                val bases = plots.values.mapNotNull { it.cutout?.id }.distinct()
                                    .mapNotNull { key ->
                                        dao.cutout(key)?.let { png ->
                                            BitmapFactory.decodeByteArray(png, 0, png.size)
                                                ?.asImageBitmap()?.let { key to it }
                                        }
                                    }.toMap()
                                Loaded(walks, efforts, plots, bases)
                            }
                            state = state.copy(
                                walks = loaded.walks, efforts = loaded.efforts,
                                plots = loaded.plots, bases = loaded.bases, loading = false,
                            )
                        }

                        /**
                         * Run one 地図 round trip and fold the answer back into the screen.
                         *
                         * On [HuaweiSyncRunner.scope] rather than the composition's: the render can
                         * take a minute or two, and closing the window must not abandon a walk
                         * halfway into another app's library.
                         */
                        fun run(walk: HuaweiWorkoutStore.Workout, op: suspend (HuaweiWorkoutStore.Workout) -> HuaweiChizu.Outcome) {
                            HuaweiSyncRunner.scope.launch {
                                val outcome = op(walk)
                                scope.launch {
                                    state = state.copy(sharing = null, message = outcome.message)
                                    reload()
                                }
                            }
                        }

                        /**
                         * Areas we have already asked 地図 about in this window, successfully or not.
                         *
                         * Without it a neighbourhood 地図 has no data for would be re-requested on
                         * every reload, for ever, each one a round trip that can run for minutes.
                         * One attempt per area per window: reopening the window is the retry.
                         */
                        var askedFor by remember { mutableStateOf(emptySet<String>()) }

                        /**
                         * A walk with no base map ASKS FOR ONE. It does not report its absence.
                         *
                         * 白い熊, 2026-09-04: *"If no map — it shouldn't display no map, it should
                         * request from chizu. This must be automatic in the app — I pull a new walk,
                         * no map exists for it, it must automatically request from chizu."* The old
                         * text read as the feature being broken, which is exactly what it was: a
                         * screen that knew what was missing, knew who to ask, and waited to be told.
                         *
                         * **One request per AREA, not per walk.** A cutout is five tiles by five and
                         * every walk that crosses it draws over the same one — ten walks from the
                         * same door need a single fetch. The plots are keyed by walk and the fetches
                         * by cutout id, and that difference is what makes this cheap rather than a
                         * storm of near-identical requests.
                         *
                         * **Serial, one at a time.** 地図 renders under a single shared rasterizer,
                         * so concurrent requests do not run in parallel — they queue, each with its
                         * own clock already running. Firing six at once is the surest way to make
                         * this fail for a reason that has nothing to do with the contract.
                         */
                        LaunchedEffect(state.plots, state.bases, askedFor, state.busy) {
                            if (state.busy || state.loading) return@LaunchedEffect
                            // `plot.cutout` is the one that ALREADY COVERS this walk, so it is
                            // null in exactly the case we are here to fix. Reading the wanted id
                            // from it asked for nothing, for every walk, while every cell said it
                            // was asking — found by looking at the screen rather than at the code.
                            // What is wanted is the cutout that WOULD cover the box, which is the
                            // same call the manual button makes.
                            val next = state.plots.values
                                .mapNotNull { plot ->
                                    if (plot.cutout != null) return@mapNotNull null
                                    plot.box?.let { box ->
                                        com.opentasker.core.huawei.maps.MapCutouts.needed(
                                            box,
                                            com.opentasker.core.huawei.maps.WalkTrack.zoomFor(box),
                                        )
                                    }
                                }
                                .distinctBy { it.id }
                                .firstOrNull { it.id !in state.bases && it.id !in askedFor }
                                ?: return@LaunchedEffect
                            val cutout = next
                            askedFor = askedFor + next.id
                            state = state.copy(sharing = CUTOUT + next.id, message = null)
                            HuaweiSyncRunner.scope.launch {
                                val outcome = withContext(Dispatchers.IO) {
                                    HuaweiChizu.basemap(applicationContext, cutout, dao, lang)
                                }
                                scope.launch {
                                    // A failure is worth saying once. A success needs no words —
                                    // the picture appearing IS the message.
                                    state = state.copy(
                                        sharing = null,
                                        message = if (outcome.ok) null else outcome.message,
                                    )
                                    reload()
                                }
                            }
                        }

                        // The one-time move of the old on-disk archive into the database runs
                        // here, before the first list: it is where a workout window is opened, and
                        // it must not need 白い熊 to press anything for their history to survive.
                        LaunchedEffect(strength) {
                            withContext(Dispatchers.IO) {
                                HuaweiWorkoutImport.runOnce(
                                    applicationContext, dao, File(HuaweiWorkoutImport.LEGACY_DIR),
                                )
                            }
                            reload()
                        }

                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val opened = state.walks.firstOrNull { it.id == open }

                        // Read once per walk opened, off the main thread. The window is the band's
                        // own start..end; a walk whose sensor was off simply has no rows, and the
                        // card then shows nothing rather than an average over an empty set.
                        LaunchedEffect(open) {
                            val walk = state.walks.firstOrNull { it.id == open }
                            state = state.copy(
                                heart = walk?.let {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            OpenTaskerApp_NoHilt.db.huaweiSampleDao().statsFor(
                                                HuaweiSyncEngine.METRIC_HEART_RATE,
                                                it.startSeconds,
                                                it.endSeconds ?: (it.startSeconds + 6 * 3600),
                                            )
                                        }.getOrNull()
                                    }
                                }?.takeIf { it.n > 0 },
                            )
                        }

                        if (opened != null) {
                            HuaweiWalkDetailScreen(
                                walk = opened,
                                sharing = state.sharing == opened.id,
                                busy = state.busy,
                                message = state.message,
                                heart = state.heart,
                                contentPadding = insets,
                                effort = state.efforts[opened.id],
                                plot = state.plots[opened.id],
                                base = state.plots[opened.id]?.cutout?.id?.let { state.bases[it] },
                                exported = state.exported,
                                onShare = {
                                    state = state.copy(sharing = opened.id, message = null)
                                    run(opened) { w ->
                                        // Regenerated for the hand-over and thrown away after it.
                                        val gpx = HuaweiWorkoutStore.gpxOf(
                                            dao, w, "${w.kind} ${walkWhen(w)}",
                                        ).orEmpty()
                                        HuaweiChizu.share(applicationContext, w, gpx, dao, lang)
                                    }
                                },
                                onOpenInChizu = {
                                    state = state.copy(sharing = opened.id, message = null)
                                    run(opened) { HuaweiChizu.show(applicationContext, it) }
                                },
                                onBack = { open = null },
                                // On the runner's scope, like every other write here: the file is
                                // small and the write is milliseconds, but a note lost because the
                                // window was closed as it was saved would be a note 白い熊 believes
                                // is on file. The reload afterwards is what puts it back on screen.
                                onAnnotate = { note, stops ->
                                    HuaweiSyncRunner.scope.launch {
                                        withContext(Dispatchers.IO) {
                                            HuaweiWorkoutStore.annotate(dao, opened, note, stops)
                                        }
                                        scope.launch { reload() }
                                    }
                                },
                                // Both write to /sdcard/tmp, where everything this app hands 白い熊
                                // goes, and both report the path: a file nobody can find has not
                                // been exported. On the runner's scope, so closing the window
                                // mid-write does not abandon a half-written file.
                                onExportHeart = {
                                    HuaweiSyncRunner.scope.launch {
                                        val written = withContext(Dispatchers.IO) {
                                            state.efforts[opened.id]?.let { e ->
                                                HuaweiWorkoutStore.exportHeart(opened, e, File(EXPORT_DIR))
                                            }
                                        }
                                        scope.launch {
                                            state = state.copy(
                                                exported = written?.absolutePath
                                                    ?: HuaweiText.walksExportFailed[lang],
                                            )
                                        }
                                    }
                                },
                                onExportGpx = {
                                    HuaweiSyncRunner.scope.launch {
                                        val written = withContext(Dispatchers.IO) {
                                            HuaweiWorkoutStore.exportGpx(dao, opened, File(EXPORT_DIR))
                                        }
                                        scope.launch {
                                            state = state.copy(
                                                exported = written?.absolutePath
                                                    ?: HuaweiText.walksExportFailed[lang],
                                            )
                                        }
                                    }
                                },
                                onFetchMap = {
                                    if (state.busy) return@HuaweiWalkDetailScreen
                                    state = state.copy(sharing = opened.id, message = null)
                                    HuaweiSyncRunner.scope.launch {
                                        // Resolve the cutout from the track itself, so what is
                                        // asked for is exactly what the drawing will look for.
                                        val outcome = withContext(Dispatchers.IO) {
                                            val box = state.plots[opened.id]?.box
                                            if (box == null) {
                                                HuaweiChizu.Outcome(false, "this walk has no usable points")
                                            } else {
                                                val want = com.opentasker.core.huawei.maps.MapCutouts
                                                    .needed(
                                                        box,
                                                        com.opentasker.core.huawei.maps.WalkTrack
                                                            .zoomFor(box),
                                                    )
                                                HuaweiChizu.basemap(applicationContext, want, dao, lang)
                                            }
                                        }
                                        scope.launch {
                                            state = state.copy(sharing = null, message = outcome.message)
                                            reload()
                                        }
                                    }
                                },
                            )
                        } else {
                            HuaweiWalksScreen(
                                state = state,
                                contentPadding = insets,
                                onDownload = {
                                    if (state.busy) return@HuaweiWalksScreen
                                    state = state.copy(downloading = true, message = null)
                                    HuaweiSyncRunner.scope.launch {
                                        val now = System.currentTimeMillis() / 1000
                                        val result = HuaweiSyncRunner.fetchWorkouts(
                                            applicationContext,
                                            HuaweiSettings.address(applicationContext),
                                            now - days * 86_400L,
                                            now,
                                            dao,
                                        )
                                        val note = result.fold(
                                            onSuccess = { fetched ->
                                                // The band is asked for the whole window in one
                                                // session — a lift and a walk cost the same round
                                                // trips and there is no filter to send it — so
                                                // both windows fetch everything and each reports
                                                // only its own. Anything else would tell 白い熊
                                                // that 「重量挙げ」 had just downloaded nine walks.
                                                val walks = fetched.filter { it.summary.isStrength == strength }
                                                // Not `kind` per walk: every one of them is a
                                                // "walk", so that printed "walk · walk · walk · …"
                                                // and told 白い熊 nothing (2026-08-30). What is
                                                // actually wanted is how many arrived and when.
                                                if (walks.isEmpty()) {
                                                    (if (strength) HuaweiText.liftNoneFound
                                                    else HuaweiText.walksNoneFound)[lang]
                                                }
                                                else {
                                                    val fmt = java.text.SimpleDateFormat(
                                                        "MM-dd HH:mm", java.util.Locale.US,
                                                    )
                                                    val when_ = walks
                                                        .mapNotNull { it.summary.startSeconds }
                                                        .sorted()
                                                        .map { fmt.format(java.util.Date(it * 1000)) }
                                                    "${walks.size}: ${when_.joinToString(" · ")}"
                                                }
                                            },
                                            onFailure = { it.message ?: "failed" },
                                        )
                                        scope.launch {
                                            state = state.copy(downloading = false, message = note)
                                            reload()
                                        }
                                    }
                                },
                                onShare = { walk ->
                                    if (state.busy) return@HuaweiWalksScreen
                                    state = state.copy(sharing = walk.id, message = null)
                                    run(walk) { w ->
                                        val gpx = HuaweiWorkoutStore.gpxOf(
                                            dao, w, "${w.kind} ${walkWhen(w)}",
                                        ).orEmpty()
                                        HuaweiChizu.share(applicationContext, w, gpx, dao, lang)
                                    }
                                },
                                onOpenInChizu = { walk ->
                                    if (state.busy) return@HuaweiWalksScreen
                                    state = state.copy(sharing = walk.id, message = null)
                                    run(walk) { HuaweiChizu.show(applicationContext, it) }
                                },
                                onOpen = { open = it.id },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DAYS = "shiroikuma.jiyusagyoban.extra.HUAWEI_WALK_DAYS"
        const val EXTRA_STRENGTH = "shiroikuma.jiyusagyoban.extra.HUAWEI_STRENGTH"

        fun open(context: Context, days: Int?, strength: Boolean = false) {
            context.startActivity(
                Intent(context, HuaweiWalksActivity::class.java).apply {
                    // CLEAR_TOP with two modes would hand 「重量挙げ」 the walks window that is
                    // already open and re-render it as itself; the intent carries the mode and
                    // onNewIntent re-reads it, so the same instance serves both.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    days?.takeIf { it > 0 }?.let { putExtra(EXTRA_DAYS, it) }
                    if (strength) putExtra(EXTRA_STRENGTH, true)
                },
            )
        }
    }
}
