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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import com.opentasker.ui.charts.ActionPill
import com.opentasker.ui.charts.ANNOTATION_INK
import com.opentasker.ui.charts.AnnotationText
import com.opentasker.ui.charts.CountPill
import com.opentasker.ui.charts.CountPickerDialog
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
    val walks: List<HuaweiWorkoutStore.Workout> = emptyList(),
    val loading: Boolean = true,
    /** True while the band is being asked for new walks — every button is inert meanwhile. */
    val downloading: Boolean = false,
    /** The walk being handed to 地図, by id. */
    val sharing: String? = null,
    val message: String? = null,
    /** Heart rate over the open walk's window, read from the synced samples when it is opened. */
    val heart: com.opentasker.core.storage.HuaweiSampleStats? = null,
    /**
     * Which third of the library this window is showing.
     *
     * One window, told which workouts it is for, rather than three screens that would drift apart
     * the first time any of them gained a figure. The band numbers a walk, a lift and a rehab
     * session in one sequence and stores them in one table; the only real differences are that the
     * trackless kinds have no route to draw and nothing to hand to 地図, and all of that falls out
     * of this one value.
     */
    val kind: HuaweiWorkoutStore.Kind = HuaweiWorkoutStore.Kind.WALK,
    /**
     * What each workout cost, keyed by [HuaweiWorkoutStore.Workout.id].
     *
     * Resolved once when the list loads rather than per cell. The sample blocks are one small blob
     * each and a cell that fetched its own would be a blob read per row per recomposition — and,
     * worse, an asynchronous one, which the screenshot engine never runs, so every preview of this
     * grid would draw an empty cell and prove nothing.
     */
    val efforts: Map<String, HuaweiWorkoutStore.Effort> = emptyMap(),
    /** Each walk's route and the cutout under it, resolved the same way and for the same reason. */
    val plots: Map<String, com.opentasker.core.huawei.maps.WalkPlot> = emptyMap(),
    /**
     * How much of each walk the band actually recorded — see `WalkTrack.Coverage`.
     *
     * Measured with the plots rather than in a cell, because it walks the whole polyline and a cell
     * would redo it on every recomposition.
     */
    val coverage: Map<String, com.opentasker.core.huawei.maps.WalkTrack.Coverage> = emptyMap(),
    /**
     * The open walk's map at the ZOOM viewer's resolution — the same bytes as its cell picture,
     * decoded without sub-sampling.
     *
     * Held only while the viewer is open, and dropped when it closes: at 3072 px this is some
     * twenty-eight megabytes of bitmap, which is more than the whole shared cutout cache, so it is
     * exactly the thing not to keep around for a screen nobody is looking at.
     */
    val zoomBase: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Cutout pixels by cutout id — decoded once, shared by every walk that crosses one. */
    val bases: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
    /** Where the last export landed, shown under the buttons that wrote it. */
    val exported: String? = null,
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
    onShare: (HuaweiWorkoutStore.Workout) -> Unit,
    onOpenInChizu: (HuaweiWorkoutStore.Workout) -> Unit,
    onOpen: (HuaweiWorkoutStore.Workout) -> Unit,
    /** Open the calendar of which days this kind was recorded on. */
    onOpenCalendar: () -> Unit = {},
    /**
     * File a stop count for one walk, straight from its cell. Null withdraws the answer.
     *
     * The grid used to be read-only about this and the walk's own screen was the only way in, which
     * made answering a question that takes one tap cost four: open, tap, pick, back. 白い熊, 2026-09-11:
     * the pill is on the cell, and a walk with no answer yet shows the same pill carrying a `+`.
     */
    onSetStops: (HuaweiWorkoutStore.Workout, Int?) -> Unit = { _, _ -> },
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
            val accent = HuaweiText.accentFor(state.kind)
            SectionCard(accent = accent) {
                SectionTitle(HuaweiText.titleFor(state.kind)[lang], accent)
                BodyText(HuaweiText.aboutFor(state.kind)[lang])
                // Directly above the button it describes: what the button fetches is all three
                // kinds, and this window is one of the places they land.
                NoteText(HuaweiText.pullAllKinds[lang])
                state.message?.let { NoteText(it) }
                Button(
                    onClick = onDownload,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (state.downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(HuaweiText.downloadFor(state.kind)[lang])
                }
                // Which days this kind was recorded on — and the way to any one of them, since a
                // filled tile opens that day's session. Every kind has one: a gap in walking is the
                // same finding as a gap in rehab, and a grid of dates is the fastest route to
                // "what did I do on the 29th" (白い熊, 2026-09-04).
                run {
                    ActionPill(
                        label = HuaweiText.rehabCalendar[lang],
                        icon = Icons.Filled.CalendarMonth,
                        onClick = onOpenCalendar,
                        modifier = Modifier.padding(top = 8.dp),
                        large = true,
                    )
                }
            }
        }

        if (state.walks.isEmpty() && !state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionCard(accent = ChartPalette.AXIS_TEXT) {
                    BodyText(HuaweiText.emptyFor(state.kind)[lang])
                }
            }
        }

        items(state.walks, key = { it.id }) { walk ->
            WalkCell(
                walk = walk,
                kind = state.kind,
                effort = state.efforts[walk.id],
                plot = state.plots[walk.id],
                base = state.plots[walk.id]?.cutout?.id?.let { state.bases[it] },
                coverage = state.coverage[walk.id],
                sharing = state.sharing == walk.id,
                busy = state.busy,
                onShare = { onShare(walk) },
                onOpenInChizu = { onOpenInChizu(walk) },
                onOpen = { onOpen(walk) },
                onSetStops = { n -> onSetStops(walk, n) },
            )
        }
    }
}

@Composable
private fun WalkCell(
    walk: HuaweiWorkoutStore.Workout,
    kind: HuaweiWorkoutStore.Kind,
    effort: HuaweiWorkoutStore.Effort?,
    plot: com.opentasker.core.huawei.maps.WalkPlot?,
    base: androidx.compose.ui.graphics.ImageBitmap?,
    /** Null when there is no track; see `WalkTrack.Coverage`. */
    coverage: com.opentasker.core.huawei.maps.WalkTrack.Coverage? = null,
    sharing: Boolean,
    busy: Boolean,
    onShare: () -> Unit,
    onOpenInChizu: () -> Unit,
    onOpen: () -> Unit,
    onSetStops: (Int?) -> Unit = {},
) {
    val lang = LocalBandLanguage.current
    // The stop picker belongs to the CELL, not to the screen: it is opened by this walk's pill and
    // answers about this walk, and a single picker hoisted to the grid would have to carry which
    // walk it was asking about through every recomposition of forty cells.
    var askingStops by remember { mutableStateOf(false) }
    // Every cell is built identically — picture, date, two lines of stats, one button — because a
    // grid of unequal cards is what 白い熊 asked this not to be, and `fillMaxHeight()` cannot deliver
    // it here: a vertical `LazyVerticalGrid` measures its items with an unbounded height and then
    // sizes the row to the tallest, so a cell asking to fill has nothing to fill.
    SectionCard(accent = HuaweiText.accentFor(kind), onClick = onOpen) {
        Box(
            Modifier
                .fillMaxWidth()
                // A map is wider than it is tall, unlike a watch face. Same grid, different frame.
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    (HuaweiText.accentFor(kind)).copy(alpha = 0.4f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A lift has no route, so the frame that holds a map holds the heart rate instead —
            // the same 4:3 box in the same place, because what makes this a grid rather than a
            // pile is that every cell is built identically.
            if (kind.trackless) {
                effort?.takeIf { it.heart.size >= 4 }?.let { e ->
                    HeartTrace(e, Modifier.fillMaxSize().padding(8.dp))
                    // "no map yet" is what a WALK says when 地図 has not drawn its area. A lift or
                    // a rehab session has no area and never will, so the empty frame has to say
                    // what is actually missing — the heart rate that is the whole content of one.
                } ?: NoteText(HuaweiText.noHeart[lang])
                return@Box
            }
            // Drawn, not loaded. Every walk used to carry its own rendered PNG — 2.5 MB for a
            // 120 kB track, and two walks down one street produced two pictures of that street.
            // The route is a few hundred line segments over a map shared by the whole
            // neighbourhood (白い熊, 2026-08-30).
            WalkMap.Picture(
                walk = walk,
                plot = plot,
                base = base,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                thinTo = 260,
                empty = { NoteText(HuaweiText.walksNoMap[lang]) },
                // Never "there is no map" — the fetch is already under way, or about to be.
                needsMap = { NoteText(HuaweiText.walksAskingMap[lang]) },
            )
            // Two words over the corner of the picture, because the picture is the thing that
            // lies: a route missing half a walk looks exactly like a walk half as long. The
            // detail says how much and why; here it only has to stop the cell being believed.
            coverage?.takeIf { it.partial }?.let {
                Text(
                    HuaweiText.walksPartialShort[lang],
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.66f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
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
            if (kind.trackless) effortStats(walk, effort, lang) else walkStats(walk, lang),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // 白い熊's own annotation, so the grid answers "which walks did I write on" by looking —
        // the same question the calendar's note marks answer for the mornings.
        //
        // The row's height is RESERVED whether or not there is anything in it. A vertical
        // `LazyVerticalGrid` sizes each row to its tallest cell, so a line that appears only on
        // annotated walks would leave every cell beside them padded with empty space, which is
        // exactly the ragged grid this screen was built not to be.
        //
        // **The stop count is answerable from here.** It is the one thing on a walk that only 白い熊
        // can supply, it takes one tap to answer, and it used to cost four — open the walk, tap the
        // pill, pick, come back. The pill is the same one the walk's own screen carries, with the
        // same withdraw-by-re-tapping rule, and an unanswered walk shows it as a `+` rather than
        // showing nothing (白い熊, 2026-09-11). The note stays read-only here: a note needs a
        // keyboard and a dialog that names what is being annotated, which is what the walk's screen
        // is for.
        //
        // Stops LEFT, note RIGHT, one line — the same order as the calendar's tile corners, so the
        // two ways of looking at the same walk are read the same way round.
        Row(
            Modifier.fillMaxWidth().height(30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Only a walk has stops to count; a lift and a rehab session are asked nothing here.
            if (kind.countsStops) {
                CountPill(count = walk.stops, onClick = { askingStops = true })
            }
            // A MARK, not the note itself — 白い熊, 2026-09-11: *"add its icon on the right side in
            // the same line where we show the stops, so we see in one look there are notes"*.
            //
            // It was a pill carrying the note's first line, which answered a different question:
            // what does this one say, rather than which of these has one. A pill sized by its text
            // cannot be found by glance — a long first line filled the cell and put the glyph back on
            // the left, which is exactly where it must not be if the row is to be read as "stops
            // left, note right", the same way round as the calendar tile. The text is one tap away,
            // on the screen that can show all of it.
            //
            // The Box owns the rest of the row rather than the arrangement placing the mark: a
            // weighted child is drawn at the START of the width it is given, so alignment has to
            // happen inside it.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (!walk.note.isNullOrBlank()) {
                    Icon(
                        Icons.Filled.EditNote,
                        contentDescription = AnnotationText.note[lang],
                        tint = ANNOTATION_INK,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (askingStops) {
            CountPickerDialog(
                title = AnnotationText.stopsAsk[lang],
                current = walk.stops,
                range = STOPS_RANGE,
                onPick = { n ->
                    // Re-tapping the number on file withdraws it, exactly as it does on the walk's
                    // own screen and as re-tapping a 1–5 rating does. A count that can be changed but
                    // never taken back turns a stray tap into data 白い熊 did not author.
                    onSetStops(if (walk.stops == n) null else n)
                    askingStops = false
                },
                onDismiss = { askingStops = false },
            )
        }

        // Always one button, so every cell is the same height. It no longer offers to SEND the
        // walk to 地図: walks are drawn here now, over a map shared by the whole area, and sending
        // each one there was what filled 地図's library with dozens of near-identical routes. A
        // walk that was sent in the past can still be opened there; one that was not opens our own
        // detail, which is where a missing map is fetched.
        Button(
            onClick = if (!kind.trackless && walk.trackId != null) onOpenInChizu else onOpen,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (sharing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(
                if (!kind.trackless && walk.trackId != null) HuaweiText.walksOpenIn[lang]
                else HuaweiText.walksOpen[lang],
            )
        }
    }
}


/**
 * `2026-08-23 (日) 18:28` — 白い熊's format, everywhere a walk is dated.
 *
 * The weekday is deliberately fixed to Japanese rather than following the window's language pill:
 * 白い熊 asked for this shape "always", and a date that changes its own alphabet when the labels
 * around it do is a different date to scan for. Java's short `E` in this locale is the single
 * character — 日, 土 — which is what makes the full form fit at all.
 */
private val WHEN_FORMAT = SimpleDateFormat("yyyy-MM-dd (E) HH:mm", Locale.JAPANESE)

internal fun walkWhen(walk: HuaweiWorkoutStore.Workout): String =
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
internal fun walkStats(walk: HuaweiWorkoutStore.Workout, lang: com.opentasker.ui.charts.BandLanguage): String {
    // A workout with no route has no distance and no fixes, and printing "0.00 km · 0 点" for a
    // lifting session states two measurements that were never taken.
    val km = walk.distanceMetres?.takeIf { it > 0 || walk.hasTrack }
        ?.let { "%.2f km".format(Locale.US, it / 1000.0) }
    val mins = walk.durationSeconds?.let { "${HuaweiText.walksActive[lang]} ${hhmm(it)}" }
    val fixes = "${walk.trackPoints} ${HuaweiText.walksFixes[lang]}".takeIf { walk.hasTrack }
    return listOfNotNull(km, mins, fixes).joinToString(" · ")
}

/**
 * The clock times the walk actually ran between.
 *
 * The card used to show only its start date and an active duration, so "when did I set off and when
 * did I stop" — the first thing anyone asks of a walk — was the one thing missing (白い熊,
 * 2026-08-30). Note that the band's `end` is `start + active time`, not the wall-clock finish: it
 * stops counting while the recorder is paused. Shown as the band means it, with the span from the
 * track beside it where the two differ, rather than quietly picking one.
 */
internal fun walkClock(walk: HuaweiWorkoutStore.Workout): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
    val start = fmt.format(java.util.Date(walk.startSeconds * 1000))
    val end = walk.endSeconds?.let { fmt.format(java.util.Date(it * 1000)) }
    return if (end == null) start else "$start – $end"
}

/** The band's own counts: steps, calories, climb. Empty when the band reported none of them. */
internal fun walkBandFigures(
    walk: HuaweiWorkoutStore.Workout,
    lang: com.opentasker.ui.charts.BandLanguage,
): String = listOfNotNull(
    // Zero is "the band counted none", which for a lift is the definition and not a figure. Only
    // the calorie count survives a workout with no motion, and it is the one that matters there.
    walk.steps?.takeIf { it > 0 }?.let { "$it ${HuaweiText.walksSteps[lang]}" },
    walk.calories?.takeIf { it > 0 }?.let { "$it ${HuaweiText.walksCalories[lang]}" },
    walk.elevationGainDm?.takeIf { it > 0 }
        ?.let { "${HuaweiText.walksClimb[lang]} %.0f m".format(Locale.US, it / 10.0) },
).joinToString(" · ")

/** Seconds as `0h 29m`. */
internal fun hhmm(seconds: Long): String = (seconds / 60).let { "${it / 60}h ${it % 60}m" }

/**
 * Seconds as `19m 5s`, or `1h 02m` once it is long enough for the seconds to stop mattering.
 *
 * [hhmm] renders a time to first fix of 1135 s as `0h 18m`, which throws away the part being
 * measured: the difference between a 20 s fix and a 90 s one is the entire question about the
 * satellite data, and both round to `0h 01m`.
 */
internal fun shortDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> hhmm(seconds)
}

/** Metres as `1.25 km`, or `840 m` while kilometres would be all zeroes. */
internal fun metresShort(metres: Int): String =
    if (metres >= 1000) "%.2f km".format(Locale.US, metres / 1000.0) else "$metres m"
