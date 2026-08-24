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
import com.opentasker.core.huawei.HuaweiWalkLibrary
import com.opentasker.ui.charts.huawei.HuaweiWalkDetailScreen
import com.opentasker.ui.charts.huawei.HuaweiWalksScreen
import com.opentasker.ui.charts.huawei.HuaweiWalksState
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.File

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
 * The thumbnails are drawn rather than read from disk — a preview has no walks archive — through the
 * `previewOf` seam the screen exposes for exactly this, the same one the faces picker uses.
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
) = HuaweiWalkLibrary.Walk(
    dir = File("/walks/walk-$number-$start"),
    number = number,
    startSeconds = start,
    endSeconds = start + minutes * 60L,
    distanceMetres = metres,
    kind = "walk",
    points = points,
    thumbPath = if (mapped) "/walks/walk-$number-$start/map-thumb.png" else null,
    mapPath = if (mapped) "/walks/walk-$number-$start/map.png" else null,
    trackId = if (mapped) "自由作業盤/walk_$number.gpx" else null,
    chizu = if (!mapped) null else HuaweiWalkLibrary.ChizuReading(
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
)

// The real one first: 白い熊's walk of 2026-08-23 — 1763 fixes, 2.34 km, 29 minutes, Prague.
private val WALKS = listOf(
    walk(8, 1_787_502_496L, 29, 2_340, 1_763, mapped = true),
    walk(7, 1_787_400_000L, 52, 4_180, 3_090, mapped = false),
    walk(6, 1_787_320_000L, 14, 1_020, 812, mapped = true),
    walk(5, 1_787_210_000L, 71, 5_640, 4_255, mapped = false),
)

private fun previewFor(w: HuaweiWalkLibrary.Walk): ImageBitmap? = when (w.number) {
    8 -> route(3, android.graphics.Color.rgb(232, 232, 226))
    6 -> route(7, android.graphics.Color.rgb(226, 231, 232))
    else -> null
}

@Composable
private fun Grid(state: HuaweiWalksState) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HuaweiWalksScreen(
                state = state,
                contentPadding = PaddingValues(10.dp),
                onDownload = {},
                onShare = {},
                onOpenInChizu = {},
                onOpen = {},
                previewOf = ::previewFor,
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
                dir = "/sdcard/〇/…/Huawei Band 11 Pro/walks",
            ),
        )
    }
}

@Composable
private fun Detail(walk: HuaweiWalkLibrary.Walk, sharing: Boolean = false, message: String? = null) {
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
                previewOf = ::previewFor,
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
