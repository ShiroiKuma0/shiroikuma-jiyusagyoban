package com.opentasker.ui.charts.huawei

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore

/**
 * 「健康（Huawei）」 in its own fullscreen window.
 *
 * A SEPARATE activity from the Hume band's rather than a mode of it, and the separation is what
 * makes the eventual demotion cheap: when the Huawei side becomes primary, retiring the other one is
 * deleting a directory and moving two registrations, not surgery on a live window. It also gives the
 * two windows their own task affinities, so a launcher shortcut to one never resumes the other.
 *
 * The window owns nothing. Its screens read the database and `HuaweiSyncState`, both of which
 * outlive it, so a rotation costs nothing and a sync started here survives the window closing.
 */
class HuaweiChartsActivity : ComponentActivity() {

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
        // Seeded per launch. The Huawei window keeps its OWN language setting rather than sharing
        // the Hume band's: they are two windows with two settings tasks, and one key would mean
        // switching the language in one silently switching the other.
        val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            val chartStyle = remember(themePrefs) { ChartStyle.from(themePrefs) }
            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(
                    LocalBandLanguage provides lang,
                    LocalChartStyle provides chartStyle,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val model: HuaweiDashboardModel = viewModel(
                            factory = HuaweiDashboardModelFactory(
                                OpenTaskerApp_NoHilt.db,
                                applicationContext,
                            ),
                        )
                        val state by model.state.collectAsState()
                        val progress by model.progress.collectAsState()
                        var selected by rememberSaveable(deepLink) { mutableStateOf(deepLink) }

                        val chart = state.metrics.firstOrNull { it.spec.key == selected }
                        if (chart == null) {
                            HuaweiDashboardScreen(
                                state = state,
                                progress = progress,
                                contentPadding = insets,
                                onSync = model::sync,
                                onOpenMetric = { selected = it },
                            )
                        } else {
                            HuaweiMetricDetailScreen(
                                chart = chart,
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
        const val EXTRA_METRIC = "shiroikuma.jiyusagyoban.extra.HUAWEI_METRIC"

        fun open(context: Context, metric: String?) {
            context.startActivity(
                Intent(context, HuaweiChartsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    if (!metric.isNullOrBlank()) putExtra(EXTRA_METRIC, metric)
                },
            )
        }
    }
}
