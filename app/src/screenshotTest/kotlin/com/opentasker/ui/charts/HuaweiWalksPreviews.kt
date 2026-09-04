package com.opentasker.ui.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.huawei.HuaweiWalkDetailScreen
import com.opentasker.core.huawei.maps.MapCutouts
import com.opentasker.core.huawei.maps.WalkPlot
import com.opentasker.ui.charts.huawei.HuaweiWalksScreen
import com.opentasker.ui.charts.huawei.HuaweiWalksState
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.File

/**
 * A synthetic walk: a few dozen coordinates around a fixed point, and the cutout that contains them.
 *
 * No PNG is involved. Without a base image [WalkMap.Route] still draws the route — the cutout's
 * identifier alone is enough to project — which is exactly what the grid does before 地図 has been
 * asked for the area, so the preview is also the check that that state looks deliberate.
 */
private fun plotFor(walk: HuaweiWorkoutStore.Workout): WalkPlot? {
    // One walk of each state, deterministically. Left to a modulo of the real timestamps the grid
    // came out showing the same state in three cells of four, which checks a third of the screen.
    val seed = walk.startSeconds.toInt()
    val state = Math.floorMod(walk.startSeconds / 3600L, 3L).toInt()
    if (state == 2) return WalkPlot(emptyList(), null, null)         // the band got no fix
    val lat0 = 50.0755
    val lon0 = 14.4378
    val pts = (0 until 40).map { i ->
        val t = i / 6.0
        (lat0 + 0.004 * kotlin.math.sin(t + seed % 7)) to
            (lon0 + 0.006 * kotlin.math.cos(t * 0.8 + seed % 5))
    }
    val box = MapCutouts.Box.of(pts)!!
    // Every third walk is somewhere with no cutout yet — the invitation state, not an error.
    if (state == 1) return WalkPlot(pts, box, null)
    return WalkPlot(pts, box, MapCutouts.needed(box, 15))
}

/**
 * 「運動（Huawei）」, rendered.
 *
 * 白い熊's phone is normally locked, so `adb shell screencap` returns the keyguard and this layout
 * cannot be looked at any other way.
 *
 * What is worth checking here is the state that will be the ordinary one for a while: **most walks
 * have no map**, because 白い熊 地図's half of the contract is not built yet and, once it is, its
 * pictures come back blank until a regional offline map is downloaded. A grid that reads as broken
 * in that state would be a design failure, so the previews put the mixed case first.
 *
 * The routes are supplied rather than read from disk — a preview has no walks archive and no map
 * cutouts — through the `plotOf` seam the screen exposes for exactly this. Since 2026-08-30 a walk
 * is DRAWN from its coordinates over a shared cutout rather than loaded as a rendered PNG, so what
 * the seam hands over is a track and its cutout, not a picture.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */

/** A stand-in for what 地図 will send back: a route over a plain ground, which is also the fallback. */
private fun route(seed: Int, ground: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply {
        drawColor(ground)
        val path = Path().apply {
            moveTo(60f, 300f)
            var x = 60f
            var y = 300f
            repeat(7) { i ->
                x += 50f
                y -= (20 + ((seed * (i + 3)) % 45)).toFloat()
                lineTo(x, y)
            }
        }
        drawPath(path, Paint().apply {
            color = android.graphics.Color.rgb(255, 214, 0)
            style = Paint.Style.STROKE
            strokeWidth = 7f
            isAntiAlias = true
        })
    }
    return bmp.asImageBitmap()
}

private fun walk(
    number: Int,
    start: Long,
    minutes: Int,
    metres: Int,
    points: Int,
    mapped: Boolean,
    note: String? = null,
    stops: Int? = null,
) = HuaweiWorkoutStore.Workout(
    number = number,
    startSeconds = start,
    endSeconds = start + minutes * 60L,
    distanceMetres = metres,
    kind = "walk",
    trackPoints = points,
    trackId = if (mapped) "自由作業盤/walk_$number.gpx" else null,
    chizu = if (!mapped) null else HuaweiWorkoutStore.ChizuReading(
        distanceMetres = metres + 12.0,
        durationSeconds = minutes * 60L,
        movingSeconds = minutes * 55L,
        // Deliberately 12 s off the band's figure — the real walk's chunk spans summed to 1779 s
        // against the band's 1767 s, and a preview that agreed perfectly would hide the one thing
        // this row exists to show.
        activeSeconds = minutes * 60L + 12,
        climbMetres = 18.0,
        descentMetres = 21.0,
        // walk 8 is 白い熊's real Prague walk, and Prague is in no region 地図 currently holds —
        // so the honest render of it today is the route over nothing.
        detail = if (number == 8) "basemap" else "map",
    ),
    note = note,
    stops = stops,
)

// The real one first: 白い熊's walk of 2026-08-23 — 1763 fixes, 2.34 km, 29 minutes, Prague.
private val WALKS = listOf(
    // Annotated, un-annotated, note-without-count and count-without-note, one of each — the four
    // states the cell's reserved row has to hold WITHOUT the grid going ragged, which is the whole
    // reason that row's height is fixed rather than conditional.
    walk(
        8, 1_787_502_496L, 29, 2_340, 1_763, mapped = true,
        note = "stopped at the bakery, then again on the bridge to watch the boats", stops = 2,
    ),
    walk(7, 1_787_400_000L, 52, 4_180, 3_090, mapped = false),
    walk(6, 1_787_320_000L, 14, 1_020, 812, mapped = true, note = "rain"),
    walk(5, 1_787_210_000L, 71, 5_640, 4_255, mapped = false, stops = 4),
)

private fun previewFor(w: HuaweiWorkoutStore.Workout): ImageBitmap? = when (w.number) {
    8 -> route(3, android.graphics.Color.rgb(232, 232, 226))
    6 -> route(7, android.graphics.Color.rgb(226, 231, 232))
    else -> null
}

/**
 * The routes, resolved the way the window resolves them — up front, and handed to the screen.
 *
 * The seam used to be a `plotOf` function the cell called. It is a map now, because the real window
 * decodes every track once when the list loads: the cells never fetch anything, which is what keeps
 * a preview honest (the screenshot engine renders one frame and runs no effects, so a cell that
 * loaded its own data would draw empty here and prove nothing).
 */
private val PLOTS = WALKS.mapNotNull { w -> plotFor(w)?.let { w.id to it } }.toMap()

@Composable
private fun Grid(state: HuaweiWalksState) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HuaweiWalksScreen(
                state = state.copy(plots = PLOTS),
                contentPadding = PaddingValues(10.dp),
                onDownload = {},
                onShare = {},
                onOpenInChizu = {},
                onOpen = {},
            )
        }
    }
}

/** The ordinary case: some walks drawn, some not. Neither reads as an error. */
@PreviewTest
@Preview(name = "Walks — grid", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiWalksGridPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Grid(HuaweiWalksState(walks = WALKS, loading = false))
    }
}

/** Asking the band: tens of seconds over Bluetooth, and every button must be dead meanwhile. */
@PreviewTest
@Preview(name = "Walks — downloading 日本語", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiWalksDownloadingPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Grid(HuaweiWalksState(walks = WALKS, loading = false, downloading = true))
    }
}

/** Nothing recorded yet — the first thing 白い熊 will see, so it has to say what to do. */
@PreviewTest
@Preview(name = "Walks — empty", widthDp = 413, heightDp = 520, showBackground = true)
@Composable
fun HuaweiWalksEmptyPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Grid(
            HuaweiWalksState(
                walks = emptyList(),
                loading = false,
            ),
        )
    }
}

@Composable
private fun Detail(walk: HuaweiWorkoutStore.Workout, sharing: Boolean = false, message: String? = null) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HuaweiWalkDetailScreen(
                walk = walk,
                sharing = sharing,
                busy = sharing,
                message = message,
                contentPadding = PaddingValues(10.dp),
                onShare = {},
                onOpenInChizu = {},
                onBack = {},
                plot = plotFor(walk),
            )
        }
    }
}

/** A walk 地図 has drawn: the large map, both its buttons, and its own reading of the route. */
@PreviewTest
@Preview(name = "Walk — mapped", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiWalkMappedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { Detail(WALKS[0]) }
}

/**
 * A walk with no map yet — the state every walk starts in, and the one that must not read as a
 * failure. It gets the band's own figures and a single clear invitation.
 */
@PreviewTest
@Preview(name = "Walk — unsent 日本語", widthDp = 413, heightDp = 780, showBackground = true)
@Composable
fun HuaweiWalkUnsentPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Detail(WALKS[1]) }
}

/** 地図 not installed, or its half not built yet. The walk is untouched; only the picture is missing. */
@PreviewTest
@Preview(name = "Walk — 地図 silent", widthDp = 413, heightDp = 780, showBackground = true)
@Composable
fun HuaweiWalkNoAnswerPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Detail(WALKS[3], message = "地図 did not answer")
    }
}

/**
 * The two editors a walk carries, drawn as they open.
 *
 * A dialog is the one part of this feature 白い熊 cannot see any other way — it exists for a moment,
 * over a locked phone, and by the time it could be described it has been dismissed. The stop picker
 * in particular is a colour decision ("black pill, yellow number, yellow border") that has to be
 * looked at rather than read.
 */
@PreviewTest
@Preview(name = "Walk — note editor", widthDp = 413, heightDp = 420, showBackground = true)
@Composable
fun HuaweiWalkNoteDialogPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        OpenTaskerTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                NoteDialog(
                    title = "2026-08-23 (日) 18:28",
                    note = WALKS[0].note,
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "Walk — stops picker 日本語", widthDp = 413, heightDp = 380, showBackground = true)
@Composable
fun HuaweiWalkStopsDialogPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        OpenTaskerTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CountPickerDialog(
                    title = AnnotationText.stopsAsk[BandLanguage.JA],
                    current = 2,
                    range = 0..9,
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
    }
}
