package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiWalkLibrary
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One walk, full width.
 *
 * Two states, and the second is the ordinary one for a while: with a map from 白い熊 地図 it shows
 * the route large; without one it shows what the band recorded and offers to send it. Neither is an
 * error state — a walk that has not been shared yet is simply a walk that has not been shared yet.
 */
@Composable
fun HuaweiWalkDetailScreen(
    walk: HuaweiWalkLibrary.Walk,
    sharing: Boolean,
    busy: Boolean,
    message: String?,
    contentPadding: PaddingValues,
    onShare: () -> Unit,
    onOpenInChizu: () -> Unit,
    onBack: () -> Unit,
    /** Where the map comes from — the same seam as the grid, synchronous when supplied. */
    previewOf: ((HuaweiWalkLibrary.Walk) -> ImageBitmap?)? = null,
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }

        item {
            SectionCard(accent = ChartPalette.STEPS) {
                SectionTitle(walkWhen(walk), ChartPalette.STEPS)
                BodyText(walkStats(walk, lang))

                if (walk.hasMap) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        val supplied = previewOf
                        val image = if (supplied != null) {
                            remember(walk.id) { supplied(walk) }
                        } else {
                            val bmp by produceState<ImageBitmap?>(null, walk.mapPath) {
                                value = withContext(Dispatchers.IO) { mapFromDisk(walk) }
                            }
                            bmp
                        }
                        if (image != null) {
                            Image(image, walk.id, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)))
                        } else {
                            // The file was recorded and has since gone — 地図 reinstalled, or the
                            // directory swept. Say that, rather than showing an empty frame.
                            NoteText(HuaweiText.walksNoMap[lang])
                        }
                    }
                    // A route on a pale ground is indistinguishable from a failed render, so say
                    // which it is. The region is a download in 地図, not a fault in either app.
                    if (walk.mapIsBlank) NoteText(HuaweiText.walksNoRegion[lang], warn = true)
                    else NoteText(HuaweiText.walksSharedNote[lang])
                    // Both offered once there is a map: 地図 itself for zooming and layers, and a
                    // redraw for when the region's offline map has since been downloaded and the
                    // picture would now have streets under it.
                    Button(
                        onClick = onOpenInChizu,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text(HuaweiText.walksOpenIn[lang]) }
                    TextButton(
                        onClick = onShare,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(HuaweiText.walksRedraw[lang])
                    }
                } else {
                    BodyText(HuaweiText.walksNoMapLong[lang])
                    Button(
                        onClick = onShare,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(HuaweiText.walksSend[lang])
                    }
                }

                message?.let { NoteText(it) }
            }
        }

        // 地図's own arithmetic over the same GPX, shown beside the band's and never blended with
        // it. Two independent readings of one route are what catches a decoder that has the format
        // slightly wrong — which is not hypothetical here — so a disagreement is worth seeing.
        walk.chizu?.let { c ->
            item {
                SectionCard(accent = ChartPalette.AXIS_TEXT) {
                    SectionTitle(HuaweiText.walksChizuFigures[lang], ChartPalette.AXIS_TEXT)
                    val km = c.distanceMetres?.let { "%.2f km".format(Locale.US, it / 1000.0) }
                    // Labelled `span`: 地図 measures first fix to last, stops included. That is a
                    // different quantity from the band's running time above, not a disagreement
                    // with it — and unlabelled, the two look like a contradiction on one screen.
                    val span = c.durationSeconds?.let { "${HuaweiText.walksSpan[lang]} ${hhmm(it)}" }
                    val moving = c.movingSeconds?.let { "${HuaweiText.walksMoving[lang]} ${hhmm(it)}" }
                    val climb = c.climbMetres?.let { up ->
                        val down = c.descentMetres ?: 0.0
                        "↑%.0f m ↓%.0f m".format(Locale.US, up, down)
                    }
                    BodyText(listOfNotNull(km, span, moving, climb).joinToString(" · "))
                }
            }
        }

        item {
            SectionCard(accent = ChartPalette.AXIS_TEXT) {
                SectionTitle(HuaweiText.walksFilesTitle[lang], ChartPalette.AXIS_TEXT)
                // The raw file is named on purpose. It is the thing that can be re-decoded when the
                // format turns out to be understood slightly wrongly — which has already happened
                // once, and is why it is kept at all.
                NoteText(HuaweiText.walksFilesNote[lang])
                NoteText(walk.gpx.absolutePath)
                NoteText(walk.raw.absolutePath)
            }
        }
    }
}

/** The ordinary source: the large picture 地図 last drew for this walk. */
internal fun mapFromDisk(walk: HuaweiWalkLibrary.Walk): ImageBitmap? =
    walk.mapPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
