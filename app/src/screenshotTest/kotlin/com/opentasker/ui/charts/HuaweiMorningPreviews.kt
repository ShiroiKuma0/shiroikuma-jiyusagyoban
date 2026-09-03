package com.opentasker.ui.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.ui.charts.huawei.HuaweiMorningCard
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * The morning rating, which 白い熊 asked to be impossible to walk past.
 *
 * Rendered because the whole point of the card is how it LOOKS relative to everything around it —
 * an instruction about prominence cannot be checked by reading the code.
 */
@Composable
private fun Frame(felt: Int?, note: String? = null) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp)) {
                HuaweiMorningCard(
                    felt = felt,
                    nightLabel = "20260823",
                    onFelt = {},
                    note = note,
                    onNote = {},
                    nights = 48,
                    rated = 15,
                    humeNights = 26,
                    onOpenRegister = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "Morning — unanswered", widthDp = 413, heightDp = 470, showBackground = true)
@Composable
fun HuaweiMorningUnansweredPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) { Frame(null) }
}

@PreviewTest
@Preview(name = "Morning — answered 日本語", widthDp = 413, heightDp = 470, showBackground = true)
@Composable
fun HuaweiMorningAnsweredPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Frame(2) }
}

/**
 * The two states of the note pill, on the card that carries it.
 *
 * The whole design rests on a relative judgement — the note must be reachable without competing
 * with the rating above it — and that cannot be checked by reading the alphas. The empty state is
 * the one to look at hardest: it has to read as an invitation, not as a control someone forgot to
 * fill in.
 */
@PreviewTest
@Preview(name = "Morning — note written", widthDp = 413, heightDp = 500, showBackground = true)
@Composable
fun HuaweiMorningNotedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(4, "woke at 03:00 and did not get back down; second night after the flight")
    }
}

@PreviewTest
@Preview(name = "Morning — note offered 日本語", widthDp = 413, heightDp = 500, showBackground = true)
@Composable
fun HuaweiMorningNoteEmptyPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) { Frame(1, null) }
}
