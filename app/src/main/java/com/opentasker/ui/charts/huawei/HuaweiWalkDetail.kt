package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.opentasker.core.huawei.HuaweiWalkLibrary
import com.opentasker.ui.charts.AnnotationText
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.CountPickerDialog
import com.opentasker.ui.charts.CountPill
import com.opentasker.ui.charts.NoteDialog
import com.opentasker.ui.charts.NotePill
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
    /** Heart rate over this walk's window, from the synced samples. Null when none were stored. */
    heart: com.opentasker.core.storage.HuaweiSampleStats? = null,
    contentPadding: PaddingValues,
    onShare: () -> Unit,
    onOpenInChizu: () -> Unit,
    onBack: () -> Unit,
    /** Where the map comes from — the same seam as the grid, synchronous when supplied. */
    plotOf: ((HuaweiWalkLibrary.Walk) -> com.opentasker.core.huawei.maps.WalkPlot?)? = null,
    /** Where the cutouts live — the walk root. */
    walkRoot: java.io.File = java.io.File(HuaweiWalkLibrary.DEFAULT_DIR),
    /** Ask 地図 for the base map of this walk's area, once, for every walk that will follow. */
    onFetchMap: () -> Unit = {},
    /**
     * File the walk's annotation — 白い熊's note and stop count, both stated whole.
     *
     * Null means "there is none": a blank note deletes, and re-tapping the stop count on file
     * withdraws it. The screen always sends the annotation it wants the walk to end up with, so
     * there is no partial update for a caller to get wrong.
     */
    onAnnotate: (note: String?, stops: Int?) -> Unit = { _, _ -> },
) {
    BackHandler(onBack = onBack)
    val lang = LocalBandLanguage.current
    // Both editors live at the screen's level, not inside the card: the card is one item of a
    // LazyColumn and would take its own dialog down with it the moment it scrolled out of view.
    var editingNote by remember(walk.id) { mutableStateOf(false) }
    var editingStops by remember(walk.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TextButton(onClick = onBack) { Text(HuaweiText.back[lang]) } }

        item {
            SectionCard(accent = ChartPalette.STEPS) {
                SectionTitle(walkWhen(walk), ChartPalette.STEPS)
                BodyText(walkClock(walk))
                BodyText(walkStats(walk, lang))
                // Steps, calories and climb, as the band counted them. Decoded since the first day
                // and thrown away on the way to disk until 2026-08-30, so the band's own screen
                // showed figures this one could not.
                walkBandFigures(walk, lang).takeIf { it.isNotEmpty() }?.let { BodyText(it) }
                // Ours, not the band's, and labelled so. Averaged from the samples the sync stored
                // over exactly this walk's window; the band computes its own figure differently and
                // the two are not required to agree.
                heart?.let { hr ->
                    BodyText("${HuaweiText.walksHeart[lang]} ${hr.mean?.let { "%.0f".format(it) }} bpm" +
                        (hr.low?.let { lo -> hr.high?.let { hi -> "  (%.0f–%.0f)".format(lo, hi) } } ?: ""))
                    NoteText("${HuaweiText.walksHeartFromSamples[lang]} · ${hr.n}")
                }

                // The route, drawn over whatever cutout covers this area. No PNG is kept per
                // walk any more — see WalkMap for why that was twenty times the size of the walk.
                var needsMap by remember(walk.id) { mutableStateOf(false) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    WalkMap.Picture(
                        walk = walk,
                        walkRoot = walkRoot,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                        plotOf = plotOf,
                        empty = { NoteText(HuaweiText.walksNoMap[lang]) },
                        needsMap = {
                            NoteText(HuaweiText.walksNeedMap[lang])
                            LaunchedEffect(walk.id) { needsMap = true }
                        },
                    )
                }
                // Somewhere new: one request, for the AREA, and every future walk here is free.
                if (needsMap) {
                    Button(
                        onClick = onFetchMap,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(HuaweiText.walksGetMap[lang])
                    }
                } else {
                    NoteText(HuaweiText.walksMapShared[lang])
                }
                // 地図 itself stays available for zooming and layers, but only for a walk that was
                // actually sent there. Nothing sends walks there by default any more.
                if (walk.trackId != null) {
                    TextButton(
                        onClick = onOpenInChizu,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(HuaweiText.walksOpenIn[lang]) }
                }

                message?.let { NoteText(it) }
            }
        }

        // What 白い熊 made of the walk, as opposed to what the band measured of it. Its own card,
        // directly under the figures, because it is the only thing on this screen that is an answer
        // rather than a reading — and the only thing here that does not exist unless it is given.
        item {
            SectionCard(accent = ChartPalette.STEPS) {
                SectionTitle(AnnotationText.own[lang], ChartPalette.STEPS)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        AnnotationText.stops[lang],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Black pill, yellow number, yellow border — 白い熊's own words for it
                    // (2026-09-02). Labelled, because a bare digit beside a distance and a duration
                    // would be a third measurement rather than an answer to a question.
                    CountPill(count = walk.stops, onClick = { editingStops = true })
                }
                NotePill(note = walk.note, onClick = { editingNote = true })
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

                    // 地図's active time is the ONE figure here that measures the same thing the
                    // band's does, so it is the only one worth putting side by side. Shown with the
                    // difference spelled out rather than left for the reader to subtract — a
                    // disagreement here says one of the two is reading the walk wrongly, and that
                    // is the whole reason this card exists.
                    c.activeSeconds?.let { active ->
                        val band = walk.durationSeconds
                        val gap = band?.let { b ->
                            val s = active - b
                            " (${if (s >= 0) "+" else "−"}${abs(s)} s)"
                        }.orEmpty()
                        NoteText(
                            "${HuaweiText.walksAgainstBand[lang]}: " +
                                "${HuaweiText.walksActive[lang]} ${hhmm(active)}$gap",
                        )
                    }
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

    if (editingNote) {
        NoteDialog(
            title = walkWhen(walk),
            note = walk.note,
            onSave = { text ->
                onAnnotate(text, walk.stops)
                editingNote = false
            },
            onDismiss = { editingNote = false },
        )
    }
    if (editingStops) {
        CountPickerDialog(
            title = AnnotationText.stopsAsk[lang],
            current = walk.stops,
            range = STOPS_RANGE,
            onPick = { n ->
                // Re-tapping the number on file withdraws it, exactly as re-tapping a 1–5 rating
                // does. A count you can change but never take back turns a stray tap into data
                // 白い熊 did not author.
                onAnnotate(walk.note, if (walk.stops == n) null else n)
                editingStops = false
            },
            onDismiss = { editingStops = false },
        )
    }
}

/**
 * How many stops the picker offers.
 *
 * Zero is a real answer — "I did not stop" is a different statement from "I have not said" — so the
 * range starts there, and the absence of any value is what "not answered" means. The top is a round
 * ten: the longest walk on file paused twice, and a picker that needs scrolling to reach the number
 * you want is a picker that gets the wrong number tapped.
 */
private val STOPS_RANGE = 0..9

/** The ordinary source: the large picture 地図 last drew for this walk. */
internal fun mapFromDisk(walk: HuaweiWalkLibrary.Walk): ImageBitmap? =
    walk.mapPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
