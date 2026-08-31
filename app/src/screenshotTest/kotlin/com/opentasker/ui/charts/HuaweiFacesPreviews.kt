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
import com.opentasker.core.huawei.HuaweiUploadClient
import com.opentasker.ui.charts.huawei.HuaweiFacesState
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.ByteArrayOutputStream
import java.io.File
import com.opentasker.ui.charts.huawei.IdentifyState
import com.opentasker.ui.charts.huawei.RoomRequest

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

/**
 * The band read: two of the library's faces are on it, alongside two this library has no copy of.
 * One of ours is the face on screen. "On the band" is carried by a tick and a word, never by the
 * accent colour alone, and every held face offers removal — the band decides what it will part with.
 */
private val BAND = HuaweiUploadClient.FaceStore(
    faces = listOf(
        HuaweiUploadClient.InstalledFace("2182762613", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("2182762133", "2.9.8", showing = false),
        HuaweiUploadClient.InstalledFace("7185695173", "2.1.1", showing = true),
        HuaweiUploadClient.InstalledFace("7185922173", "2.1.1", showing = false),
    ),
    freeUnits = 95,
)

@PreviewTest
@Preview(name = "Faces — band read", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesBandReadPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(HuaweiFacesState(faces = FACES, loading = false, band = BAND))
    }
}

/** A removal running: every button in the grid is inert, because the band allows one session. */
@PreviewTest
@Preview(name = "Faces — removing 日本語", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesRemovingPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame(
            HuaweiFacesState(
                faces = FACES,
                loading = false,
                band = BAND,
                deleting = "7185695173",
            ),
        )
    }
}

/**
 * The band full, and the question that follows.
 *
 * Worth a preview of its own because it is the one screen here that 白い熊 meets at a bad moment —
 * an install that will not go — and a dialog that reads badly then is worse than no dialog. The
 * band's own faces come back as ten-digit asset ids; the library is what turns them into names, and
 * the one it holds no copy of has to say so rather than showing a bare number.
 */
private val FULL_BAND = HuaweiUploadClient.FaceStore(
    // EIGHTEEN, because that is what "full" is on 白い熊's band. The first version of this fixture
    // held four, and four faces fit — so the preview showed a tidy dialog for years of looking at
    // it while the real one squeezed its overflow into the last row and could not be scrolled
    // (白い熊, 2026-08-28). A fixture for a full band that is not full tests nothing: the count IS
    // the condition under test.
    faces = listOf(
        HuaweiUploadClient.InstalledFace("2182762613", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("2182762133", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("2183834133", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("2182955593", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("2182951513", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("2182951433", "2.9.5", showing = false),
        HuaweiUploadClient.InstalledFace("7185922173", "2.1.1", showing = false),
        HuaweiUploadClient.InstalledFace("7185695173", "2.1.1", showing = true),
        HuaweiUploadClient.InstalledFace("7185780633", "2.1.1", showing = false),
        HuaweiUploadClient.InstalledFace("7185111222", "2.1.1", showing = false),
        HuaweiUploadClient.InstalledFace("9900112233", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112244", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112255", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112266", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112277", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112288", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112299", "1.0.0", showing = false),
        HuaweiUploadClient.InstalledFace("9900112211", "1.0.0", showing = false),
    ),
    // 85, not 0 — the band reports free units while refusing the next face, which is why the
    // free-slot pre-check never fires on it. See HuaweiWatchFaceStallTest.
    freeUnits = 85,
)

@PreviewTest
@Preview(name = "Faces — band full", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesRoomNeededPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES,
                loading = false,
                band = FULL_BAND,
                roomNeeded = RoomRequest(FACES[2], FULL_BAND.faces),
            ),
        )
    }
}

/**
 * The naming session, with the band showing a face this library has no copy of.
 *
 * This is the state the whole feature exists for: a bare ten-digit id, and a field to turn it into
 * something 白い熊 will recognise when the picker asks which face may go.
 */
@PreviewTest
@Preview(name = "Faces — naming", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesIdentifyPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES, loading = false, band = FULL_BAND,
                names = mapOf("9900112233" to "the orange dial"),
                identify = IdentifyState(
                    running = true,
                    showing = HuaweiUploadClient.InstalledFace("2182951513", "3.0.2", showing = true),
                ),
            ),
        )
    }
}

/**
 * The same dialog a moment after Save was pressed.
 *
 * This preview exists because the press used to look like nothing: the name was written and shown
 * on the screen BEHIND the dialog, which is not where anyone is looking. The button reading "Saved"
 * and refusing a second press, plus the name stated underneath, is the whole fix — so it is what
 * gets checked.
 */
@PreviewTest
@Preview(name = "Faces — naming, saved", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesIdentifySavedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES, loading = false, band = FULL_BAND,
                names = mapOf("2182951513" to "Stock rainbow"),
                identify = IdentifyState(
                    running = true,
                    showing = HuaweiUploadClient.InstalledFace("2182951513", "3.0.2", showing = true),
                ),
            ),
        )
    }
}

/**
 * The band card with its strangers listed one per row, each with its own Remove.
 *
 * Two are named and eleven are not — which is the state 白い熊 is actually in, and the reason the
 * run-on line it replaced was no use: reading which faces are there is not the same as being able
 * to do anything about them.
 */
@PreviewTest
@Preview(name = "Faces — the band's own faces", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesStrangersPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES, loading = false, band = FULL_BAND,
                names = mapOf(
                    FULL_BAND.faces[0].assetId to "Stock rainbow",
                    FULL_BAND.faces[1].assetId to "Stock big digits",
                ),
            ),
        )
    }
}

/** The one guard: removing a face this library cannot put back. */
@PreviewTest
@Preview(name = "Faces — confirm removal", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesConfirmRemovePreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES, loading = false, band = FULL_BAND,
                confirmRemove = FULL_BAND.faces[3],
            ),
        )
    }
}

/** The picker again, now that two of the strangers have been named. */
@PreviewTest
@Preview(name = "Faces — band full, named", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesRoomNamedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES, loading = false, band = FULL_BAND,
                names = mapOf(
                    FULL_BAND.faces[0].assetId to "stock — the blue one",
                    FULL_BAND.faces[1].assetId to "stock — big digits",
                ),
                roomNeeded = RoomRequest(FACES[2], FULL_BAND.faces),
            ),
        )
    }
}

@PreviewTest
@Preview(name = "Faces — band full 日本語", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesRoomNeededJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame(
            HuaweiFacesState(
                faces = FACES,
                loading = false,
                band = FULL_BAND,
                roomNeeded = RoomRequest(FACES[2], FULL_BAND.faces),
            ),
        )
    }
}

/**
 * A face on the band but not on screen: the only state where "Show on band" appears.
 *
 * The face already showing must NOT offer it — a button that does nothing is worse than no button —
 * so this preview is the check that the two cells differ.
 */
@PreviewTest
@Preview(name = "Faces — activating", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiFacesActivatingPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiFacesState(
                faces = FACES,
                loading = false,
                band = BAND,
                activating = "7185922173",
            ),
        )
    }
}

