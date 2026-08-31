package com.opentasker.ui.charts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.ui.charts.huawei.BoardLanguage
import com.opentasker.ui.charts.huawei.BoardSync
import com.opentasker.ui.charts.huawei.HuaweiBoardScreen
import com.opentasker.ui.charts.huawei.HuaweiBoardState
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * The board, looked at without the phone.
 *
 * This is the only way this layout gets SEEN before it ships: 白い熊's phone is normally locked, and
 * EMUI leaves overlay windows out of `screencap` entirely, so the previous version of this board was
 * built, delivered and described without anyone having laid eyes on it. Sixteen hand-drawn pictures
 * are exactly the thing that cannot be verified by reasoning about the code.
 *
 * The artwork draws with no photographs supplied — `faceArt`/`walkArt` are null here — which is also
 * the honest case to check: those two cards fall back to drawn art whenever the library has no earth
 * face or no walk with a map, and the fallback has to hold its own beside the other fourteen.
 */
@Composable
private fun Frame(state: HuaweiBoardState) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            HuaweiBoardScreen(
                state = state,
                contentPadding = PaddingValues(10.dp),
                onRun = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Board — English", widthDp = 413, heightDp = 1500, showBackground = true)
@Composable
fun HuaweiBoardEnglishPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(HuaweiBoardState(lang = BandLanguage.EN))
    }
}

@PreviewTest
@Preview(name = "Board — 日本語", widthDp = 413, heightDp = 1500, showBackground = true)
@Composable
fun HuaweiBoardJapanesePreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame(HuaweiBoardState(lang = BandLanguage.JA))
    }
}

/** A sync in flight: the one card that does not close the board, because it has progress to show. */
@PreviewTest
@Preview(name = "Board — syncing", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiBoardSyncingPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame(
            HuaweiBoardState(
                lang = BandLanguage.JA,
                sync = BoardSync(phase = "履歴を読んでいます", pct = "62",
                    records = "1137 / 1820", running = true),
            ),
        )
    }
}

/** And finished, which is when the two deeper actions become available. */
@PreviewTest
@Preview(name = "Board — synced", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiBoardSyncedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiBoardState(
                lang = BandLanguage.EN,
                sync = BoardSync(
                    summary = "1137 samples from 49/49 records · 104 sleep segments · 328 RR windows",
                    running = false,
                ),
            ),
        )
    }
}

/**
 * The band-language dialog.
 *
 * Its whole job is to be correct about a limitation: the band cannot report its language — no read
 * on the locale service, and a full product-info sweep on 2026-08-29 returned 34 tags with no
 * language among them. So it shows what this phone last set, says plainly that the band has no way
 * to answer, and offers BOTH languages side by side rather than "the other one" — which would have
 * to know which one it is in. The wording and that pair of buttons are the feature; both are worth
 * looking at rather than reasoning about.
 */
@PreviewTest
@Preview(name = "Language — Japanese", widthDp = 413, heightDp = 700, showBackground = true)
@Composable
fun HuaweiBoardLanguageJaPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame(
            HuaweiBoardState(
                lang = BandLanguage.JA,
                language = BoardLanguage(remembered = "ja-JP"),
            ),
        )
    }
}

/** And in English, just after a switch was sent. */
@PreviewTest
@Preview(name = "Language — just switched", widthDp = 413, heightDp = 700, showBackground = true)
@Composable
fun HuaweiBoardLanguageSwitchedPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(
            HuaweiBoardState(
                lang = BandLanguage.EN,
                language = BoardLanguage(remembered = "en-US", told = "en-US"),
            ),
        )
    }
}

/**
 * A card whose task is still running, and which knows the way back into its panel.
 *
 * Worth its own preview because it was broken in a way reasoning could not catch: the reopen handler
 * existed, but `enabled = !anyBusy` disabled the button, and a disabled Compose button swallows the
 * click before any handler sees it. The tile spun and could not be pressed (白い熊, 2026-08-30). What
 * this checks is that the running card is still live AND says so.
 */
@PreviewTest
@Preview(name = "Board — a card running", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiBoardCardRunningPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame(HuaweiBoardState(lang = BandLanguage.EN, busy = "衛星予測（Huawei） -- [727]"))
    }
}
