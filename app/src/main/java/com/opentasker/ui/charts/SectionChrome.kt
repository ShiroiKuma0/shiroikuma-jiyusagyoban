package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The look of 「健康」 — one place, so every section wears it.
 *
 * 白い熊 asked for this (2026-08-10): a yellow rounded border around each group, the section name as
 * a big bold underlined header, larger text throughout, yellow as the base ink, and sub-headings
 * visibly distinct from body text.
 *
 * It lives in one file rather than in each card because the failure mode of "make it nicer" is eight
 * cards that are each nice and collectively inconsistent. [SectionCard] is the frame, [SectionTitle]
 * the header, [SubHeading] the internal divider, and [BodyText]/[NoteText] the two weights of prose.
 * A card that wants to look different has to change this file, which is the point.
 *
 * The accent follows the theme rather than being a literal yellow: 白い熊's palette is
 * yellow-on-black today and the whole window is re-themeable from the customization page, so the
 * colour is read from `MaterialTheme` and the metric's own series colour, never hard-coded.
 */

/** Base ink for section prose. Yellow-on-black is 白い熊's palette; it follows the theme. */
val sectionInk: Color
    @Composable get() = MaterialTheme.colorScheme.primary

/**
 * A bordered group.
 *
 * [accent] tints the border and the title dot, so heart rate, sleep and the index are told apart at
 * a glance without a legend. The border is deliberately thin — a heavy one at this corner radius
 * reads as a button rather than a container.
 */
@Composable
fun SectionCard(
    accent: Color = sectionInk,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * The section name: big, bold, underlined, with the accent dot and an optional trailing chip.
 *
 * Underlined by a drawn rule rather than `TextDecoration.Underline` — a text decoration hugs the
 * glyphs and looks like a hyperlink; a rule the width of the row reads as a heading.
 */
@Composable
fun SectionTitle(
    text: String,
    accent: Color = sectionInk,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 23.sp),
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent.copy(alpha = 0.45f)),
        )
    }
}

/** A heading inside a section — smaller than [SectionTitle], heavier and warmer than body text. */
@Composable
fun SubHeading(text: String, accent: Color = sectionInk) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, letterSpacing = 0.4.sp),
        fontWeight = FontWeight.Bold,
        color = accent.copy(alpha = 0.85f),
    )
}

/** Section prose. A step up from the old bodySmall, which was hard work on a phone at arm's length. */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = sectionInk,
    )
}

/** The quiet weight — provenance, caveats, the arithmetic behind a number. */
@Composable
fun NoteText(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        color = if (warn) ChartPalette.BAND_WARN else LocalChartStyle.current.axisText,
    )
}

/** A value pill — the band chips, the load status, anything that classifies a number. */
@Composable
fun ValueChip(text: String, tint: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.18f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}
