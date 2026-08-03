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
import androidx.compose.ui.Modifier
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.ui.screens.BandScreen
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
 * owns nothing: [BandScreen] reads the database and [com.opentasker.core.band.BandSyncState], both of
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
        val metric = intent?.getStringExtra(EXTRA_METRIC)?.takeIf { it.isNotBlank() }
        val spanMinutes = intent?.getIntExtra(EXTRA_SPAN_MINUTES, 0)?.takeIf { it > 0 }
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Edge-to-edge draws under the system bars, and 白い熊's status bar carries the
                    // kanji clock overlay — without this the first card sits underneath it.
                    BandScreen(
                        db = OpenTaskerApp_NoHilt.db,
                        contentPadding = WindowInsets.systemBars.asPaddingValues(),
                        onlyMetric = metric,
                        initialSpanMinutes = spanMinutes,
                    )
                }
            }
        }
    }

    companion object {
        /** Optional: show only this metric (`hr`, `hrv`, `spo2`, `temp`, `stress`). */
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
