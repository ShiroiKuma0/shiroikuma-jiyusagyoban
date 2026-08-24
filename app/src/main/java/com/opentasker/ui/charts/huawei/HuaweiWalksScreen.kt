package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.core.huawei.HuaweiWalkLibrary
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the walks window is doing, so a cell can show it without inventing its own state. */
data class HuaweiWalksState(
    val walks: List<HuaweiWalkLibrary.Walk> = emptyList(),
    val dir: String = "",
    val loading: Boolean = true,
    /** True while the band is being asked for new walks — every button is inert meanwhile. */
    val downloading: Boolean = false,
    /** The walk being handed to 地図, by id. */
    val sharing: String? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = downloading || sharing != null
}

/**
 * 「運動」 — the walks, as a grid.
 *
 * Deliberately the same shape as the watch-face picker: a grid of equal cells, a picture in each,
 * the identity beneath it, one action per cell. 白い熊 asked for that explicitly, and it is the right
 * call beyond consistency — both screens answer "which one?" by looking, and a layout already
 * learned costs nothing to read a second time.
 *
 * **A walk with no map is not a broken cell.** Most walks will have none until they have been handed
 * to 白い熊 地図, so that state gets a real design — the route's own stats, and a button that says
 * what to do about it — rather than an empty frame that reads as a failure.
 */
@Composable
fun HuaweiWalksScreen(
    state: HuaweiWalksState,
    contentPadding: PaddingValues,
    onDownload: () -> Unit,
    onShare: (HuaweiWalkLibrary.Walk) -> Unit,
    onOpenInChizu: (HuaweiWalkLibrary.Walk) -> Unit,
    onOpen: (HuaweiWalkLibrary.Walk) -> Unit,
    /**
     * Where a cell's picture comes from, for callers that are not reading a real archive — today the
     * screenshot previews, which is the only way this layout can be looked at at all, since 白い熊's
     * phone is normally locked and `screencap` returns the keyguard.
     *
     * Null means the ordinary path: decode the file 地図 drew, off the main thread. **A supplied
     * one is called synchronously**, and that is the point — the screenshot engine renders a single
     * frame and never runs a `produceState`, so an asynchronous seam renders every cell as "no map
     * yet" and the preview quietly stops being evidence of anything.
     */
    previewOf: ((HuaweiWalkLibrary.Walk) -> ImageBitmap?)? = null,
) {
    val lang = LocalBandLanguage.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionCard(accent = ChartPalette.STEPS) {
                SectionTitle(HuaweiText.walksTitle[lang], ChartPalette.STEPS)
                BodyText(HuaweiText.walksAbout[lang])
                state.message?.let { NoteText(it) }
                Button(
                    onClick = onDownload,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (state.downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(HuaweiText.walksDownload[lang])
                }
            }
        }

        if (state.walks.isEmpty() && !state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionCard(accent = ChartPalette.AXIS_TEXT) {
                    BodyText(HuaweiText.walksEmpty[lang])
                    NoteText(state.dir)
                }
            }
        }

        items(state.walks, key = { it.id }) { walk ->
            WalkCell(
                walk = walk,
                previewOf = previewOf,
                sharing = state.sharing == walk.id,
                busy = state.busy,
                onShare = { onShare(walk) },
                onOpenInChizu = { onOpenInChizu(walk) },
                onOpen = { onOpen(walk) },
            )
        }
    }
}

@Composable
private fun WalkCell(
    walk: HuaweiWalkLibrary.Walk,
    previewOf: ((HuaweiWalkLibrary.Walk) -> ImageBitmap?)?,
    sharing: Boolean,
    busy: Boolean,
    onShare: () -> Unit,
    onOpenInChizu: () -> Unit,
    onOpen: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    // Every cell is built identically — picture, date, two lines of stats, one button — because a
    // grid of unequal cards is what 白い熊 asked this not to be, and `fillMaxHeight()` cannot deliver
    // it here: a vertical `LazyVerticalGrid` measures its items with an unbounded height and then
    // sizes the row to the tallest, so a cell asking to fill has nothing to fill.
    SectionCard(accent = ChartPalette.STEPS, onClick = onOpen) {
        Box(
            Modifier
                .fillMaxWidth()
                // A map is wider than it is tall, unlike a watch face. Same grid, different frame.
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, ChartPalette.STEPS.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val supplied = previewOf
            val image = if (supplied != null) {
                remember(walk.id) { supplied(walk) }
            } else {
                val bmp by produceState<ImageBitmap?>(null, walk.thumbPath) {
                    value = withContext(Dispatchers.IO) { thumbFromDisk(walk) }
                }
                bmp
            }
            if (image != null) {
                Image(image, walk.id, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            } else {
                NoteText(HuaweiText.walksNoMap[lang])
            }
        }

        Text(
            walkWhen(walk),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            // Two lines, because the full date does not fit one in a grid cell and truncating the
            // clock off the end would be the worst half to lose. Every cell carries the same shape,
            // so they all wrap the same way and the grid stays even.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // Two lines whether the stats need two or not: one cell wrapping and its neighbour not is
        // the whole difference between a grid and a ragged pile.
        Text(
            walkStats(walk, lang),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Always one button, so every cell is the same height. Which button depends on whether 地図
        // has drawn this walk: send it, or go and look at it properly in 地図 — the cell's own tap
        // already opens our large picture, so the button is never a duplicate of it.
        Button(
            onClick = if (walk.hasMap) onOpenInChizu else onShare,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (walk.hasMap) HuaweiText.walksOpenIn[lang] else HuaweiText.walksSend[lang])
        }
    }
}

/** The ordinary source: whatever 地図 last drew for this walk, decoded off disk. */
internal fun thumbFromDisk(walk: HuaweiWalkLibrary.Walk): ImageBitmap? =
    walk.thumbPath?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }

/**
 * `2026-08-23 (日) 18:28` — 白い熊's format, everywhere a walk is dated.
 *
 * The weekday is deliberately fixed to Japanese rather than following the window's language pill:
 * 白い熊 asked for this shape "always", and a date that changes its own alphabet when the labels
 * around it do is a different date to scan for. Java's short `E` in this locale is the single
 * character — 日, 土 — which is what makes the full form fit at all.
 */
private val WHEN_FORMAT = SimpleDateFormat("yyyy-MM-dd (E) HH:mm", Locale.JAPANESE)

internal fun walkWhen(walk: HuaweiWalkLibrary.Walk): String =
    WHEN_FORMAT.format(Date(walk.startSeconds * 1000L))

/**
 * Distance and duration as the band reported them — never recomputed from the drawn line.
 *
 * The duration is labelled **active**, and that word is load-bearing. The band reports a start and a
 * running time, and its own "end" field is exactly `start + duration` — so this figure is
 * time-with-the-recorder-running, not the span of the walk. A real walk here covered 29 minutes of
 * recording across **2 h 08 m** of wall clock, because the band stopped while 白い熊 stood still and
 * resumed from the same spot: two gaps of 17 and 81 minutes that move about a metre on the ground.
 *
 * Printed as a bare duration beside 地図's wall-clock span, it read as the two devices contradicting
 * each other. They never did — the numbers measure different things, and the label is what says so.
 */
internal fun walkStats(walk: HuaweiWalkLibrary.Walk, lang: com.opentasker.ui.charts.BandLanguage): String {
    val km = walk.distanceMetres?.let { "%.2f km".format(Locale.US, it / 1000.0) }
    val mins = walk.durationSeconds?.let { "${HuaweiText.walksActive[lang]} ${hhmm(it)}" }
    return listOfNotNull(km, mins, "${walk.points} ${HuaweiText.walksFixes[lang]}")
        .joinToString(" · ")
}

/** Seconds as `0h 29m`. */
internal fun hhmm(seconds: Long): String = (seconds / 60).let { "${it / 60}h ${it % 60}m" }
