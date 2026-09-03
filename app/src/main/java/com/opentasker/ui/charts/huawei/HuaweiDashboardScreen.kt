package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiStatus
import com.opentasker.core.huawei.HuaweiSyncProgress
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.charts.MetricPreviewCard
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.DailySummaryCard
import com.opentasker.ui.charts.HealthIndexCard
import com.opentasker.ui.charts.RecoveryCard
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.charts.SubHeading
import com.opentasker.ui.charts.rememberCrosshairState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Huawei band's report.
 *
 * Shares the Hume screen's one structural claim — **one viewport and one crosshair across every
 * card** — because a stacked column of health charts is only worth stacking if it can be read
 * across, and it cannot be if each chart is on its own clock.
 *
 * What it does not share is the Hume screen's content. There is no index and no recovery here, and
 * that absence is stated in a card rather than shown as an empty one: an empty card implies the band
 * is failing to deliver something we ask for, when in fact we do not ask yet.
 */
/** The key the sleep card opens under — it has no MetricSpec of its own. */
const val SLEEP_KEY = "hw:sleep"

/**
 * The 機能訓練 page's own key in the same `selected` slot the metric detail screens use.
 *
 * Prefixed like every other Huawei key so a deep link can name it and it cannot collide with a Hume
 * one — see `HuaweiKeys` for why that prefix is permanent.
 */
const val REHAB_KEY = "hw:rehab"

@Composable
fun HuaweiDashboardScreen(
    state: HuaweiDashboardState,
    progress: HuaweiSyncProgress,
    contentPadding: PaddingValues,
    onSync: () -> Unit,
    onOpenMetric: (String) -> Unit,
    onFelt: (Int) -> Unit = {},
    /** Open the note editor for the morning the card is asking about. */
    onNote: () -> Unit = {},
    onOpenRegister: () -> Unit = {},
    /** 機能訓練: tick a day of the cut-out, or open the full calendar. */
    onTapRehabDay: (Long) -> Unit = {},
    onOpenRehab: () -> Unit = {},
) {
    val lang = LocalBandLanguage.current
    val style = LocalChartStyle.current

    val viewport = remember(state.bounds, style.defaultSpanMs) {
        ChartViewport(
            initialEndMs = state.bounds.last.takeIf { it > 0 } ?: System.currentTimeMillis(),
            initialSpanMs = style.defaultSpanMs,
        )
    }
    val crosshair = rememberCrosshairState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(style.cardGap),
    ) {
        item("sync") { SyncHeader(state, progress, onSync) }

        // The morning rating first, before anything the band measured. 白い熊's instruction
        // (2026-08-23), and it is the right one: everything else on this page exists whether or not
        // they look at it, while this exists only if they answer — and only until the day is over.
        if (state.feltEnabled) {
            item("morning") {
                HuaweiMorningCard(
                    felt = state.felt,
                    nightLabel = state.feltMorning?.toString(),
                    onFelt = onFelt,
                    note = state.feltNote,
                    onNote = onNote,
                    // Both counts from the SAME list, so the share can never exceed the whole.
                    nights = state.nights.size,
                    rated = state.register?.rows?.count { it.felt != null } ?: 0,
                    humeNights = state.humeNights,
                    onOpenRegister = onOpenRegister,
                )
            }
        }

        // 機能訓練, directly under the morning rating (白い熊, 2026-09-03). The two belong together:
        // both are things only 白い熊 can answer, both are answered once a day, and both are worth
        // nothing if the day passes unanswered. Everything below them is what the band measured.
        item("rehab") {
            val zone = remember { java.time.ZoneId.systemDefault() }
            val today = remember(zone) { java.time.LocalDate.now(zone) }
            val from = remember(today) { rehabCutoutStart(today).toEpochDay() }
            val to = remember(today) { today.toEpochDay() }
            HuaweiRehabCard(
                days = rehabCells(from, to, state.rehabDays, state.rehabNotes),
                zone = zone,
                doneCount = (from..to).count {
                    com.opentasker.core.band.RehabLog.dateKeyOf(java.time.LocalDate.ofEpochDay(it)) in
                        state.rehabDays
                },
                totalDays = (to - from + 1).toInt(),
                onTapDay = onTapRehabDay,
                onOpen = onOpenRehab,
            )
        }

        state.recovery?.let { rec ->
            item("recovery") {
                RecoveryCard(
                    recovery = rec,
                    load = state.load,
                    sri = state.sri,
                    sleepScore = state.sleepScore,
                    peak30Cadence = null,
                    peakCadenceDay = null,
                    awakeMinutes = state.nights.lastOrNull()?.awake,
                    regime = null,
                    feltToday = state.felt,
                    feltNight = state.feltMorning,
                    recordedNight = null,
                    // The rating lives in ONE place on this screen — the pill at the top. Offering
                    // it again here would be two controls writing the same value, and a reader
                    // would reasonably wonder whether they meant different things.
                    feltEnabled = false,
                    onFelt = {},
                    registerNights = state.register?.rows?.size ?: 0,
                    registerRated = state.register?.rows?.count { it.felt != null } ?: 0,
                    onOpenRegister = onOpenRegister,
                    onClick = onOpenRegister,
                )
            }
        }

        state.index?.let { idx -> item("index") { HealthIndexCard(idx) { onOpenMetric("index") } } }

        state.message?.let { msg -> item("message") { SectionCard { BodyText(msg[lang]) } } }

        // Then the two act-on-them cards, in 白い熊's order: steps, then sleep, then the rest.
        // Steps and sleep first because they are the two you can do something about; the readings
        // that merely happen to you follow. Sleep sits between steps and the heart rate rather than
        // at the end, which is also what keeps the blue and the aqua off each other.
        state.metrics.firstOrNull { it.spec.key == HuaweiKeys.STEPS }?.let { chart ->
            item(chart.spec.key) {
                MetricPreviewCard(chart, viewport, crosshair) { onOpenMetric(chart.spec.key) }
            }
        }
        item("sleep") {
            HuaweiSleepCard(state.sleep, viewport, crosshair) { onOpenMetric(SLEEP_KEY) }
        }
        items(
            state.metrics.filter { it.spec.key != HuaweiKeys.STEPS },
            key = { it.spec.key },
        ) { chart ->
            MetricPreviewCard(chart, viewport, crosshair) { onOpenMetric(chart.spec.key) }
        }

        // The day table, back across the whole history — the Huawei era and the Hume one before it.
        // Below the charts because it answers a different question: a chart is for seeing a shape, a
        // day row is for reading a number off a date.
        if (state.days.isNotEmpty()) item("days") { DailySummaryCard(state.days) }

        if (state.coverage.isNotEmpty()) item("coverage") { CoverageCard(state.coverage) }
        item("diagnostics") { DiagnosticsCard(state) }
    }
}

/**
 * The same shape as the Hume band's header (白い熊, 2026-08-22): the sync button top right with the
 * battery under it, and the status text filling the column to its left. Two reports that answer the
 * same question should not need to be read two different ways.
 */
@Composable
private fun SyncHeader(
    state: HuaweiDashboardState,
    progress: HuaweiSyncProgress,
    onSync: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val axisInk = LocalChartStyle.current.axisText
    val s = state.status
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        HuaweiText.lastSync[lang],
                        style = MaterialTheme.typography.labelMedium,
                        color = axisInk,
                    )
                    Text(
                        s?.lastSuccessAtMillis?.let(::stamp) ?: HuaweiText.neverSynced[lang],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    // A FLOOR, never a capacity — the Hume band's headroom line sits in this exact
                    // position and IS a measured depth, so the wording has to carry the difference.
                    Text(
                        s?.observedDepthHours?.let {
                            HuaweiText.observedDepth[lang].format("%.1f".format(it))
                        } ?: HuaweiText.observedDepthUnmeasured[lang],
                        style = MaterialTheme.typography.bodySmall,
                        color = axisInk,
                    )
                    s?.firmware?.let {
                        Text(
                            "${HuaweiText.firmware[lang]} $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = axisInk,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SyncButton(progress.running, state.bound, onSync)
                    HuaweiBattery(s)
                }
            }

            if (progress.running || progress.phase == "done") SyncProgressRow(progress)

            if ((s?.lastMissingCount ?: 0) > 0) {
                Text(
                    HuaweiText.missingRecords[lang].format(s!!.lastMissingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChartPalette.BAND_CRITICAL,
                )
            }
        }
    }
}

/**
 * The band's charge, under the sync button.
 *
 * Read only while a sync is connected, so it is never fresher than the last one — which is why its
 * age is printed rather than assumed. "54 %" that turns out to be from yesterday is worse than no
 * number, because it reads as current.
 */
@Composable
private fun HuaweiBattery(status: HuaweiStatus?) {
    val lang = LocalBandLanguage.current
    val axisInk = LocalChartStyle.current.axisText
    val pct = status?.batteryPct ?: return
    val tint = when {
        pct <= 15 -> ChartPalette.BAND_CRITICAL
        pct <= 30 -> ChartPalette.BAND_WARN
        else -> ChartPalette.BAND_GOOD
    }
    val ageHours = status.batteryAgeHours(System.currentTimeMillis())
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                HuaweiText.battery[lang],
                style = MaterialTheme.typography.labelSmall,
                color = axisInk,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$pct%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint,
            )
        }
        Box(
            Modifier
                .width(72.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(axisInk.copy(alpha = 0.25f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
        }
        if (ageHours != null) {
            Text(
                HuaweiText.ago[lang].format("%.1f".format(ageHours)),
                style = MaterialTheme.typography.labelSmall,
                color = axisInk,
            )
        }
    }
}

@Composable
private fun SyncButton(running: Boolean, bound: Boolean, onSync: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (running) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            .clickable(enabled = !running, onClick = onSync)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                if (bound) HuaweiText.syncNow[LocalBandLanguage.current]
                else HuaweiText.pairFirst[LocalBandLanguage.current],
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun SyncProgressRow(progress: HuaweiSyncProgress) {
    val lang = LocalBandLanguage.current
    val axisInk = LocalChartStyle.current.axisText
    // A seconds counter, because a phase that sits still for eight seconds looks stuck even when it
    // is working perfectly — and this band's handshake genuinely takes that long.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.running) {
        while (progress.running) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val seconds = if (progress.startedAtMillis > 0) (now - progress.startedAtMillis) / 1000 else 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            buildString {
                append(phaseLabel(progress.phase)[lang])
                if (progress.windowCount > 0) {
                    append(" (${progress.windowIndex}/${progress.windowCount})")
                }
                if (progress.recordCount > 0) {
                    append(" · ${progress.recordIndex}/${progress.recordCount}")
                }
                if (progress.running) append(" · ${seconds}s")
                if (progress.inserted > 0) append(" · ${progress.inserted}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = axisInk,
        )
        if (progress.running) {
            LinearProgressIndicator(
                progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            )
        }
    }
}

@Composable
private fun CoverageCard(rows: List<HuaweiCoverage>) {
    val lang = LocalBandLanguage.current
    SectionCard(accent = ChartPalette.SPO2) {
        SectionTitle(HuaweiText.coverageTitle[lang], ChartPalette.SPO2)
        BodyText(HuaweiText.coverageWhy[lang])
        rows.forEach { c ->
            val spec = HuaweiMetricSpecs.byKey(c.key)
            SubHeading(spec?.label?.get(lang) ?: c.key)
            if (c.samples == 0) {
                NoteText(HuaweiText.covNothing[lang])
            } else if (c.observedCadenceSec == null) {
                NoteText("${c.samples} ${HuaweiText.covSamples[lang]} · ${HuaweiText.covNeedMore[lang]}")
            } else {
                NoteText(
                    buildString {
                        append("${c.samples} ${HuaweiText.covSamples[lang]}")
                        append("   ${HuaweiText.covCadence[lang]} ${secs(c.observedCadenceSec!!)}")
                        c.p90GapSec?.let { append("   ${HuaweiText.covP90[lang]} ${secs(it)}") }
                        c.longestGapSec?.let { append("   ${HuaweiText.covLongest[lang]} ${secs(it)}") }
                        c.density?.let {
                            append("   ${HuaweiText.covDensity[lang]} ${(it * 100).toInt()}%")
                        }
                    },
                )
            }
        }
        NoteText(HuaweiText.covDensityNote[lang])
    }
}

@Composable
private fun DiagnosticsCard(state: HuaweiDashboardState) {
    val lang = LocalBandLanguage.current
    var open by remember { mutableStateOf(false) }
    SectionCard(accent = ChartPalette.AXIS_TEXT, onClick = { open = !open }) {
        SectionTitle(HuaweiText.diagnosticsTitle[lang], ChartPalette.AXIS_TEXT)
        if (!open) {
            NoteText(HuaweiText.diagnosticsWhy[lang])
        } else {
            BodyText(HuaweiText.diagnosticsWhy[lang])
            NoteText(HuaweiText.rawUnitsWarning[lang], warn = true)
            state.diagnostics.forEach { chart ->
                SubHeading(chart.spec.label[lang])
                NoteText(chart.headline)
            }
            SubHeading(HuaweiText.unknownFields[lang])
            if (state.unknownFields.isEmpty()) {
                NoteText(HuaweiText.noUnknownFields[lang])
            } else {
                // A TABLE, not N charts. The question these answer is "bit 0x10 started appearing
                // on the 3rd", which is a row.
                state.unknownFields.forEach { f ->
                    NoteText(
                        HuaweiText.unknownFieldRow[lang]
                            .format(f.storageKey.removePrefix("unknown_"), f.samples),
                    )
                }
            }
            NoteText(HuaweiText.notImplemented[lang])
        }
    }
}

private fun phaseLabel(phase: String) = when (phase) {
    "connecting" -> HuaweiText.phaseConnecting
    "handshake" -> HuaweiText.phaseHandshake
    "device" -> HuaweiText.phaseDevice
    "counting" -> HuaweiText.phaseCounting
    "reading" -> HuaweiText.phaseReading
    "writing" -> HuaweiText.phaseWriting
    "serving" -> HuaweiText.phaseServing
    "done" -> HuaweiText.phaseDone
    else -> HuaweiText.phaseStarting
}

private fun secs(v: Int): String = when {
    v < 90 -> "${v}s"
    v < 5_400 -> "${v / 60}m"
    else -> "%.1fh".format(v / 3_600.0)
}

private val STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun stamp(millis: Long): String = STAMP.format(Instant.ofEpochMilli(millis))
