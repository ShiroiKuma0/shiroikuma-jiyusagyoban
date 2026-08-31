package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.PaletteCheck
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText

/** 1 is best, 5 is worst — settled 2026-08-12 and never re-ordered. */
private val SCALE = 1..5

/** What the pill's tinted background actually sits on, for the contrast test above. */
private const val CARD_INK = 0xFF141210.toInt()


/**
 * 「今朝の体感」 — the morning rating, at the very top of the report and impossible to walk past.
 *
 * ## Why this one card looks unlike every other
 *
 * Everything else on this screen is a measurement: the band recorded it whether or not 白い熊 was
 * paying attention. This is the only thing on the page that does not exist unless they answer it,
 * and an unanswered morning cannot be recovered later — by evening the question is unanswerable and
 * the day is simply blank in the register forever.
 *
 * So it is deliberately louder than the rest: 白い熊 asked for a thick-bordered pill, a big bold
 * underlined heading, and the top of the page (2026-08-23). That is not decoration, it is the one
 * place on this screen where drawing attention changes what data exists.
 *
 * The buttons wear the 1–5 scale's own colours — settled 2026-08-12, 1 = best — so the thing tapped
 * and the thing the register prints afterwards are the same object. Never a heat map, never
 * re-ordered.
 */
@Composable
fun HuaweiMorningCard(
    felt: Int?,
    nightLabel: String?,
    onFelt: (Int) -> Unit,
    /** Nights on record and how many are rated — the register, folded into this same pill. */
    nights: Int = 0,
    rated: Int = 0,
    humeNights: Int = 0,
    onOpenRegister: (() -> Unit)? = null,
) {
    val lang = LocalBandLanguage.current
    val accent = ChartPalette.BAND_WARN

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.10f))
            // Thick, and in the accent itself rather than a hairline — this is the border 白い熊
            // asked to be able to see from across the room.
            .border(3.dp, accent, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            HuaweiText.morningTitle[lang],
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            color = accent,
        )
        Text(
            felt?.let { HuaweiText.morningAnswered[lang].format(nightLabel ?: "—", it) }
                ?: HuaweiText.morningAsk[lang],
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (n in SCALE) {
                val selected = felt == n
                val tint = ChartPalette.scale(n)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (selected) Modifier.background(tint)
                            else Modifier
                                .background(tint.copy(alpha = 0.20f))
                                .border(2.dp, tint, RoundedCornerShape(12.dp)),
                        )
                        // Padding BEFORE clickable, or the touch target shrinks to the glyph and a
                        // tap aimed at the number falls through to whatever is behind it. The Hume
                        // card carries the same note because it was found the hard way there.
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                        .clickable { onFelt(n) },
                ) {
                    Text(
                        "$n",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        // Unselected, the number is normally its own colour — the tap target and the
                        // mark the register prints are then the same object. But step 5 is a DARK
                        // red by design (it is the worst rung), and dark red on a dark card is not a
                        // number anyone can read. So the ink falls back to white wherever the tint
                        // cannot carry text against this surface. Measured rather than special-cased
                        // by number, so a future change to the scale cannot reintroduce it.
                        color = when {
                            selected -> ChartPalette.scaleInk(n)
                            PaletteCheck.contrast(tint.toArgb(), CARD_INK) >= 3.0 -> tint
                            else -> Color.White
                        },
                    )
                }
            }
        }
        NoteText(HuaweiText.morningScale[lang])

        // 「あらゆる夜と運動」 lives inside this pill (白い熊, 2026-08-23) — the register is the
        // ledger this rating is filed into, so the question and the record it answers into are one
        // object rather than two that happen to sit near each other.
        //
        // But it has to LOOK like something you press. Folded in as plain text under a divider it
        // read as a footnote, and 白い熊 could not tell it was tappable. So it is its own bordered
        // pill inside the yellow one, in the blue that means "a thing to open" everywhere else on
        // this screen — a nested affordance rather than a paragraph.
        if (nights > 0 && onOpenRegister != null) {
            val link = ChartPalette.STEPS   // the blue
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(link.copy(alpha = 0.10f))
                    .border(2.dp, link, RoundedCornerShape(14.dp))
                    // Padding BEFORE clickable, so the whole pill is the target rather than the
                    // text inside it — the same trap the 1–5 buttons above carry a note about.
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clickable { onOpenRegister() },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        HuaweiText.registerTitle[lang],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = link,
                    )
                    // The chevron says "this opens something" without needing a word for it, and it
                    // survives both languages.
                    Text("›", style = MaterialTheme.typography.titleLarge, color = link)
                }
                Text(
                    HuaweiText.registerSummary[lang].format(nights, rated),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Both numbers come from the SAME list of nights. They used to come from two — the
                // register's windowed rows and the full history — which printed "22 nights on
                // record · 26 of them are the Hume band's" on 白い熊's screen. A share that exceeds
                // its whole makes a reader distrust every other figure on the page, and rightly.
                if (humeNights > 0) NoteText(HuaweiText.humeNights[lang].format(humeNights, nights))
            }
        }
    }
}
