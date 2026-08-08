package com.opentasker.ui.charts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.core.band.BandMetric
import com.opentasker.core.band.BandSettings
import com.opentasker.core.band.BandStatus
import com.opentasker.core.band.BandSyncEngine
import com.opentasker.core.band.BandSyncRequest
import com.opentasker.core.band.BandSyncState
import com.opentasker.core.band.BandLocalTime
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything the 「健康」 window reads, loaded once per refresh.
 *
 * One-shot queries, deliberately not Room `Flow`s: invalidation is table-granular, so a Flow over the
 * range would re-run the whole filter chain on every insert during a sync — thousands of times for
 * one sync. This runs once when the window opens and once when a sync finishes.
 */

/** One metric, prepared as far as the pipeline can go without knowing the viewport. */
data class MetricChart(
    val spec: MetricSpec,
    val chunk: QualifiedChunk?,
    /** Hourly extents, for the capsule renderers. Pooled where the metric says so. */
    val buckets: List<HourBucket>,
    /** Raw retained points, for the bar renderer, which must never be filtered. */
    val bars: List<ChartPoint>,
    /**
     * The band-state index's second population: records where the whole HR/BP triple failed.
     *
     * Kept apart from [chunk] because pooling the two is what manufactures its apparent 15–99 range.
     * Empty for every other metric.
     */
    val secondary: List<ChartPoint> = emptyList(),
    val headline: String,
    val headlineBand: BandRung?,
    val subtitle: Loc,
    /** One row per calendar day, newest first — printed under the full-screen chart. */
    val history: List<MetricDay> = emptyList(),
) {
    val isEmpty: Boolean
        get() = (chunk?.segments?.isEmpty() != false) && buckets.isEmpty() && bars.isEmpty() &&
            secondary.isEmpty()

    /**
     * The one time-ordered series the crosshair reads, whichever renderer this metric uses.
     *
     * A capsule metric has no line to sample, so its hourly high stands in; a bar metric reads its
     * bars directly. Defined here rather than in each caller because the preview card, the plot and
     * the full-screen readout must agree about what the crosshair is pointing at — three copies of
     * this expression is three chances for one of them to answer a different question.
     */
    val readoutPoints: List<ChartPoint> by lazy {
        chunk?.segments?.flatMap { it.points }?.plus(secondary)?.sortedBy { it.tMs }
            ?: bars.ifEmpty { buckets.map { ChartPoint(it.startMs, it.hi) } }
    }
}

data class BloodPressureChart(
    val dumbbells: List<DumbbellBucket>,
    val headline: String,
    val systolicRange: String,
    val diastolicRange: String,
) {
    val isEmpty: Boolean get() = dumbbells.isEmpty()
}

data class SleepChart(
    val sessions: List<SleepSession>,
    val latest: SleepSession?,
    val headline: Loc,
    /** One row per night, newest first — every recorded session, not just the latest. */
    val nights: List<SleepNight> = emptyList(),
) {
    val isEmpty: Boolean get() = sessions.isEmpty()
}

data class DashboardState(
    val loading: Boolean = true,
    val status: BandStatus? = null,
    val index: HealthIndexResult? = null,
    val metrics: List<MetricChart> = emptyList(),
    val bloodPressure: BloodPressureChart? = null,
    val sleep: SleepChart? = null,
    /** One row per calendar day, newest first. */
    val days: List<DaySummary> = emptyList(),
    /** The full extent of everything stored, so a viewport can pan across all of it. */
    val bounds: LongRange = 0L..0L,
    val message: Loc? = null,
)

class BandDashboardModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    val progress = BandSyncState.progress

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = load()
        }
    }

    /**
     * Start a sync, from the button.
     *
     * Armed on the MAIN thread before anything is dispatched: the GATT connect, the MTU negotiation
     * and service discovery take seconds, and until the engine says anything the screen would show
     * nothing at all. Armed first, the press is visible in the frame it lands in. That mechanism
     * predates this screen and must not be optimised away.
     */
    fun sync() {
        if (!BandSyncState.arm()) return
        BandSyncEngine.scope.launch {
            val request = try {
                BandSyncRequest(
                    address = BandSettings.address(appContext),
                    from = resolveFrom(),
                    streams = BandSettings.parseStreams(""),
                    timeoutSec = BandSettings.timeoutSec(appContext),
                    backup = true,
                    backupDir = BandSettings.backupDir(appContext),
                    source = "window",
                )
            } catch (e: Exception) {
                // The engine finishes its own state on every path, but it was armed before this
                // pre-flight ran — so a throw here is the one way it could be left spinning.
                BandSyncState.finish(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            BandSyncEngine.sync(context = appContext, db = db, request = request)
            refresh()
        }
    }

    private suspend fun resolveFrom(): BandLocalTime {
        val last = db.bandSyncDao().lastSuccessful()?.startedAt
        return BandSyncArgsBridge.resolveAuto(last, BandSettings.overlapMinutes(appContext))
    }

    private suspend fun load(): DashboardState = withContext(Dispatchers.Default) {
        val dao = db.bandSampleDao()
        val oldest = dao.oldestEpochMs()
        val newest = dao.newestEpochMs()
        if (oldest == null || newest == null || newest <= oldest) {
            return@withContext DashboardState(
                loading = false,
                status = BandSyncEngine.status(db),
                message = BandText.noData,
            )
        }
        val zone = ZoneId.systemDefault()
        val from = oldest
        val to = newest + 1

        val spo2Rows = dao.rangeAsc(BandMetric.SPO2, from, to)
        val spo2Times = spo2Rows.mapTo(HashSet()) { it.epochMs }

        // A record whose optical read failed carries no heart rate and no blood pressure — the whole
        // triple is absent together, never partially. So the presence of a systolic sample at the
        // same instant is exactly the record-type flag, and it is what splits the state index.
        val readOkTimes = dao.rangeAsc(BandMetric.SYSTOLIC, from, to).mapTo(HashSet()) { it.epochMs }

        val metrics = MetricSpecs.ALL.map { spec ->
            val rows = if (spec.key == BandMetric.SPO2) spo2Rows else dao.rangeAsc(spec.key, from, to)
            val points = rows.map { ChartPoint(it.epochMs, it.value) }
            // The day-by-day history is built from the RAW series, before the outlier filter: the
            // table under a chart reports what the band recorded, and the chart's ✕ marks are where
            // the two differ. Filtering here as well would hide the disagreement.
            buildMetric(spec, points, spo2Times, readOkTimes)
                .copy(history = MetricHistory.days(points, zone))
        }

        val sleep = loadSleep(zone)
        val bp = loadBloodPressure(dao, from, to)

        DashboardState(
            loading = false,
            status = BandSyncEngine.status(db),
            index = HealthIndexSource.compute(metrics, sleep.latest, spo2Times),
            metrics = metrics,
            bloodPressure = bp,
            sleep = sleep,
            days = DailySummary.build(
                hr = metrics.firstOrNull { it.spec.key == BandMetric.HEART_RATE }
                    ?.let { it.chunk?.segments?.flatMap { s -> s.points } }.orEmpty(),
                spo2 = metrics.firstOrNull { it.spec.key == BandMetric.SPO2 }
                    ?.let { it.chunk?.segments?.flatMap { s -> s.points } }.orEmpty(),
                steps = metrics.firstOrNull { it.spec.key == BandMetric.STEPS_MINUTE }?.bars.orEmpty(),
                sleepSessions = sleep.sessions,
                spo2Times = spo2Times,
                zone = zone,
            ),
            bounds = oldest..newest,
        )
    }

    private fun buildMetric(
        spec: MetricSpec,
        points: List<ChartPoint>,
        spo2Times: Set<Long>,
        readOkTimes: Set<Long>,
    ): MetricChart {
        if (points.isEmpty()) {
            return MetricChart(
                spec = spec, chunk = null, buckets = emptyList(), bars = emptyList(),
                headline = "—", headlineBand = null, subtitle = BandText.noReadings,
            )
        }
        if (spec.key == BandMetric.STRESS || spec.key == BandMetric.HRV) {
            return buildStateIndex(spec, points, readOkTimes)
        }
        // Heart rate carries two populations — a periodic series and an extra reading taken at each
        // SpO₂ measurement, +7.46 bpm higher. A LINE has to split them or it draws a sawtooth. A
        // CAPSULE must not: the envelope is the hour's real extent, and dropping the coincident
        // readings would clip the top off every capsule (Hume's own day range matches the pooled
        // population). So the choice follows the mark, and the gap analysis has to follow the same
        // series it is drawn over — tinting pooled capsules with the periodic series' gaps would
        // report an absence in stretches that plainly have data.
        val forChart = if (spec.key == BandMetric.HEART_RATE && spec.render == RenderKind.LINE) {
            ChartQualify.splitHeartRate(points, spo2Times).first
        } else {
            points
        }

        // The Hampel filter is an instrument for smoothing a LINE, and it assumes one population.
        // Pointed at the pooled heart-rate series it flags the interleaving itself — the +7.46 bpm
        // second population looks like a sawtooth of outliers, and it burns the whole rejection
        // budget on readings that are perfectly real (102 of them, on 白い熊's own data). An hourly
        // envelope needs no such filter anyway: its ends are two real readings, not a curve through
        // them. So capsules keep the range and sentinel gates and skip Hampel entirely.
        val chartSpec = if (spec.render == RenderKind.CAPSULE) spec.copy(hampelHalfWindow = 0) else spec
        val chunk = if (spec.render == RenderKind.BARS) null else {
            ChartPipeline.qualifyAndSegment(forChart, chartSpec, mixedCadence = spec.mixedCadence)
        }
        val buckets = if (spec.render == RenderKind.CAPSULE) HourlyEnvelope.bucket(points) else emptyList()
        val bars = if (spec.render == RenderKind.BARS) points else emptyList()

        // The headline summarises the SAME window the health index scores — the last 24 hours — and
        // for a line metric it is the MEDIAN rather than the latest reading. A single latest sample of
        // a noisy series (HRV moves between 15 and 90 within a day) says almost nothing, and worse, it
        // let the card and the index disagree about the same metric.
        val recent = HealthIndexSource.lastDay(points).ifEmpty { points.takeLast(RECENT_FOR_HEADLINE) }
        val lo = recent.minOf { it.value }
        val hi = recent.maxOf { it.value }
        val typical = HealthIndexSource.median(recent.map { it.value }) ?: recent.last().value
        // Steps are a DAILY total, so the headline is today's — summing the whole archive would read
        // "38 505 歩 · とても高い" for a week's walking and mean nothing.
        val todayFrom = points.last().tMs - 24 * 3_600_000L
        val today = points.filter { it.tMs >= todayFrom }.sumOf { it.value }
        val headline = when (spec.render) {
            RenderKind.CAPSULE -> "${spec.format(lo)}–${spec.format(hi)}"
            RenderKind.BARS -> spec.format(today)
            else -> spec.format(typical)
        }
        val forBand = when (spec.render) {
            RenderKind.BARS -> today
            RenderKind.CAPSULE -> typical
            else -> typical
        }
        return MetricChart(
            spec = spec,
            chunk = chunk,
            buckets = buckets,
            bars = bars,
            headline = headline,
            headlineBand = spec.bandFor(forBand),
            // The footer is a feature, not debug output: 白い熊 cares that the chart stays close to
            // the measurements, so the app has to be able to PROVE what it dropped.
            subtitle = footer(
                samples = points.size,
                rejected = chunk?.rejectedPoints?.size ?: 0,
                gaps = chunk?.gaps?.size ?: 0,
                noReading = chunk?.noReading ?: 0,
            ),
        )
    }

    /** `718 samples · 3 rejected · 2 gaps`, in both languages. */
    private fun footer(samples: Int, rejected: Int, gaps: Int, noReading: Int): Loc {
        fun render(lang: BandLanguage) = buildString {
            append(BandText.samples[lang].format(samples))
            if (rejected > 0) append(" · " + BandText.rejected[lang].format(rejected))
            if (gaps > 0) append(" · " + BandText.gaps[lang].format(gaps))
            if (noReading > 0) append(" · " + BandText.noReadingCount[lang].format(noReading))
        }
        return Loc(render(BandLanguage.EN), render(BandLanguage.JA))
    }

    /**
     * The band-state index, split into its two record types.
     *
     * Drawn as two populations rather than one line because they are two populations: 1 644 records
     * where the optical read succeeded (values 15–94) and 487 where it failed (50–99). Pooled, they
     * look like one metric with a wide healthy range. Apart, they are what they are — a flag.
     *
     * No filtering: the Hampel gate assumes a single population and a smooth underlying signal, and
     * this has neither.
     */
    private fun buildStateIndex(
        spec: MetricSpec,
        points: List<ChartPoint>,
        readOkTimes: Set<Long>,
    ): MetricChart {
        val ok = points.filter { it.tMs in readOkTimes }
        val failed = points.filter { it.tMs !in readOkTimes }
        val recent = HealthIndexSource.lastDay(points)
        val typical = HealthIndexSource.median(recent.map { it.value }) ?: points.last().value
        return MetricChart(
            spec = spec,
            chunk = ChartPipeline.qualifyAndSegment(ok, spec.copy(hampelHalfWindow = 0)),
            buckets = emptyList(),
            bars = emptyList(),
            secondary = failed,
            headline = spec.format(typical),
            headlineBand = null,
            subtitle = Loc(
                "${ok.size} read OK · ${failed.size} read failed",
                "測定成功 ${ok.size} 件 ／ 測定失敗 ${failed.size} 件",
            ),
        )
    }

    private suspend fun loadBloodPressure(
        dao: com.opentasker.core.storage.BandSampleDao,
        from: Long,
        to: Long,
    ): BloodPressureChart {
        val sys = dao.rangeAsc(BandMetric.SYSTOLIC, from, to).map { ChartPoint(it.epochMs, it.value) }
        val dia = dao.rangeAsc(BandMetric.DIASTOLIC, from, to).map { ChartPoint(it.epochMs, it.value) }
        if (sys.isEmpty() && dia.isEmpty()) {
            return BloodPressureChart(emptyList(), "—", "—", "—")
        }
        val recentSys = sys.takeLast(RECENT_FOR_HEADLINE)
        val recentDia = dia.takeLast(RECENT_FOR_HEADLINE)
        fun range(v: List<ChartPoint>) =
            if (v.isEmpty()) "—" else "${v.minOf { it.value }.toInt()}–${v.maxOf { it.value }.toInt()}"
        return BloodPressureChart(
            dumbbells = HourlyEnvelope.dumbbells(sys, dia),
            headline = if (recentSys.isEmpty() || recentDia.isEmpty()) "—" else
                "${recentSys.last().value.toInt()}/${recentDia.last().value.toInt()}",
            systolicRange = range(recentSys),
            diastolicRange = range(recentDia),
        )
    }

    private suspend fun loadSleep(zone: ZoneId): SleepChart {
        val rows = db.bandSleepDao().recent(SLEEP_SEGMENTS)
        if (rows.isEmpty()) return SleepChart(emptyList(), null, Loc("—", "—"))
        val inputs = rows.mapNotNull { row ->
            val start = BandLocalTimes.toEpochMs(row.startLocalTs, zone) ?: return@mapNotNull null
            SleepSegmentInput(start, row.minutes, row.stages)
        }
        val sessions = SleepShape.sessions(inputs)
        val latest = sessions.maxByOrNull { it.endMs }
        return SleepChart(
            sessions = sessions,
            latest = latest,
            nights = MetricHistory.nights(sessions, zone),
            headline = latest?.let {
                Loc(
                    BandText.sleepDuration.en.format(it.totalMinutes / 60, it.totalMinutes % 60),
                    BandText.sleepDuration.ja.format(it.totalMinutes / 60, it.totalMinutes % 60),
                )
            } ?: Loc("—", "—"),
        )
    }

    private companion object {
        /** Enough recent samples for a headline range without turning it into a week's summary. */
        const val RECENT_FOR_HEADLINE = 40
        const val SLEEP_SEGMENTS = 400
    }
}

/** `yyyyMMddHHmmss` in the device's own zone, the same conversion the sync engine used to store it. */
object BandLocalTimes {
    fun toEpochMs(localTs: Long, zone: ZoneId): Long? = runCatching {
        LocalDateTime.of(
            (localTs / 10_000_000_000L).toInt(),
            ((localTs / 100_000_000L) % 100).toInt(),
            ((localTs / 1_000_000L) % 100).toInt(),
            ((localTs / 10_000L) % 100).toInt(),
            ((localTs / 100L) % 100).toInt(),
            (localTs % 100).toInt(),
        ).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}

/** Keeps the Action's `from=auto` semantics in one place rather than duplicating them here. */
object BandSyncArgsBridge {
    fun resolveAuto(lastSuccessAtMillis: Long?, overlapMinutes: Int): BandLocalTime =
        com.opentasker.core.band.BandSyncArgs.resolve(
            from = com.opentasker.core.band.BandFrom.Auto,
            lastSuccessAtMillis = lastSuccessAtMillis,
            overlapMinutes = overlapMinutes,
            now = LocalDateTime.now(),
        )
}

class BandDashboardModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BandDashboardModel::class.java)) {
            return BandDashboardModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
