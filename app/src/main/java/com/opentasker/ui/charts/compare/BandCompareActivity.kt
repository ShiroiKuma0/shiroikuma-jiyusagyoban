package com.opentasker.ui.charts.compare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「バンド比較」 in its own window.
 *
 * Its own activity rather than a tab of either band's report, because it belongs to NEITHER. It sits
 * in the 共通 group with the other device-neutral tasks, so when the Hume side is eventually retired
 * this window is untouched — the comparison is what earns that decision, and it would be strange for
 * it to live inside one of the things it is comparing.
 */
class BandCompareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        renderFrom(intent)
    }

    /** `singleTask` delivers a second launch here rather than through [onCreate]. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFrom(intent)
    }

    private fun renderFrom(intent: Intent?) {
        val spanMinutes = intent?.getIntExtra(EXTRA_SPAN_MINUTES, 0)?.takeIf { it > 0 } ?: (24 * 60)
        val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            val chartStyle = remember(themePrefs) { ChartStyle.from(themePrefs) }
            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(
                    LocalBandLanguage provides lang,
                    LocalChartStyle provides chartStyle,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        var cards by remember { mutableStateOf(emptyList<CompareModel.Card>()) }
                        var loading by remember { mutableStateOf(true) }
                        val now = remember { System.currentTimeMillis() }
                        val spanMs = spanMinutes * 60_000L
                        // ONE viewport for every card. Two bands on separate scales would be a
                        // convincing wrong chart; two cards on separate time windows would be the
                        // same mistake one level up.
                        val viewport = remember { ChartViewport(now, spanMs) }

                        LaunchedEffect(spanMs) {
                            cards = withContext(Dispatchers.IO) {
                                CompareModel.build(OpenTaskerApp_NoHilt.db, now - spanMs, now)
                            }
                            loading = false
                        }

                        CompareScreen(
                            cards = cards,
                            viewport = viewport,
                            contentPadding = WindowInsets.systemBars.asPaddingValues(),
                            loading = loading,
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SPAN_MINUTES = "shiroikuma.jiyusagyoban.extra.COMPARE_SPAN_MINUTES"

        fun open(context: Context, spanMinutes: Int?) {
            context.startActivity(
                Intent(context, BandCompareActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    spanMinutes?.takeIf { it > 0 }?.let { putExtra(EXTRA_SPAN_MINUTES, it) }
                },
            )
        }
    }
}
