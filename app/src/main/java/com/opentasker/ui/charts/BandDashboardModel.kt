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
import com.opentasker.core.band.RecoveryLog
import com.opentasker.core.band.TrainingSessions
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
    /**
     * The second measurement population, drawn as hollow spot dots and kept out of the curve.
     *
     * Heart rate only: the readings taken alongside SpO₂, which track exertion where the periodic
     * series does not. Derived from [chunk]'s retained points, so a reading the filter rejected can
     * never reappear here as a dot.
     */
    val spots: List<ChartPoint> = emptyList(),
    /**
     * The curve's own series, when the mark is a line over a SUBSET of [chunk].
     *
     * Two chunks rather than one, because the two answer different questions and the difference is
     * visible on screen. [chunk] stays **pooled** — it owns the footer counts, the rejections and
     * the gap tint, and a tint must mean "the band recorded nothing here", which is only true of the
     * pooled series (the periodic one goes quiet for ten minutes at a time in stretches that plainly
     * have spot readings in them). This one carries only the periodic samples, re-segmented at their
     * own cadence, so the curve breaks where the periodic series really stops rather than being
     * drawn through a hole.
     */
    val lineChunk: QualifiedChunk? = null,
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
    /** Last night's markers against 白い熊's own normal, and the week's load. See [Recovery]. */
    val recovery: RecoveryResult? = null,
    val load: RecoveryBuild.LoadReading? = null,
    /** Sleep Regularity Index — predicted mortality more strongly than duration. See [SleepRegularity]. */
    val sri: Double? = null,
    /** Last night on Apple's published 50/30/20 weights. */
    val sleepScore: SleepScore.Breakdown? = null,
    /** Mean of the day's 30 highest step-count minutes — NHANES norm 71.1. */
    val peak30Cadence: Double? = null,
    /** The day [peak30Cadence] belongs to, so the row can say so rather than implying "today". */
    val peakCadenceDay: Long? = null,
    /** Travel / altitude, annotated rather than silently corrected. */
    val regime: RecoveryRegime.Regime? = null,
    /** Every marked session paired with the night after it — see [SessionRegister]. */
    val register: SessionRegister.Register? = null,
    /**
     * The self-rating for [feltNight], if given — the third counted marker. Null means that night has
     * not been rated. It is deliberately NOT "today's": in the morning the night being rated started
     * yesterday, and labelling it "Today" is what let a stale entry pass for last night's answer.
     */
    val feltToday: Int? = null,
    /** `yyyyMMdd` start date of the night [feltToday] belongs to, so the row can name it. */
    val feltNight: Long? = null,
    /**
     * `yyyyMMdd` start date of the night the markers describe — null when none is on record.
     *
     * Carried beside [feltNight] rather than assumed equal to it: with the band off the wrist they
     * are two different nights, and the card has to be able to say so. See [RecoveryBuild.ratableNight].
     */
    val recordedNight: Long? = null,
    val feltEnabled: Boolean = true,
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

        val today = LocalDate.now(zone)
        val zoneOffsetMs = zone.rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
        // One definition, two readers: the recovery build's own clock arithmetic, and the nap test
        // that keeps a daytime sleep out of the recovery baseline.
        val minuteOfDayOf: (Long) -> Double = { ms -> SleepShape.minuteOfDay(ms, zone).toDouble() }

        // Before anything reads a rating: the store changed what a key MEANS on 2026-08-16, and only
        // the recorded nights can say where each one moves. This is the one place that holds both, and
        // it runs before RecoveryLog.all() below rather than after, so nothing is ever assembled from
        // a half-migrated store. It is a no-op after the first successful run.
        //
        // 400 sleep segments are loaded above — over a month of nights, where the oldest rating on
        // file is days old — so the map is comfortably wider than the store it has to place. A
        // rating older than that window would be left where it is and counted, not guessed at.
        RecoveryLog.migrateToMorningKeys(
            appContext,
            RecoverySource.nights(sleep.sessions, minuteOfDayOf).associate {
                localDateKeyOf(it.startMs, zone) to localDateKeyOf(it.endMs, zone)
            },
        ).let { unresolved ->
            if (unresolved > 0) {
                com.opentasker.core.logging.AppLogger.warn(
                    "RecoveryLog",
                    "$unresolved rating(s) had no recorded night to place them by; left on their old key",
                )
            }
        }

        val assembled = RecoveryBuild.build(
            metrics = metrics,
            sessions = sleep.sessions,
            ratings = RecoveryLog.all(appContext),
            notes = com.opentasker.core.band.DayNotes.RECOVERY.all(appContext),
            sessions_ = TrainingSessions.all(appContext),
            sessionOpen = TrainingSessions.openStart(appContext) != null,
            localDateOf = { ms -> localDateKeyOf(ms, zone) },
            zoneOffsetMs = zoneOffsetMs,
            todayEpochDay = (System.currentTimeMillis() + zoneOffsetMs) / 86_400_000L,
            nowMs = System.currentTimeMillis(),
            offsetsByDay = offsetsByDay(sleep.sessions, zone),
            minuteOfDayOf = minuteOfDayOf,
        )

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
            recovery = assembled.recovery,
            load = assembled.load,
            sri = assembled.sri,
            sleepScore = assembled.sleepScore,
            peak30Cadence = assembled.peak30Cadence,
            peakCadenceDay = assembled.peakCadenceDay,
            regime = assembled.regime,
            register = assembled.register,
            // Read back by the same key it is written under, so the buttons show what the marker is
            // actually using. Both sides go through feltKey for that reason: the read used to fall
            // through to today's calendar date whenever the night had no rating — `?.let {}` yields
            // null for "no recovery" and for "no rating alike" — which is a key nothing writes any
            // more, so the only thing it could ever surface was a stale pre-night-keying entry.
            feltNight = feltKey(zone),
            feltToday = RecoveryLog.rating(appContext, feltKey(zone)),
            recordedNight = recordedMorningKey(assembled.recovery?.nightEndMs, zone),
            feltEnabled = RecoveryLog.enabled(appContext),
        )
    }

    /**
     * Record a session 白い熊 marked on the chart after the fact.
     *
     * The same store the live toggle writes to, so a retroactive mark and a bookended one are the
     * same kind of thing downstream — and the same length guards apply, checked here rather than in
     * the screen so the rule lives in one place.
     */
    fun markSession(startMs: Long, endMs: Long): Boolean {
        val minutes = (endMs - startMs) / 60_000L
        if (minutes < TrainingSessions.MIN_SESSION_MINUTES || minutes > TrainingSessions.MAX_OPEN_MINUTES) {
            return false
        }
        TrainingSessions.log(appContext, TrainingSessions.Session(startMs, endMs, "後から"))
        refresh()
        return true
    }

    /**
     * The device's UTC offset on each recorded day, for the travel check.
     *
     * Derived from the sleep sessions rather than stored per day: a session's own instant resolved in
     * the current zone gives the offset that applied then, so a flight shows up as a step between two
     * consecutive nights without any new persistence.
     */
    private fun offsetsByDay(sessions: List<SleepSession>, zone: ZoneId): Map<Long, Int> =
        sessions.associate { s ->
            val instant = java.time.Instant.ofEpochMilli(s.startMs)
            val offsetMin = zone.rules.getOffset(instant).totalSeconds / 60
            (s.startMs / 86_400_000L) to offsetMin
        }

    /** `yyyyMMdd`, the local-date key shape the band's own daily records use. */
    private fun localDateKey(date: LocalDate): Long =
        date.year * 10_000L + date.monthValue * 100L + date.dayOfMonth

    /** `yyyyMMdd` of an instant in [zone]. */
    private fun localDateKeyOf(ms: Long, zone: ZoneId): Long =
        localDateKey(java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())

    /**
     * The morning a score is filed under: today's, always. See [RecoveryBuild.ratableMorning] for why
     * it needs nothing from the band — the rule lives there because it is pure and therefore testable,
     * where this is not.
     */
    private fun feltKey(zone: ZoneId): Long =
        RecoveryBuild.ratableMorning(java.time.LocalDateTime.now(zone))

    /** `yyyyMMdd` morning of the night the card's markers describe, or null if none is on record. */
    private fun recordedMorningKey(nightEndMs: Long?, zone: ZoneId): Long? =
        nightEndMs?.let { localDateKeyOf(it, zone) }

    /**
     * Record how 白い熊 woke this morning, then reload so the counting rule picks it up at once.
     *
     * Filed under this morning — see [RecoveryBuild.ratableMorning]. It takes nothing from the band on
     * purpose: the morning after a night the band missed is exactly the one most likely to need typing
     * in by hand, and the old rule, which read the key off the last recorded night, could not offer it
     * at all. (白い熊, 2026-08-16.)
     */
    fun setFeltToday(rating: Int) = setFelt(feltKey(ZoneId.systemDefault()), rating)

    /**
     * Rate — or un-rate — ONE named night, whichever night it is.
     *
     * The general form of [setFeltToday], and the whole of what the register's editor needs: there the
     * night is chosen by tapping a calendar tile or a table line, so its key arrives as an argument
     * instead of being derived from what the card happens to be showing.
     *
     * A rating written here is not a lesser kind of rating. It goes into the same store under the same
     * night-start key, so the counting rule, the baseline every marker is banded against, the grid and
     * the table all pick it up on the [refresh] below — an evening filled in three weeks late counts
     * exactly as one filled in that morning.
     */
    fun setFelt(dateKey: Long, rating: Int) {
        // Tapping the value already selected REMOVES it. A rating you can change but never withdraw
        // is a trap: a stray tap becomes permanent data 白い熊 did not author, and the marker would
        // then be counted against a number nobody meant. (Found exactly that way, 2026-08-09.) It has
        // to behave identically here, or the same gesture would mean two different things depending
        // on which screen it was made from.
        if (RecoveryLog.rating(appContext, dateKey) == rating) {
            RecoveryLog.clear(appContext, dateKey)
        } else {
            RecoveryLog.setRating(appContext, dateKey, rating)
        }
        refresh()
    }

    /**
     * Write — or, on blank text, delete — one morning's note.
     *
     * The same store the Huawei report writes to, under the same key, for the reason the ratings
     * share one: what 白い熊 wrote about a morning is a statement about the morning, not about which
     * band happened to be on the wrist. Two stores would show a note on one screen and a hole on the
     * other.
     */
    fun setNote(dateKey: Long, text: String) {
        com.opentasker.core.band.DayNotes.RECOVERY.setNote(appContext, dateKey, text)
        refresh()
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
        // SpO₂ measurement, ~6 bpm higher. A LINE has to split them or it draws a sawtooth. A
        // CAPSULE must not: the envelope is the hour's real extent, and dropping the coincident
        // readings would clip the top off every capsule (Hume's own day range matches the pooled
        // population). LINE_WITH_SPOTS keeps the series POOLED in the chunk — so the footer, the
        // rejections and above all the gap TINT describe every reading — and splits only the MARK:
        // the curve is drawn over the periodic samples alone, the rest as spot dots.
        //
        // That split matters because the two populations do not measure the same thing. Asleep and
        // still they agree to 1 bpm; with a hundred steps nearby the spot reading runs 22 bpm higher
        // and the periodic series does not move at all. One curve through both would be a sawtooth
        // that is an artefact of the interleaving.
        val forChart = if (spec.key == BandMetric.HEART_RATE && spec.render == RenderKind.LINE) {
            ChartQualify.splitHeartRate(points, spo2Times).first
        } else {
            points
        }

        // The Hampel filter is an instrument for smoothing a LINE, and it assumes one population.
        // Pointed at the pooled heart-rate series it flags the interleaving itself — the higher
        // second population looks like a sawtooth of outliers, and it burns the whole rejection
        // budget on readings that are perfectly real (102 of them, on 白い熊's own data). Neither an
        // hourly envelope nor a pooled chunk feeding a split mark needs it: what is fitted through
        // the samples is fitted through ONE population, which was never the noisy part. So only a
        // plain LINE keeps Hampel; everything else keeps the range and sentinel gates alone.
        val chartSpec = if (spec.render == RenderKind.LINE) spec else spec.copy(hampelHalfWindow = 0)
        val chunk = if (spec.render == RenderKind.BARS) null else {
            ChartPipeline.qualifyAndSegment(forChart, chartSpec, mixedCadence = spec.mixedCadence)
        }
        val buckets = if (spec.render == RenderKind.CAPSULE) HourlyEnvelope.bucket(points) else emptyList()
        val bars = if (spec.render == RenderKind.BARS) points else emptyList()

        // The two marks of a split rendering, both drawn off the chunk's RETAINED points so that a
        // reading the filter rejected can come back as neither a dot nor a knot in the curve.
        //
        // The CURVE goes to the SpO₂-coincident readings and the dots to the periodic series, which
        // is the way round it is because of which one is trustworthy — the spot readings follow
        // exertion (+18 bpm over baseline while walking, 97 % of the time), and the periodic series
        // does not (−4 bpm, i.e. below its own resting level, which no heart does while walking).
        // The prominent mark belongs to the readings you can believe (白い熊, 2026-08-09).
        val retained = chunk?.segments?.flatMap { it.points }.orEmpty()
        val spots = if (spec.splitPopulations) retained.filter { it.tMs !in spo2Times } else emptyList()
        val lineChunk = if (spec.render == RenderKind.LINE_WITH_SPOTS && chunk != null) {
            ChartQualify.curveSeries(chunk, spo2Times, spec)
        } else {
            null
        }

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
        // A range or a typical value is a property of the METRIC, not of the mark it is drawn with —
        // `headlineIsRange` says which, so heart rate keeps its `53–105 bpm` now that its capsules
        // have become dots. Steps stay on the daily total.
        val headline = when {
            spec.render == RenderKind.BARS -> spec.format(today)
            spec.headlineIsRange -> "${spec.format(lo)}–${spec.format(hi)}"
            else -> spec.format(typical)
        }
        val forBand = if (spec.render == RenderKind.BARS) today else typical
        return MetricChart(
            spec = spec,
            chunk = chunk,
            buckets = buckets,
            bars = bars,
            spots = spots,
            lineChunk = lineChunk,
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
        // The most recent NIGHT, not the most recent session: an afternoon nap ends later than last
        // night and would otherwise take the headline, the stage table and the 健康指数 with it
        // (白い熊, 2026-08-20). The chart still draws every session — a nap happened and the
        // hypnogram should show it; what it must not do is answer "how did I sleep".
        val latest = SleepShape.latestNight(sessions, zone)
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
