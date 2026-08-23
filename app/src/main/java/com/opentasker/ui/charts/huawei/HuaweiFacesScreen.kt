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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiFaceLibrary
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
)

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
     * Where a cell's picture comes from. Defaulted to the archive, and overridable so a screenshot
     * preview can render real thumbnails — the phone is normally locked, so a render is the only way
     * this layout gets looked at before it ships.
     */
    previewOf: (HuaweiFaceLibrary.Entry) -> ByteArray? = { HuaweiFaceLibrary.preview(it.zip) },
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
                bandBusy = state.installing != null,
                sent = state.bytesSent,
                previewOf = previewOf,
                onInstall = { onInstall(face) },
            )
        }
    }
}

@Composable
private fun FaceCell(
    face: HuaweiFaceLibrary.Entry,
    installing: Boolean,
    bandBusy: Boolean,
    sent: Int,
    previewOf: (HuaweiFaceLibrary.Entry) -> ByteArray?,
    onInstall: () -> Unit,
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
            val bmp by produceState<ImageBitmap?>(null, face.zip.absolutePath) {
                value = withContext(Dispatchers.IO) {
                    previewOf(face)?.let {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }
                }
            }
            val image = bmp
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
    }
}
