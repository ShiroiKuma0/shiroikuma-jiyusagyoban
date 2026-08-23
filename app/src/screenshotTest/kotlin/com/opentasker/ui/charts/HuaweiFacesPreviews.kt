package com.opentasker.ui.charts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.huawei.HuaweiFaceLibrary
import com.opentasker.ui.charts.huawei.HuaweiFacesScreen
import com.opentasker.ui.charts.huawei.HuaweiFacesState
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The watch-face picker, rendered.
 *
 * 白い熊's phone is normally locked, so `adb shell screencap` returns the keyguard and this layout
 * cannot be looked at any other way. What is worth checking here is what a real library will stress:
 * a long name wrapping, the band's tall 286×482 aspect in a grid cell, and the moment when one face
 * is installing and every other button must be dead.
 *
 * The thumbnails are drawn rather than read from disk — a preview has no archive to open — via the
 * `previewOf` seam the screen exposes for exactly this.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */

private fun swatch(r: Int, g: Int, b: Int): ByteArray {
    val bmp = Bitmap.createBitmap(143, 241, Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply {
        drawColor(android.graphics.Color.rgb(r, g, b))
        drawCircle(71f, 90f, 46f, Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true })
    }
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

private val FACES = listOf(
    HuaweiFaceLibrary.Entry(File("/faces/Aurora.zip"), "Aurora", "7185695173", "2.1.1"),
    HuaweiFaceLibrary.Entry(File("/faces/Deep Space Chronograph.zip"), "Deep Space Chronograph", "7185922173", "2.1.1"),
    HuaweiFaceLibrary.Entry(File("/faces/Kanji.zip"), "漢字文字盤", "7185780633", "2.1.1"),
    HuaweiFaceLibrary.Entry(File("/faces/Minimal.zip"), "Minimal", "7185111222", "2.1.1"),
)

private val TINTS = mapOf(
    "7185695173" to Triple(30, 90, 160),
    "7185922173" to Triple(20, 20, 28),
    "7185780633" to Triple(120, 30, 40),
)

private fun previewFor(e: HuaweiFaceLibrary.Entry): ByteArray? =
    TINTS[e.assetId]?.let { (r, g, b) -> swatch(r, g, b) }   // "Minimal" has none, on purpose

@Composable
private fun Frame(state: HuaweiFacesState) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HuaweiFacesScreen(
                state = state,
                contentPadding = PaddingValues(10.dp),
                previewOf = ::previewFor,
                onInstall = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Faces — library", widthDp = 413, heightDp = 780, showBackground = true)
@Composable
fun HuaweiFacesLibraryPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(HuaweiFacesState(faces = FACES, loading = false))
    }
}

/** One face going, and every other button dead — the band takes one at a time. */
@PreviewTest
@Preview(name = "Faces — installing", widthDp = 413, heightDp = 780, showBackground = true)
@Composable
fun HuaweiFacesInstallingPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES,
                loading = false,
                installing = "7185695173_2.1.1",
                bytesSent = 421_888,
            ),
        )
    }
}

@PreviewTest
@Preview(name = "Faces — empty 日本語", widthDp = 413, heightDp = 420, showBackground = true)
@Composable
fun HuaweiFacesEmptyPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame(HuaweiFacesState(faces = emptyList(), loading = false, dir = "/sdcard/〇/…/Huawei Band 11 Pro"))
    }
}
