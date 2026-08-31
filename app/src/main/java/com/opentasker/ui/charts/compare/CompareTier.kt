package com.opentasker.ui.charts.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.opentasker.ui.charts.ChartPalette

/**
 * How much weight a comparison will bear.
 *
 * ## Why the good case has no badge
 *
 * [DIRECT] shows nothing at all. Two reasons, and the second is the one that decides it: green
 * against red measures ΔE 4.1 under deuteranopia, which is not a distinction 白い熊 can rely on; and
 * badging the ordinary case teaches the reader that badges are decoration, so the badge that
 * matters gets skipped with the rest.
 *
 * Every chip that IS shown carries a glyph and a word as well as a colour, and their lightnesses
 * form a ladder (0.811 > 0.632 > 0.575) so they remain three distinct things in greyscale.
 */
enum class CompareTier(
    private val glyph: String,
    private val label: String,
    private val ink: Color?,
) {
    /** Both bands measure this the same way. No chip — see the class note. */
    DIRECT("", "", null),

    /** Comparable, with a caveat the reader must hold in mind. */
    CAUTION("≈", "注意して比較", ChartPalette.BAND_WARN),

    /** Only one band recorded this at all, so there is nothing to compare. */
    ONE_BAND_ONLY("·", "1台のみ", ChartPalette.AXIS_TEXT),

    /** The join refused. */
    REFUSED("✕", "比較できない", ChartPalette.BAND_SERIOUS);

    @Composable
    fun Chip() {
        val colour = ink ?: return
        Text(
            "$glyph $label",
            style = MaterialTheme.typography.labelMedium,
            color = colour,
            modifier = Modifier
                .padding(top = 2.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colour.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    companion object {
        /**
         * Which tier a finished join has earned.
         *
         * Derived from what actually happened rather than declared per metric: a metric that is in
         * principle directly comparable but which only one band recorded today is, today, one band
         * only — and saying otherwise would be describing the plan instead of the data.
         */
        fun of(join: CompareData.Join, provisional: Boolean): CompareTier = when {
            join.both == 0 -> ONE_BAND_ONLY
            provisional -> CAUTION
            else -> DIRECT
        }
    }
}
