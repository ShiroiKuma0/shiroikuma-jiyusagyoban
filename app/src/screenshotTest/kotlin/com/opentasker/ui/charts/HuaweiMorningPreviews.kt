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
private fun Frame(felt: Int?) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(12.dp)) {
                HuaweiMorningCard(
                    felt = felt,
                    nightLabel = "20260823",
                    onFelt = {},
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
