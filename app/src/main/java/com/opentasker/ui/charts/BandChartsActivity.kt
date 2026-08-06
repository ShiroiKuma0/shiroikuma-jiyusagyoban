package com.opentasker.ui.charts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.band.BandSettings
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore

/**
 * 「健康」 in its own fullscreen window.
 *
 * Deliberately NOT a tab inside 白い熊 自由作業盤 (白い熊, 2026-08-03): the charts are a thing to open
 * and look at, from a launcher shortcut, without going through the automation editor first. It was
 * briefly a bottom-bar destination and that was wrong twice over — it buried the charts behind a
 * horizontally-scrolling bar they fell off the right-hand end of, and it made looking at your own
 * health data require opening an app about automation.
 *
 * A task reaches it through the `band.charts` action, and a launcher shortcut reaches the task
 * through the existing CREATE_SHORTCUT picker — so an icon on the home screen leads straight here.
 *
 * `singleTask` (see the manifest) so returning resumes rather than stacking a second copy. The window
 * owns nothing: the screens read the database and [com.opentasker.core.band.BandSyncState], both of
 * which outlive it, so a rotation or a trip to Home costs nothing and a sync started here survives
 * the window being closed.
 */
class BandChartsActivity : ComponentActivity() {

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
        val deepLink = intent?.getStringExtra(EXTRA_METRIC)?.takeIf { it.isNotBlank() }
        // Read once per launch rather than per frame: `健康の設定 -- [727][01]` writes it through the
        // band.charts action, so it is already settled by the time this window is created.
        val language = BandLanguage.parse(BandSettings.language(applicationContext))
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            // The chart style is derived from the same prefs the rest of the theme reads, so a slider
            // moved on the UI page changes these charts the next time this window composes — no
            // separate store, no second source of truth.
            val chartStyle = remember(themePrefs) { ChartStyle.from(themePrefs) }
            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(
                    LocalBandLanguage provides language,
                    LocalChartStyle provides chartStyle,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        // Edge-to-edge draws under the system bars, and 白い熊's status bar carries the
                        // kanji clock overlay — without this the first card sits underneath it.
                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val model: BandDashboardModel = viewModel(
                            factory = BandDashboardModelFactory(
                                OpenTaskerApp_NoHilt.db,
                                applicationContext,
                            ),
                        )
                        // One piece of navigation state, not a navigation library: this window has
                        // exactly two destinations and a back gesture between them.
                        var selected by rememberSaveable(deepLink) { mutableStateOf(deepLink) }
                        val state by model.state.collectAsState()

                        if (selected == null) {
                            BandDashboardScreen(
                                model = model,
                                contentPadding = insets,
                                onOpenMetric = { selected = it },
                            )
                        } else {
                            MetricDetailScreen(
                                state = state,
                                metricKey = selected!!,
                                contentPadding = insets,
                                onBack = { selected = null },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Optional: open straight onto one metric instead of the dashboard. */
        const val EXTRA_METRIC = "shiroikuma.jiyusagyoban.extra.BAND_METRIC"

        /** Optional: initial visible span in minutes. Absent means 24 hours. */
        const val EXTRA_SPAN_MINUTES = "shiroikuma.jiyusagyoban.extra.BAND_SPAN_MINUTES"

        /**
         * Launched from an Action, which runs off the main thread with an application Context —
         * hence NEW_TASK. CLEAR_TOP so running the task twice brings the existing window forward
         * with the new arguments instead of leaving a stale copy behind it.
         */
        fun open(context: Context, metric: String?, spanMinutes: Int?) {
            val app = context.applicationContext
            val intent = Intent(app, BandChartsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (!metric.isNullOrBlank()) putExtra(EXTRA_METRIC, metric)
                if (spanMinutes != null && spanMinutes > 0) putExtra(EXTRA_SPAN_MINUTES, spanMinutes)
            }
            app.startActivity(intent)
        }
    }
}
