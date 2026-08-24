package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiFaceLibrary
import com.opentasker.core.huawei.HuaweiUploadClient
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the window is doing, so a cell can show it without inventing its own state. */
data class HuaweiFacesState(
    val faces: List<HuaweiFaceLibrary.Entry> = emptyList(),
    val dir: String = "",
    val loading: Boolean = true,
    /** The face being installed, by id. Null when the band is free. */
    val installing: String? = null,
    val bytesSent: Int = 0,
    val message: String? = null,
    /** What the band answered when last asked, or null if it has not been asked. */
    val band: HuaweiUploadClient.FaceStore? = null,
    val reading: Boolean = false,
    /** The face being removed, by asset id. */
    val deleting: String? = null,
) {
    /**
     * Reading, installing and removing all open the one session the band allows, so any of them
     * makes every button in the grid inert. Deriving it here rather than at each call site is what
     * stops a new button being added that forgets one of the three.
     */
    val bandBusy: Boolean get() = installing != null || reading || deleting != null

    /** Whether the band is holding this face — false whenever the band has not been read. */
    fun onBand(assetId: String): Boolean =
        band?.faces?.any { it.assetId == assetId } == true

    /** The face the band is showing right now, if it has been read. */
    fun isShowing(assetId: String): Boolean =
        band?.faces?.any { it.assetId == assetId && it.showing } == true
}

/**
 * The watch-face library: pick one, install it.
 *
 * ## Why every button disables together
 *
 * The band serves a single connection and `HuaweiSessionGuard` enforces one session process-wide, so
 * a second install started while the first is running does not queue — it is refused outright. A
 * grid where the other buttons stayed live would therefore answer a tap with "a sync is already
 * running", which reads as a broken button rather than as a busy band. Disabling all of them says
 * the true thing: the band is occupied.
 */
@Composable
fun HuaweiFacesScreen(
    state: HuaweiFacesState,
    contentPadding: PaddingValues,
    /**
     * Where a cell's picture comes from, for callers not reading a real archive — today the
     * screenshot previews, which are the only way this layout gets looked at before it ships,
     * since the phone is normally locked.
     *
     * Null means the ordinary path: read the ZIP off the main thread. **A supplied one is called
     * synchronously**, and that is the point — the screenshot engine renders a single frame and
     * never runs a `produceState`, so an asynchronous seam drew every cell as "no picture" and the
     * previews silently stopped being evidence of anything.
     */
    previewOf: ((HuaweiFaceLibrary.Entry) -> ByteArray?)? = null,
    onReadBand: () -> Unit = {},
    onRemove: (HuaweiFaceLibrary.Entry) -> Unit = {},
    /** Last so it stays the trailing lambda — installing is what this window is for. */
    onInstall: (HuaweiFaceLibrary.Entry) -> Unit,
) {
    val lang = LocalBandLanguage.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            BandCard(state, onReadBand)
        }
        if (state.message != null || state.faces.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                SectionCard(accent = ChartPalette.AXIS_TEXT) {
                    SectionTitle(HuaweiText.facesTitle[lang], ChartPalette.AXIS_TEXT)
                    BodyText(state.message ?: HuaweiText.facesEmpty[lang])
                    NoteText(state.dir)
                }
            }
        }
        items(state.faces, key = { it.zip.absolutePath }) { face ->
            FaceCell(
                face = face,
                installing = state.installing == face.id,
                removing = state.deleting == face.assetId,
                onBand = state.onBand(face.assetId),
                showing = state.isShowing(face.assetId),
                bandBusy = state.bandBusy,
                sent = state.bytesSent,
                previewOf = previewOf,
                onInstall = { onInstall(face) },
                onRemove = { onRemove(face) },
            )
        }
    }
}

/**
 * What the band is holding, and how much room is left.
 *
 * Reading it is a button rather than something the window does on opening. The band serves one
 * connection: a read that fired automatically would seize it for several seconds every time this
 * window came up, including the times 白い熊 opened it only to install something.
 */
@Composable
private fun BandCard(state: HuaweiFacesState, onReadBand: () -> Unit) {
    val lang = LocalBandLanguage.current
    val band = state.band
    SectionCard(accent = ChartPalette.AXIS_TEXT) {
        SectionTitle(HuaweiText.facesBandTitle[lang], ChartPalette.AXIS_TEXT)
        if (band == null) {
            BodyText(HuaweiText.facesBandNever[lang])
        } else {
            // Split by what this library holds a copy of — a real distinction we can check — rather
            // than by any flag in the record claiming to say where a face came from.
            val mine = band.faces.count { f -> state.faces.any { it.assetId == f.assetId } }
            val other = band.faces.size - mine
            BodyText(
                "${band.faces.size} ${HuaweiText.facesCountUnit[lang]} · " +
                    "$mine ${HuaweiText.facesCountMine[lang]} · " +
                    "$other ${HuaweiText.facesCountOther[lang]}",
            )
            // The band's own free-space figure, in the band's own units. Reported rather than
            // converted: nothing here knows what one unit is, and inventing a megabyte would be
            // inventing a number.
            if (band.freeUnits >= 0) NoteText("${HuaweiText.facesFree[lang]} ${band.freeUnits}")
            // Faces on the wrist that this library has no copy of — the factory ones, and anything
            // installed from elsewhere. Listed because "remove one to make room" is unanswerable
            // when the grid only shows what we happen to hold.
            val strangers = band.faces.filter { f -> state.faces.none { it.assetId == f.assetId } }
            if (strangers.isNotEmpty()) {
                NoteText(
                    HuaweiText.facesUnknownOnBand[lang] + ": " +
                        strangers.joinToString(" · ") { it.assetId + if (it.showing) " ●" else "" },
                )
            }
        }
        Button(
            onClick = onReadBand,
            enabled = !state.bandBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (state.reading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(HuaweiText.facesRead[lang])
            }
        }
    }
}

@Composable
private fun FaceCell(
    face: HuaweiFaceLibrary.Entry,
    installing: Boolean,
    removing: Boolean,
    onBand: Boolean,
    showing: Boolean,
    bandBusy: Boolean,
    sent: Int,
    previewOf: ((HuaweiFaceLibrary.Entry) -> ByteArray?)?,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val accent = if (installing) ChartPalette.HEART_RATE else ChartPalette.AXIS_TEXT
    SectionCard(accent = accent) {
        // The band's own aspect: 286 x 482. Showing the preview in the shape it will actually take
        // is the whole reason to keep a picture at all.
        Box(
            Modifier.fillMaxWidth().aspectRatio(286f / 482f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            fun decode(bytes: ByteArray?): ImageBitmap? =
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            val supplied = previewOf
            val image = if (supplied != null) {
                remember(face.zip.absolutePath) { decode(supplied(face)) }
            } else {
                val bmp by produceState<ImageBitmap?>(null, face.zip.absolutePath) {
                    value = withContext(Dispatchers.IO) { decode(HuaweiFaceLibrary.preview(face.zip)) }
                }
                bmp
            }
            if (image != null) {
                Image(image, face.name, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            } else {
                NoteText(HuaweiText.facesNoPreview[lang])
            }
        }
        Text(
            face.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onInstall,
            enabled = !bandBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (installing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(HuaweiText.facesInstall[lang])
            }
        }
        // Only while THIS face is going: a byte count under an idle button would read as a stale
        // result rather than as progress.
        if (installing && sent > 0) NoteText("${sent / 1024} KB")

        // "On the band" is said in a word and a tick, never by colour alone. Removal appears only
        // for a face the band actually holds and did not ship with: offering it otherwise would be
        // offering a button that cannot work.
        if (onBand) {
            NoteText(
                if (showing) "${HuaweiText.facesOnBand[lang]} · ${HuaweiText.facesShowing[lang]}"
                else HuaweiText.facesOnBand[lang],
            )
            // Offered for every face the band holds. Predicting which ones it will refuse is what
            // went wrong before; the band decides, and the result is re-read from it.
            OutlinedButton(
                onClick = onRemove,
                enabled = !bandBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (removing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(HuaweiText.facesDelete[lang])
                }
            }
        }
    }
}
