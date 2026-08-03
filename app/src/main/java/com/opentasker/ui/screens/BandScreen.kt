package com.opentasker.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.band.BAND_CADENCE_SEC
import com.opentasker.core.band.BandCapacityEstimate
import com.opentasker.core.band.BandCensus
import com.opentasker.core.band.BandMetric
import com.opentasker.core.band.BandSettings
import com.opentasker.core.band.BandStream
import com.opentasker.core.band.BandStreamStat
import com.opentasker.core.band.BandSyncArgs
import com.opentasker.core.band.BandSyncEngine
import com.opentasker.core.band.BandSyncProgress
import com.opentasker.core.band.BandSyncRequest
import com.opentasker.core.band.BandSyncState
import com.opentasker.core.band.BandSyncSummary
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.BandSyncEntity
import com.opentasker.ui.charts.ChartPipeline
import com.opentasker.ui.charts.ChartPoint
import com.opentasker.ui.charts.ChartQualify
import com.opentasker.ui.charts.ChartViewport
import com.opentasker.ui.charts.MetricSpecs
import com.opentasker.ui.charts.QualifiedChunk
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「健康」 — the band's data, and the instrument that measures how much of it we can still reach.
 *
 * Laid out as a LazyColumn of sections from the start, deliberately: the charts are a separate
 * hand-off and land BELOW the sync controls and the census, so the structure should not need
 * rearranging when they arrive.
 *
 * Follows ContextInspectorScreen — ViewModel + Factory + Composable in one file, taking only `db` and
 * `contentPadding`, and bypassing the 1096-line ActiveAutomationViewModel entirely.
 */

private val statsJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

data class BandStreamRow(
    val key: String,
    val records: Int,
    val inserted: Int,
    val oldestLocalTs: Long?,
    val newestLocalTs: Long?,
    val end: String,
    val error: String?,
)

data class BandScreenState(
    val lastSync: BandSyncEntity? = null,
    val recent: List<BandSyncEntity> = emptyList(),
    val streams: List<BandStreamRow> = emptyList(),
    val estimates: List<BandCapacityEstimate> = emptyList(),
    val sampleCount: Int = 0,
    val staleness: String? = null,
    val charts: BandChartData = BandChartData(),
)

class BandViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(BandScreenState())
    val state: StateFlow<BandScreenState> = _state.asStateFlow()

    val progress = BandSyncState.progress

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val recent = db.bandSyncDao().recent(14)
            val last = recent.firstOrNull { it.ok }
            val streams = last?.let { decodeStats(it).map { (key, stat) -> stat.toRow(key) } }.orEmpty()
            _state.value = BandScreenState(
                lastSync = last,
                recent = recent,
                streams = streams,
                estimates = BandCensus.summarize(recent.reversed().map { it.toSummary(recent) }),
                sampleCount = db.bandSampleDao().count(),
                staleness = stalenessWarning(streams),
                charts = loadCharts(),
            )
        }
    }

    /**
     * S1–S3 for every line metric, off the UI thread.
     *
     * One-shot queries, deliberately not Room `Flow`s: invalidation is table-granular, so a Flow over
     * the range would re-run the whole filter chain on every insert during a sync — thousands of
     * times. This is called once per refresh, which is once per completed sync.
     */
    private suspend fun loadCharts(): BandChartData = withContext(Dispatchers.Default) {
        val dao = db.bandSampleDao()
        val oldest = dao.oldestEpochMs()
        val newest = dao.newestEpochMs()
        if (oldest == null || newest == null || newest <= oldest) return@withContext BandChartData()

        // Load the whole retained history: three days of every metric is ~6,700 values a day, about
        // 80 KB. Windowing this would buy nothing and would make panning stutter at the seams.
        val from = oldest
        val to = newest + 1

        // The interleaved-heart-rate split. The band writes two populations into `hr`: a 120 s
        // periodic series, and an extra reading taken at each SpO₂ measurement under a different
        // measurement mode. Measured on real data the second runs +7.46 bpm high, and merged they
        // make a sawtooth that would consume Hampel's whole rejection budget.
        //
        // The rule is exact: an `hr` row is interleaved exactly when an SpO₂ row shares its
        // timestamp. (Both were written by the same sync in the same zone, so matching on epochMs is
        // equivalent to matching on the band's own localTs.)
        val spo2Rows = dao.rangeAsc(BandMetric.SPO2, from, to)
        val spo2Times = spo2Rows.mapTo(HashSet()) { it.epochMs }

        val chunks = mutableMapOf<String, QualifiedChunk>()
        var spots: List<ChartPoint> = emptyList()
        var total = 0

        for (spec in MetricSpecs.LINES) {
            val rows = if (spec.key == BandMetric.SPO2) spo2Rows else dao.rangeAsc(spec.key, from, to)
            if (rows.isEmpty()) continue
            var points = rows.map { ChartPoint(it.epochMs, it.value) }
            if (spec.key == BandMetric.HEART_RATE) {
                val (periodic, coincident) = ChartQualify.splitHeartRate(points, spo2Times)
                points = periodic
                spots = coincident
            }
            total += points.size
            chunks[spec.key] = ChartPipeline.qualifyAndSegment(points, spec)
        }

        BandChartData(
            chunks = chunks,
            heartRateSpots = spots,
            bounds = oldest..newest,
            totalSamples = total,
        )
    }

    /**
     * Sync from the tab.
     *
     * Launched on the engine's APPLICATION scope, not viewModelScope: leaving the screen must not
     * cancel a sync in flight.
     *
     * The state is armed HERE, on the main thread, rather than in the engine: everything up to the
     * GATT connect — the coroutine hop, the settings read, the last-success query — happens before
     * the engine can say anything, and connecting itself takes seconds. Armed first, the press is
     * visible in the frame it lands in.
     */
    fun syncNow() {
        if (!BandSyncState.arm()) return
        BandSyncEngine.scope.launch {
            val request = try {
                BandSyncRequest(
                    address = BandSettings.address(appContext),
                    from = BandSyncArgs.resolve(
                        from = com.opentasker.core.band.BandFrom.Auto,
                        lastSuccessAtMillis = db.bandSyncDao().lastSuccessful()?.startedAt,
                        overlapMinutes = BandSettings.overlapMinutes(appContext),
                        now = LocalDateTime.now(),
                    ),
                    streams = BandSettings.streams(appContext),
                    timeoutSec = BandSettings.timeoutSec(appContext),
                    backup = true,
                    backupDir = BandSettings.backupDir(appContext),
                    source = "tab",
                )
            } catch (e: Exception) {
                // The engine finishes the state on every path of its own, but it was armed before
                // this pre-flight ran — so a throw here is the one way it could be left spinning.
                BandSyncState.finish(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            BandSyncEngine.sync(context = appContext, db = db, request = request)
            refresh()
        }
    }

    private fun decodeStats(row: BandSyncEntity): Map<String, BandStreamStat> =
        runCatching { statsJson.decodeFromString<Map<String, BandStreamStat>>(row.statsJson) }
            .getOrDefault(emptyMap())

    private fun BandSyncEntity.toSummary(all: List<BandSyncEntity>): BandSyncSummary {
        val previous = all.filter { it.startedAt < startedAt }.maxByOrNull { it.startedAt }
        val gapHours = previous?.let { (startedAt - it.startedAt) / 3_600_000.0 } ?: 0.0
        return BandSyncSummary(startedAt, gapHours, decodeStats(this))
    }

    /**
     * Warn when the oldest record the band still holds is close to the measured floor — that is the
     * signal that the next gap risks losing data for good.
     */
    private fun stalenessWarning(streams: List<BandStreamRow>): String? {
        val worst = streams.filter { it.oldestLocalTs != null && BAND_CADENCE_SEC.containsKey(it.key) }
            .minByOrNull { it.oldestLocalTs!! } ?: return null
        val hours = hoursSince(worst.oldestLocalTs!!) ?: return null
        return if (hours < 24) {
            "The band's oldest %s record is only %.0f h old — sync more often than that or data is lost."
                .format(worst.key, hours)
        } else {
            null
        }
    }
}

class BandViewModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BandViewModel::class.java)) {
            return BandViewModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private fun BandStreamStat.toRow(key: String) = BandStreamRow(
    key = key,
    records = records,
    inserted = inserted,
    oldestLocalTs = oldestLocalTs,
    newestLocalTs = newestLocalTs,
    end = end,
    error = error,
)

/** Hours between a band localTs and now, or null if it is not a readable timestamp. */
private fun hoursSince(localTs: Long): Double? = runCatching {
    val at = LocalDateTime.of(
        (localTs / 10_000_000_000L).toInt(),
        ((localTs / 100_000_000L) % 100).toInt(),
        ((localTs / 1_000_000L) % 100).toInt(),
        ((localTs / 10_000L) % 100).toInt(),
        ((localTs / 100L) % 100).toInt(),
        (localTs % 100).toInt(),
    )
    java.time.Duration.between(at, LocalDateTime.now()).toMinutes() / 60.0
}.getOrNull()

private fun formatLocalTs(localTs: Long?): String {
    if (localTs == null || localTs <= 0) return "—"
    return "%04d-%02d-%02d %02d:%02d".format(
        localTs / 10_000_000_000L,
        (localTs / 100_000_000L) % 100,
        (localTs / 1_000_000L) % 100,
        (localTs / 10_000L) % 100,
        (localTs / 100L) % 100,
    )
}

private fun formatMillis(millis: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .format(java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/**
 * The 「健康」 view. Hosted by [com.opentasker.ui.charts.BandChartsActivity] — its own fullscreen
 * window, opened by a task, not a tab inside the automation editor.
 *
 * The charts come FIRST: 白い熊 opens this to look at the data, and the sync controls and the buffer
 * census are what you scroll down to when you want to know how the data got there.
 */
@Composable
fun BandScreen(
    db: AppDatabase,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Show only this metric key, for a shortcut that opens straight onto one chart. */
    onlyMetric: String? = null,
    /** Initial visible span. Null means the 24-hour default. */
    initialSpanMinutes: Int? = null,
) {
    val context = LocalContext.current.applicationContext
    val factory = remember(db, context) { BandViewModelFactory(db, context) }
    val viewModel: BandViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    val specs = remember(onlyMetric) {
        if (onlyMetric == null) MetricSpecs.LINES else MetricSpecs.LINES.filter { it.key == onlyMetric }
    }

    // ONE viewport shared by every chart. The whole value of a stacked column of health charts is
    // cross-reading — "HR spiked at 03:12, was I in REM?" — and per-chart zoom destroys that.
    // Re-anchored to the newest sample the first time data arrives, then left under 白い熊's control.
    val viewport = remember {
        ChartViewport(
            initialEndMs = System.currentTimeMillis(),
            initialSpanMs = initialSpanMinutes?.let { it * 60_000L } ?: ChartViewport.DEFAULT_SPAN_MS,
        )
    }
    val newestSample = state.charts.bounds.last
    var anchored by remember { mutableStateOf(false) }
    LaunchedEffect(newestSample) {
        if (!anchored && newestSample > 0L) {
            viewport.jumpTo(newestSample, state.charts.bounds)
            anchored = true
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        item {
            BandChartsCard(
                data = state.charts,
                specs = specs,
                viewport = viewport,
                zone = zone,
            )
        }
        item { BandSyncCard(state, progress, viewModel::syncNow) }
        state.staleness?.let { warning -> item { BandStalenessCard(warning) } }
        item { BandStreamsCard(state.streams) }
        item { BandCapacityCard(state.estimates) }
        item { BandCensusCard(state.recent) }
    }
}

/** The phase names the engine publishes, in 白い熊's language. */
private fun phaseLabel(phase: String): String = when (phase) {
    "starting" -> "準備中"
    "connecting" -> "接続中"
    "device" -> "機器情報"
    "reading" -> "読み出し"
    "done" -> "完了"
    else -> phase
}

@Composable
private fun BandSyncCard(
    state: BandScreenState,
    progress: BandSyncProgress,
    onSync: () -> Unit,
) {
    val running = progress.running

    // The counter. It exists because connecting is seconds of silence with nothing to report a
    // percentage about: a number that visibly climbs is the proof the press landed, and it keeps
    // proving it for as long as the band takes to answer.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            nowMillis = System.currentTimeMillis()
            delay(250)
        }
    }
    val elapsedSec = if (progress.startedAtMillis == 0L) {
        0
    } else {
        ((nowMillis - progress.startedAtMillis) / 1000).coerceAtLeast(0)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("健康 — バンド同期", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val last = state.lastSync
            Text(
                if (last == null) {
                    "No sync yet."
                } else {
                    "Last: ${formatMillis(last.startedAt)} · ${last.message}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            last?.let {
                Text(
                    "Band: ${it.firmware ?: "?"} · battery ${it.battery ?: "?"}% · MTU ${it.mtu ?: "?"} · ${it.address}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("${state.sampleCount} samples stored", style = MaterialTheme.typography.bodySmall)
            if (running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "同期中 — ${phaseLabel(progress.phase)} · ${elapsedSec}秒",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // Determinate only once a stream is actually being read; before that a 0% bar is
                // indistinguishable from an empty one, which is what "nothing happens" looked like.
                if (progress.percent > 0) {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.stream} — ${progress.streamIndex + 1}/${progress.streamCount}" +
                            " · ${progress.percent}% · ${progress.records}件",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (progress.message.isNotEmpty()) {
                Text(progress.message, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onSync, enabled = !running) {
                Text(if (running) "同期中… ${elapsedSec}秒" else "Sync now")
            }
        }
    }
}

@Composable
private fun BandStalenessCard(warning: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("⚠ ${warning}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BandStreamsCard(streams: List<BandStreamRow>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Streams — last sync", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (streams.isEmpty()) {
                Text("Nothing read yet.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            streams.forEach { row ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.key, modifier = Modifier.width(90.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${row.inserted} new / ${row.records}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(row.error ?: row.end, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${formatLocalTs(row.oldestLocalTs)} → ${formatLocalTs(row.newestLocalTs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BandCapacityCard(estimates: List<BandCapacityEstimate>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Buffer depth — measured", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "The band overwrites its oldest records. These bounds close in as syncs happen at " +
                    "varied gaps: a clean gap is a floor, a lossy one a ceiling.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (estimates.isEmpty()) {
                Text("Not enough syncs yet.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            estimates.forEach { e ->
                Row {
                    Text(e.stream, modifier = Modifier.width(90.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(e.lowerBoundHours?.let { "≥ %.1f h".format(it) } ?: "—")
                            append("  ")
                            append(e.upperBoundHours?.let { "≤ %.1f h".format(it) } ?: "")
                            append("  (${e.confidence}, max ${e.maxRecordsSeen})")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BandCensusCard(recent: List<BandSyncEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Census — last 14 syncs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (recent.isEmpty()) {
                Text("No syncs recorded.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            recent.forEach { row ->
                Text(
                    "${formatMillis(row.startedAt)} · ${if (row.ok) "ok" else "failed"} · ${row.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
