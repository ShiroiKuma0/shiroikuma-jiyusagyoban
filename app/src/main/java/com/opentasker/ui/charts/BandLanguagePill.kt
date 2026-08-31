package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The 日本語／英語 pill — one definition, worn by every page of the report.
 *
 * It started on the dashboard alone and 白い熊 then asked for it on 運動と回復 and on each individual
 * report page too (2026-08-20). Four hosts is exactly the number at which a copied chip starts
 * drifting, so the chip, its busy state and its failure line live here and the hosts only decide
 * *where* to put them.
 *
 * The state is split from the chip on purpose. The chip has to sit on a line that is already full —
 * beside a bold datetime, or between a screen title and the ⓘ — while its failure message needs the
 * full width of whatever contains it. A single composable emitting both would force one of the two
 * into the wrong place, so the host holds [LanguageSwitchState] and drops [LanguagePill] and
 * [LanguageSwitchFailure] where each one fits.
 */

/** Held by the host so the chip and its failure line can be placed apart. */
@Stable
class LanguageSwitchState {
    /** True from the tap until the settings task has been rewritten, saved and run. */
    var switching by mutableStateOf(false)
        internal set

    /** Why the last attempt did not take, or null. */
    var failure by mutableStateOf<Loc?>(null)
        internal set
}

@Composable
fun rememberLanguageSwitch(): LanguageSwitchState = remember { LanguageSwitchState() }

/**
 * The chip.
 *
 * One chip, labelled with both languages exactly as 白い熊 wrote them — not "the one you are not in",
 * and not a highlighted pair. The label says what the control is, and what it is is a switch between
 * two languages; which one you are currently reading is already answered by every other word on the
 * page.
 *
 * **Yellow border, no fill**, which is this app's settled grammar for a live control — see
 * `SelectionChip`: an outline is the thing you can press, a filled yellow slab is the thing you are
 * about to do. It follows `colorScheme.primary` rather than a literal `#FFFF00` because the whole
 * window is re-themeable from the customization page, and a hard-coded yellow would survive a theme
 * change that everything around it did not.
 *
 * Tapping it rewrites `健康の設定 -- [727][01]`, saves it and runs it — see [BandLanguageSwitch] for
 * why it goes the long way round rather than writing the preference. That is a database write and a
 * task run, so the chip goes quiet and untappable while it happens rather than accepting a second
 * press that would race the first.
 */
@Composable
fun LanguagePill(
    state: LanguageSwitchState,
    onSwitch: suspend () -> Loc?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val ink = accent.copy(alpha = if (state.switching) 0.4f else 1f)
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Transparent)
            .border(1.5.dp, ink, RoundedCornerShape(14.dp))
            .clickable(enabled = !state.switching) {
                state.switching = true
                state.failure = null
                scope.launch {
                    // Survives this composable being recomposed in the OTHER language: the state is
                    // remembered by the host at the same position, so the flag still clears.
                    state.failure = onSwitch()
                    state.switching = false
                }
            }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(LANGUAGE_PILL, style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

/**
 * The reason the last tap did not take, if it did not.
 *
 * Emitted separately from the chip so it gets a full-width line of its own: every failure here is a
 * sentence about the workspace ("no task sets %Band_Language"), and a sentence squeezed in beside a
 * chip is a sentence nobody can read.
 */
@Composable
fun LanguageSwitchFailure(state: LanguageSwitchState, modifier: Modifier = Modifier) {
    val lang = LocalBandLanguage.current
    state.failure?.let {
        Text(
            it[lang],
            modifier,
            style = MaterialTheme.typography.bodySmall,
            color = ChartPalette.BAND_CRITICAL,
        )
    }
}

/**
 * Deliberately not a [Loc]: the label is the same in both languages, because it names both. Putting
 * it in the string table would invite a "translation" of a string that has nothing to translate.
 */
private const val LANGUAGE_PILL = "日本語／英語"
