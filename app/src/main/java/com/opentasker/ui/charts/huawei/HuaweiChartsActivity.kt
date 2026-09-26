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
                        // The morning card's note editor. Held here rather than inside the card so
                        // it survives the card scrolling out of the LazyColumn while it is open.
                        var notingMorning by rememberSaveable { mutableStateOf<Long?>(null) }
                        // 機能訓練: the day whose tick is open, and the day whose note is. Two states
                        // rather than one, for the register's reason — the note editor stacks OVER
                        // the day dialog and returns to it, so the day must stay chosen underneath.
                        var rehabDay by rememberSaveable { mutableStateOf<Long?>(null) }
                        var notingRehab by rememberSaveable { mutableStateOf<Long?>(null) }

                        // The system Back button, which until now was not wired to this screen's
                        // state at all. Every sub-screen offered a tappable header to return, and
                        // Back itself fell straight through to the activity's default and closed
                        // the whole window — 白い熊 hit it on a marker history page (2026-09-13),
                        // and it was equally true of the chart detail, the register, the sleep
                        // detail and 機能訓練 since each of those was written.
                        //
                        // Back now always means "up one level": a sub-screen returns to the report,
                        // and the report closes. That costs a window opened straight onto one metric
                        // by an external intent a second press to dismiss, because the first reveals
                        // the report underneath. Worth it for one rule that holds everywhere rather
                        // than a special case whose behaviour depends on how the window was opened.
                        //
                        // The dialogs are deliberately NOT handled here. A Compose `Dialog` owns its
                        // own window and consumes Back before this does, dismissing itself through
                        // `onDismissRequest` — which is exactly what returns the note editor to the
                        // 機能訓練 day dialog underneath it rather than closing both at once.
                        androidx.activity.compose.BackHandler(enabled = selected != null) {
                            selected = null
                        }

                        val chart = state.metrics.firstOrNull { it.spec.key == selected }
                        // A strip row's history. Encoded into the same `selected` string every other
                        // route uses, so the back stack, rememberSaveable and the existing dismissal
                        // all keep working rather than needing a parallel piece of state.
                        val openMarker = selected?.removePrefix(MARKER_PREFIX)
                            ?.takeIf { selected?.startsWith(MARKER_PREFIX) == true }
                            ?.let { name ->
                                com.opentasker.ui.charts.RecoveryMarker.entries.firstOrNull { it.name == name }
                            }
                        val registerOpen = selected == com.opentasker.ui.charts.MetricSpecs.KEY_REGISTER
                        val rehabOpen = selected == REHAB_KEY
                        val sleepOpen = selected == SLEEP_KEY
                        if (sleepOpen) {
                            HuaweiSleepDetailScreen(
                                night = state.sleep,
                                nights = state.nights,
                                cutoverMs = state.cutoverMs,
                                contentPadding = insets,
                                bounds = state.bounds,
                                onBack = { selected = null },
                            )
                        } else if (registerOpen) {
                            // The Hume side's own register screen, unchanged. One record of 白い熊's
                            // nights, reachable from either report — a second one would disagree the
                            // first time a rating was filed from the screen the other was not
                            // watching.
                            com.opentasker.ui.charts.SessionRegisterScreen(
                                register = state.register,
                                contentPadding = insets,
                                onRate = { night, step -> model.setFeltFor(night, step) },
                                onNote = { morning, text -> model.setNoteFor(morning, text) },
                                onBack = { selected = null },
                            )
                        } else if (rehabOpen) {
                            val zone = java.time.ZoneId.systemDefault()
                            val today = java.time.LocalDate.now(zone)
                            // The whole record, and never less than the register's own window — so
                            // the two calendars cover at least the same weeks and a day marked in
                            // one is findable in the other.
                            val earliest = (state.rehabDays + state.rehabNotes.keys)
                                .mapNotNull { com.opentasker.ui.charts.SessionRegister.epochDayOf(it) }
                                .minOrNull()
                            val from = minOf(
                                com.opentasker.ui.charts.RecoveryBuild.gridStart(today.toEpochDay()),
                                earliest ?: Long.MAX_VALUE,
                            )
                            val to = today.toEpochDay()
                            HuaweiRehabScreen(
                                days = rehabCells(from, to, state.rehabDays, state.rehabNotes),
                                zone = zone,
                                doneCount = state.rehabDays.size,
                                totalDays = (to - from + 1).toInt(),
                                contentPadding = insets,
                                onTapDay = { rehabDay = com.opentasker.core.band.RehabLog
                                    .dateKeyOf(java.time.LocalDate.ofEpochDay(it)) },
                                onBack = { selected = null },
                            )
                        } else if (openMarker != null) {
                            // One strip row's own history. Its own branch rather than a synthetic
                            // MetricChart: these are per-NIGHT derived values and the chart detail
                            // screen exists to clean raw sample series — see MarkerHistory.
                            val markerZone = java.time.ZoneId.systemDefault()
                            val series = state.markerHistory[openMarker]
                            if (series == null || series.nights.isEmpty()) {
                                selected = null
                            } else {
                                com.opentasker.ui.charts.MarkerHistoryScreen(
                                    series = series,
                                    zone = markerZone,
                                    contentPadding = insets,
                                    onBack = { selected = null },
                                    // The swing's page carries the curves themselves: that is what
                                    // "the history of this shape" actually means. Twice over, and
                                    // the two answer different questions — the overlay asks whether
                                    // last night's descent is like the others, the small multiples
                                    // show each night as its own picture so a particular one can be
                                    // looked at (白い熊, 2026-09-26).
                                    extra = if (openMarker == com.opentasker.ui.charts.RecoveryMarker.HR_SWING) {
                                        {
                                            com.opentasker.ui.charts.RecentCurvesCard(state.recentCurves, markerZone)
                                            com.opentasker.ui.charts.NightCurvesCard(
                                                state.recentCurves,
                                                // The SAME "usually" the report's own bar and
                                                // sentence compare against — never a second median.
                                                state.descent?.usual?.drop,
                                                markerZone,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        } else if (chart == null) {
                            HuaweiDashboardScreen(
                                state = state,
                                progress = progress,
                                contentPadding = insets,
                                onSync = model::sync,
                                onOpenMetric = { selected = it },
                                onOpenMarker = { selected = MARKER_PREFIX + it.name },
                                onFelt = model::setFelt,
                                onNote = { notingMorning = state.feltMorning ?: model.morningKeyNow() },
                                onTapRehabDay = { rehabDay = com.opentasker.core.band.RehabLog
                                    .dateKeyOf(java.time.LocalDate.ofEpochDay(it)) },
                                // Each pill opens the workout window it names. A separate
                                // activity, so the report is still underneath when it closes.
                                onOpenWorkouts = { kind ->
                                    com.opentasker.ui.charts.huawei.HuaweiWalksActivity.open(
                                        applicationContext, 30, kind,
                                    )
                                },
                                // The register opens the Hume side's own screen: it is one record
                                // of 白い熊's nights and ratings, not a per-band one, and two
                                // registers would disagree the first time a rating was filed from
                                // whichever screen the other was not watching.
                                onOpenRegister = { selected = com.opentasker.ui.charts.MetricSpecs.KEY_REGISTER },
                            )
                        } else {
                            HuaweiMetricDetailScreen(
                                chart = chart,
                                contentPadding = insets,
                                onBack = { selected = null },
                                bounds = state.bounds,
                            )
                        }

                        // The 機能訓練 day editor, and its note stacked over it — the same two-state
                        // dance the register does, so dismissing the note returns to the day rather
                        // than closing both.
                        rehabDay?.takeIf { notingRehab == null }?.let { key ->
                            HuaweiRehabDayDialog(
                                dateKey = key,
                                done = key in state.rehabDays,
                                note = state.rehabNotes[key],
                                onPick = { done ->
                                    model.setRehab(key, done)
                                    rehabDay = null
                                },
                                onEditNote = { notingRehab = key },
                                onDismiss = { rehabDay = null },
                            )
                        }
                        notingRehab?.let { key ->
                            com.opentasker.ui.charts.NoteDialog(
                                title = com.opentasker.ui.charts.nightDateFull(key, lang),
                                note = state.rehabNotes[key],
                                onSave = { text ->
                                    model.setRehabNote(key, text)
                                    notingRehab = null
                                },
                                onDismiss = { notingRehab = null },
                            )
                        }

                        // Outside the branch above, so the editor opened from the morning card is
                        // not torn down by whatever the screen behind it decides to show next.
                        notingMorning?.let { key ->
                            com.opentasker.ui.charts.NoteDialog(
                                title = com.opentasker.ui.charts.BandText.morningOfNight[lang].format(
                                    com.opentasker.ui.charts.nightDateFull(key, lang),
                                    com.opentasker.ui.charts.nightSpanLabel(key),
                                ),
                                note = state.feltNote,
                                onSave = { text ->
                                    model.setNoteFor(key, text)
                                    notingMorning = null
                                },
                                onDismiss = { notingMorning = null },
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
