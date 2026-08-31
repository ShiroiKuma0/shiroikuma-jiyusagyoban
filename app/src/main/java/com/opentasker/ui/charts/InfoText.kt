package com.opentasker.ui.charts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The typography of every explanatory panel in 「健康」 — defined once, used by all of them.
 *
 * The `i` sheets and the 健康指数 page are long-form prose, and they were being drawn with the same
 * recessive grey `bodySmall` the app uses for chart captions. That is right for a footnote under a
 * plot and wrong for two screens of text somebody is actually going to sit and read: too small, too
 * dim, and too loosely leaded to hold a paragraph together (白い熊, 2026-08-07).
 *
 * So: bigger, in the theme accent rather than grey, set solid at a line height equal to the font
 * size, with headings larger still and underlined so a page of prose has visible structure.
 *
 * **It lives here rather than in each screen** because there are two of them plus eight metric info
 * sheets, and the one certainty about typography spread across ten call sites is that it stops
 * matching. If a size is wrong, it is wrong in one place.
 *
 * The colour is `colorScheme.primary`, not a literal yellow: yellow is what 白い熊's theme happens to
 * make the accent, and text that hard-codes it would stop following a re-theme.
 */
object InfoType {
    /** Body prose. Up from the 12 sp `bodySmall` these panels used to borrow. */
    val BODY_SP = 16.sp

    /** Headings, underlined — the only structure a wall of prose gets. */
    val HEADING_SP = 22.sp

    /**
     * Solid leading: line height equal to the font size.
     *
     * 白い熊 asked for "linespacing one" explicitly. Material's own bodies run about 1.4×, which is
     * comfortable for a two-line caption and turns a five-paragraph panel into a lot of scrolling.
     */
    val BODY_LEADING = BODY_SP
    val HEADING_LEADING = HEADING_SP
}

/** A heading inside an info panel: large, bold, underlined, accent-coloured. */
@Composable
fun InfoHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge,
        fontSize = InfoType.HEADING_SP,
        lineHeight = InfoType.HEADING_LEADING,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
}

/** Body prose inside an info panel. */
@Composable
fun InfoBody(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        fontSize = InfoType.BODY_SP,
        lineHeight = InfoType.BODY_LEADING,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Dates, in one format everywhere: **`2026-08-07`** (白い熊, 2026-08-07).
 *
 * The screens had grown three: `MM-dd` in the day table, `MM-dd HH:mm` at the crosshair, `M/d` on the
 * time axis. Three formats for one kind of thing is three chances to misread which day you are
 * looking at, and the year mattering is not hypothetical once the archive spans a new year.
 *
 * ISO order also sorts as text, which is why the day table can be read down the column.
 */
object BandDates {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun date(tMs: Long): String = DATE.withZone(zone).format(Instant.ofEpochMilli(tMs))
    fun dateTime(tMs: Long): String = DATE_TIME.withZone(zone).format(Instant.ofEpochMilli(tMs))
    fun time(tMs: Long): String = TIME.withZone(zone).format(Instant.ofEpochMilli(tMs))

    /** `22:41 → 08:33` — a night's extent, which is a different thing from its duration. */
    fun span(startMs: Long, endMs: Long): String = "${time(startMs)} → ${time(endMs)}"
}
