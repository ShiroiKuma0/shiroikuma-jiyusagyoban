package com.opentasker.ui.charts.huawei

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.core.huawei.HuaweiFrom
import com.opentasker.core.huawei.HuaweiRriKeys
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSleep
import com.opentasker.core.huawei.HuaweiStatus
import com.opentasker.core.huawei.HuaweiSyncArgs
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.core.huawei.HuaweiSyncState
import com.opentasker.core.storage.AppDatabase
import com.opentasker.ui.charts.ChartPipeline
import com.opentasker.ui.charts.ChartPoint
import com.opentasker.ui.charts.HealthIndexSource
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.MetricChart
import com.opentasker.ui.charts.MetricSpec
import com.opentasker.ui.charts.RenderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One undecoded field, as the 診断 card lists it. */
data class HuaweiUnknownField(
    val storageKey: String,
    val samples: Int,
    val firstSeconds: Long?,
    val lastSeconds: Long?,
)

/**
 * The most recent night, rebuilt from stored segments.
 *
 * Reuses [HuaweiSleep.Session] rather than defining a parallel UI type, so the totals the card shows
 * are computed by the same code the decoder's tests check against the band's own screen.
 */
data class HuaweiSleepNight(val session: HuaweiSleep.Session)

data class HuaweiDashboardState(
    val loading: Boolean = true,
    val status: HuaweiStatus? = null,
    val bound: Boolean = false,
    val metrics: List<MetricChart> = emptyList(),
    val coverage: List<HuaweiCoverage> = emptyList(),
    val diagnostics: List<MetricChart> = emptyList(),
    val unknownFields: List<HuaweiUnknownField> = emptyList(),
    val sleep: HuaweiSleepNight? = null,
    val bounds: LongRange = 0L..0L,
    val message: Loc? = null,
    /**
     * This morning's rating, and the morning it belongs to.
     *
     * Read from the SAME store the Hume report writes to, and deliberately so: a morning rating is a
     * statement about 白い熊, not about a band. Two stores would mean the answer given on one screen
     * was missing from the other, and the register would show a hole on a morning that was rated.
     */
    /** The index and recovery, built exactly as the Hume report builds them — see [buildDerived]. */
    val index: com.opentasker.ui.charts.HealthIndexResult? = null,
    val recovery: com.opentasker.ui.charts.RecoveryResult? = null,
    val load: com.opentasker.ui.charts.RecoveryBuild.LoadReading? = null,
    val sri: Double? = null,
    val sleepScore: com.opentasker.ui.charts.SleepScore.Breakdown? = null,
    val register: com.opentasker.ui.charts.SessionRegister.Register? = null,
    val nights: List<com.opentasker.ui.charts.SleepSession> = emptyList(),
    /** One row per day, back across the whole history — see [buildDerived]. */
    val days: List<com.opentasker.ui.charts.DaySummary> = emptyList(),
    /** How many of [nights] came off the OTHER wrist — stated, never implied. */
    val humeNights: Int = 0,
    /** Where this band's own record begins — rows before it are the other band's. */
    val cutoverMs: Long? = null,
    val felt: Int? = null,
    val feltMorning: Long? = null,
    val feltEnabled: Boolean = true,
    /**
     * This morning's written note, from the same key the rating is filed under.
     *
     * Beside the rating rather than inside it: the digit is answered every morning and the sentence
     * is not, so they are two independent values about one morning — and a note without a rating is
     * a perfectly ordinary thing to have written.
     */
    val feltNote: String? = null,
    /**
     * 機能訓練 — the days the rehab was done, and what was written about them.
     *
     * Two plain collections rather than a built list of squares: the card shows two weeks and the
     * page the whole history, and the two must be cut from ONE record. Deciding the span here would
     * mean the model choosing for both, so it hands over the record and each screen takes its slice.
     */
    val rehabDays: Set<Long> = emptySet(),
    val rehabNotes: Map<Long, String> = emptyMap(),
)

/**
 * The Huawei report's state.
 *
 * A **sibling** of `BandDashboardModel`, not a generalisation of it. That model's `DashboardState`
 * carries twenty fields and this one would return null for fourteen — index, recovery, sleep, blood
 * pressure, the felt ratings, the register. A shared type would then assert, at every call site,
 * that any device can produce a recovery card, which is the one claim this report must never make.
 * The reuse that matters is already paid for by `MetricSpec` and `MetricChart`, which were never
 * device-specific.
 *
 * Loading is one-shot rather than a Room `Flow`, for the reason the Hume model records: table-
 * granular invalidation re-runs the whole filter chain on every write, thousands of times per sync.
 */
class HuaweiDashboardModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(HuaweiDashboardState())
    val state: StateFlow<HuaweiDashboardState> = _state.asStateFlow()

    val progress = HuaweiSyncState.progress

    init {
        // Today, marked, on the very first open and never again — 白い熊 asked the history to start
        // there (2026-09-03). See [RehabLog.seedTodayOnce] for why it is flagged rather than
        // conditioned on the store being empty.
        com.opentasker.core.band.RehabLog.seedTodayOnce(appContext)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val next = withContext(Dispatchers.Default) { load() }
            _state.value = next
        }
    }

    private suspend fun load(): HuaweiDashboardState {
        val samples = db.huaweiSampleDao()
        val oldest = samples.oldestAny()
        val newest = samples.newestAny()
        val status = runCatching { HuaweiSyncRunner.status(db) }.getOrNull()
        val bound = HuaweiSettings.isBound(appContext)

        // Loaded before the early return: a night can exist when no per-minute sample does, and
        // showing "no data" over a stored night would be a lie about the band rather than about us.
        val sleep = loadLatestNight()

        if (oldest == null || newest == null) {
            return HuaweiDashboardState(
                loading = false, status = status, bound = bound, sleep = sleep,
                message = if (bound) HuaweiText.noData else HuaweiText.notPaired,
                // 機能訓練 is 白い熊's own record and owes the band nothing — it has to be there on a
                // report that has no samples at all, which is exactly the state a new install is in.
                rehabDays = com.opentasker.core.band.RehabLog.all(appContext),
                rehabNotes = com.opentasker.core.band.DayNotes.REHAB.all(appContext),
            )
        }
        val fromMs = oldest * 1000L
        val toMs = newest * 1000L

        // Every instant the band described anything at. A counter with no reading at one of these
        // is a measured zero; a stretch with none of them is the only thing that deserves gap tint.
        val recorded = samples.recordedSeconds(oldest, newest)

        // Where the Huawei band's own record begins. Before it, the charts show the Hume band's
        // history — the era this band did not exist for — and after it, this band alone. The two
        // never overlap: see HuaweiHistory for why that is the whole basis of it being honest.
        val cutoverMs = HuaweiHistory.cutover(db)

        // The window opens on the whole history, not just the Huawei era, so the Hume prefix is
        // reachable by scrolling back rather than invisible.
        val humeOldest = db.bandSampleDao().oldestEpochMs()
        val historyFromMs = listOfNotNull(humeOldest, cutoverMs).minOrNull() ?: (oldest * 1000L)

        val charts = HuaweiMetricSpecs.ALL.map { spec ->
            buildChart(spec, oldest, newest, recorded, cutoverMs, historyFromMs)
        }
        val diagnostics =
            HuaweiMetricSpecs.DIAGNOSTIC.map { spec -> buildChart(spec, oldest, newest, recorded) }

        val coverage = (HuaweiMetricSpecs.ALL + HuaweiMetricSpecs.DIAGNOSTIC).map { spec ->
            HuaweiCoverage.from(
                spec.key,
                samples.timesFor(HuaweiKeys.storageKey(spec.key), oldest, newest),
            )
        }

        // Anything the band sent that no row describes. Listed rather than charted: the question
        // these answer is "bit 0x10 started appearing on the 3rd", which is a table, not a curve.
        val known = (HuaweiMetricSpecs.ALL + HuaweiMetricSpecs.DIAGNOSTIC)
            .map { HuaweiKeys.storageKey(it.key) }.toSet()
        val unknown = samples.metrics().filter { it !in known }.map { key ->
            val times = samples.timesFor(key, oldest, newest)
            HuaweiUnknownField(key, times.size, times.minOrNull(), times.maxOrNull())
        }

        val zone = java.time.ZoneId.systemDefault()
        val derived = buildDerived(charts, cutoverMs, zone)
        val morningKey = com.opentasker.ui.charts.RecoveryBuild.ratableMorning(
            java.time.LocalDateTime.now(zone),
        )

        return HuaweiDashboardState(
            loading = false,
            index = derived.index,
            recovery = derived.recovery,
            load = derived.load,
            sri = derived.sri,
            sleepScore = derived.sleepScore,
            register = derived.register,
            nights = derived.nights,
            days = derived.days,
            humeNights = derived.humeNights,
            cutoverMs = cutoverMs,
            felt = com.opentasker.core.band.RecoveryLog.rating(appContext, morningKey),
            feltMorning = morningKey,
            feltNote = com.opentasker.core.band.DayNotes.RECOVERY.note(appContext, morningKey),
            rehabDays = com.opentasker.core.band.RehabLog.all(appContext),
            rehabNotes = com.opentasker.core.band.DayNotes.REHAB.all(appContext),
            feltEnabled = com.opentasker.core.band.RecoveryLog.enabled(appContext),
            status = status,
            bound = bound,
            metrics = charts,
            coverage = coverage,
            diagnostics = diagnostics,
            unknownFields = unknown,
            sleep = sleep,
            // The WHOLE history, not just this band's era. The Hume prefix is drawn into every
            // chart, but ChartViewport clamps panning to these bounds — so with the Huawei-only
            // range the older readings existed on screen and could not be reached by scrolling,
            // which is indistinguishable from their not being there.
            bounds = minOf(fromMs, historyFromMs)..toMs,
        )
    }

    /** The newest stored night, or null when none has been synced. */
    private suspend fun loadLatestNight(): HuaweiSleepNight? {
        val dao = db.huaweiSleepDao()
        val start = dao.newestSession() ?: return null
        val rows = dao.session(start)
        if (rows.isEmpty()) return null
        return HuaweiSleepNight(
            HuaweiSleep.Session(
                startSeconds = start,
                endSeconds = rows.first().sessionEnd,
                segments = rows.map {
                    HuaweiSleep.Segment(
                        it.startSeconds, it.durationSeconds, HuaweiSleep.Stage.of(it.stage),
                    )
                },
            ),
        )
    }

    /**
     * The index, the recovery and the register — built by the SAME code the Hume report uses.
     *
     * Not a parallel implementation. `RecoveryBuild` and `HealthIndexSource` read their inputs by
     * metric KEY, so the only adaptation needed is to hand them charts keyed the way they expect;
     * everything after that is the arithmetic the other report has been using for weeks, with its
     * gates, its refusals and its published reasoning intact. A second implementation would drift,
     * and two health indices that disagree would be worse than one.
     *
     * **The baseline is not all this band's.** Nights before the cutover come off the Hume wrist,
     * because this band did not exist for them, and a baseline of two nights is not a baseline. That
     * is 白い熊's instruction and it is sound — but it means these numbers rest partly on the other
     * device, so [HuaweiDashboardState.humeNights] carries the count and the cards say so.
     */
    private suspend fun buildDerived(
        charts: List<MetricChart>,
        cutoverMs: Long?,
        zone: java.time.ZoneId,
    ): Derived {
        // Re-key to the names RecoveryBuild and HealthIndexSource look for. The Huawei charts carry
        // "hw:" keys precisely so the two devices' series cannot be confused elsewhere; here they
        // must be spoken to in the shared vocabulary, and this is the ONE place that translates.
        val rekeyed = charts.mapNotNull { chart ->
            val humeKey = when (chart.spec.key) {
                HuaweiKeys.STEPS -> com.opentasker.core.band.BandMetric.STEPS_MINUTE
                HuaweiKeys.HEART_RATE -> com.opentasker.core.band.BandMetric.HEART_RATE
                HuaweiKeys.SPO2 -> com.opentasker.core.band.BandMetric.SPO2
                else -> null
            } ?: return@mapNotNull null
            chart.copy(spec = chart.spec.copy(key = humeKey))
        }

        val humeSessions = loadHumeSessions(zone)
        val nights = HuaweiNights.all(db, cutoverMs, humeSessions)
        val boundary = cutoverMs ?: Long.MAX_VALUE
        val humeNights = nights.count { it.endMs < boundary }

        val zoneOffsetMs = zone.rules.getOffset(java.time.Instant.now()).totalSeconds * 1000L
        val minuteOfDayOf: (Long) -> Double =
            { ms -> com.opentasker.ui.charts.SleepShape.minuteOfDay(ms, zone).toDouble() }

        val assembled = com.opentasker.ui.charts.RecoveryBuild.build(
            metrics = rekeyed,
            hrvPoints = publishableRmssd(),
            sessions = nights,
            ratings = com.opentasker.core.band.RecoveryLog.all(appContext),
            notes = com.opentasker.core.band.DayNotes.RECOVERY.all(appContext),
            sessions_ = com.opentasker.core.band.TrainingSessions.all(appContext),
            sessionOpen = com.opentasker.core.band.TrainingSessions.openStart(appContext) != null,
            // yyyyMMdd of an instant, the key every rating and every register row is filed under.
            localDateOf = { ms ->
                val d = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                d.year * 10_000L + d.monthValue * 100L + d.dayOfMonth
            },
            zoneOffsetMs = zoneOffsetMs,
            todayEpochDay = (System.currentTimeMillis() + zoneOffsetMs) / 86_400_000L,
            nowMs = System.currentTimeMillis(),
            minuteOfDayOf = minuteOfDayOf,
        )

        // The band's own last night, if it has one — otherwise the newest on record, which will be
        // the Hume band's. Either way it is A night that happened, never a blend of two.
        val latest = nights.lastOrNull()
        // One row per day, back across everything on record — the Huawei era and, before it, the
        // Hume one. The rows are built from the SAME series the charts draw, so a day's figures and
        // its curve can never disagree: they are the same points counted twice, not two sources.
        //
        // spo2Times is empty because it is a HUME record-type flag — it marks which heart-rate
        // readings arrived alongside a blood-oxygen one, splitting that band's dual population. This
        // band has no such split, and passing anything here would be inventing one.
        val days = com.opentasker.ui.charts.DailySummary.build(
            hr = rekeyed.firstOrNull { it.spec.key == com.opentasker.core.band.BandMetric.HEART_RATE }
                ?.chunk?.segments?.flatMap { it.points }.orEmpty(),
            spo2 = rekeyed.firstOrNull { it.spec.key == com.opentasker.core.band.BandMetric.SPO2 }
                ?.chunk?.segments?.flatMap { it.points }.orEmpty(),
            steps = rekeyed.firstOrNull { it.spec.key == com.opentasker.core.band.BandMetric.STEPS_MINUTE }
                ?.bars.orEmpty(),
            sleepSessions = nights,
            spo2Times = emptySet(),
            zone = zone,
        )

        return Derived(
            index = com.opentasker.ui.charts.HealthIndexSource.compute(rekeyed, latest, emptySet()),
            days = days,
            recovery = assembled.recovery,
            load = assembled.load,
            sri = assembled.sri,
            sleepScore = assembled.sleepScore,
            register = assembled.register,
            nights = nights,
            humeNights = humeNights,
        )
    }

    data class Derived(
        val index: com.opentasker.ui.charts.HealthIndexResult? = null,
        val recovery: com.opentasker.ui.charts.RecoveryResult? = null,
        val load: com.opentasker.ui.charts.RecoveryBuild.LoadReading? = null,
        val sri: Double? = null,
        val sleepScore: com.opentasker.ui.charts.SleepScore.Breakdown? = null,
        val register: com.opentasker.ui.charts.SessionRegister.Register? = null,
        val nights: List<com.opentasker.ui.charts.SleepSession> = emptyList(),
        val days: List<com.opentasker.ui.charts.DaySummary> = emptyList(),
        val humeNights: Int = 0,
    )

    /** The Hume band's nights, for the era before this band existed. */
    /**
     * Every RMSSD window the band itself would publish, as points.
     *
     * ## Why the filter is not ours to skip
     *
     * Each `rrisqi` window carries the number of beat-to-beat intervals it accepted, and Huawei
     * Health publishes a window only at roughly 17 or more — a clean 9-for-9 split across the
     * records it listed and omitted on 白い熊's own wrist. A window the vendor's own app discards is
     * one the band considers too sparse to mean anything, and a night's median computed over the
     * sparse ones would be a number with no measurement behind it. Everything is still STORED
     * unfiltered (see `HuaweiSyncRunner.storeRri`) — the threshold is applied here, where it can be
     * revisited, rather than at ingest, where it could not.
     *
     * The two series are joined on the window's start second, which is the key both are written
     * under: one row per field per window, so a count and its RMSSD share an instant exactly.
     */
    private suspend fun publishableRmssd(): List<ChartPoint> {
        val dao = db.huaweiSampleDao()
        val from = 0L
        val to = Long.MAX_VALUE / 2
        val counts = runCatching { dao.range(HuaweiRriKeys.COUNT, from, to) }.getOrNull().orEmpty()
        val publishable = counts
            .filter { it.value >= com.opentasker.core.huawei.HuaweiRri.MIN_VALID_INTERVALS }
            .mapTo(HashSet()) { it.epochSeconds }
        if (publishable.isEmpty()) return emptyList()
        return runCatching { dao.range(HuaweiRriKeys.metricFor(5), from, to) }.getOrNull().orEmpty()
            .filter { it.epochSeconds in publishable }
            .map { ChartPoint(it.epochSeconds * 1000L, it.value) }
    }

    private suspend fun loadHumeSessions(zone: java.time.ZoneId): List<com.opentasker.ui.charts.SleepSession> {
        val rows = runCatching { db.bandSleepDao().recent(400) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()
        val inputs = rows.mapNotNull { row ->
            val start = com.opentasker.ui.charts.BandLocalTimes.toEpochMs(row.startLocalTs, zone)
                ?: return@mapNotNull null
            com.opentasker.ui.charts.SleepSegmentInput(start, row.minutes, row.stages)
        }
        return com.opentasker.ui.charts.SleepShape.sessions(inputs)
    }

    /**
     * Record — or withdraw — this morning's rating.
     *
     * Tapping the value already chosen REMOVES it, exactly as on the Hume screen. A rating you can
     * change but never withdraw is a trap: a stray tap becomes data 白い熊 did not author, and every
     * later marker is then banded against a number nobody meant. The same gesture must not mean two
     * different things depending on which report it was made from.
     */
    /**
     * Rate — or un-rate — ONE named night, whichever night it is.
     *
     * What the register's editor needs: there the night is chosen by tapping a row, so its key
     * arrives as an argument rather than being derived from what today happens to be. It writes to
     * the same store under the same key as [setFelt], so a rating filed here is not a lesser kind.
     */
    fun setFeltFor(nightKey: Long, rating: Int) {
        if (com.opentasker.core.band.RecoveryLog.rating(appContext, nightKey) == rating) {
            com.opentasker.core.band.RecoveryLog.clear(appContext, nightKey)
        } else {
            com.opentasker.core.band.RecoveryLog.setRating(appContext, nightKey, rating)
        }
        refresh()
    }

    fun setFelt(rating: Int) {
        val key = com.opentasker.ui.charts.RecoveryBuild.ratableMorning(
            java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()),
        )
        if (com.opentasker.core.band.RecoveryLog.rating(appContext, key) == rating) {
            com.opentasker.core.band.RecoveryLog.clear(appContext, key)
        } else {
            com.opentasker.core.band.RecoveryLog.setRating(appContext, key, rating)
        }
        refresh()
    }

    /**
     * Write — or, on blank text, delete — the note for ONE named morning.
     *
     * Deliberately keyed like [setFeltFor] rather than like [setFelt]: the note editor is reachable
     * from the morning card, from a grid tile and from a table line, and all three name the morning
     * they mean. One writer for all of them is what stops a note from the card landing on a
     * different key than a note from the calendar.
     */
    fun setNoteFor(morningKey: Long, text: String) {
        com.opentasker.core.band.DayNotes.RECOVERY.setNote(appContext, morningKey, text)
        refresh()
    }

    /** Tick or un-tick one day of 機能訓練. */
    fun setRehab(dateKey: Long, done: Boolean) {
        com.opentasker.core.band.RehabLog.setDone(appContext, dateKey, done)
        refresh()
    }

    /** Write — or, on blank text, delete — one day's 機能訓練 note. Its own store; see `DayNotes`. */
    fun setRehabNote(dateKey: Long, text: String) {
        com.opentasker.core.band.DayNotes.REHAB.setNote(appContext, dateKey, text)
        refresh()
    }

    /** The morning the card's own note belongs to — the same one [setFelt] rates. */
    fun morningKeyNow(): Long = com.opentasker.ui.charts.RecoveryBuild.ratableMorning(
        java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()),
    )

    private suspend fun buildChart(
        spec: MetricSpec,
        from: Long,
        to: Long,
        recorded: List<Long> = emptyList(),
        cutoverMs: Long? = null,
        historyFromMs: Long? = null,
    ): MetricChart {
        val rows = db.huaweiSampleDao().range(HuaweiKeys.storageKey(spec.key), from, to)
        val points = if (!spec.absentIsZero) {
            // A zero this spec calls "no reading" is not a point. Keeping it made the qualifier
            // reject it and tint the span as missing, so a metric the band fills with zeros between
            // its occasional real values read as a day of lost data. Rows already stored are
            // filtered here rather than migrated away.
            rows.asSequence()
                .filterNot { spec.zeroIsNoReading && it.value == 0.0 }
                .map { ChartPoint(it.epochSeconds * 1000L, it.value) }
                .toList()
        } else {
            // Fill the band's own silence with zeros, but only where the band was demonstrably
            // recording. A stretch it said nothing about at all stays absent, so a real sync hole
            // still reads as a hole instead of being disguised as a quiet afternoon.
            val have = rows.associate { it.epochSeconds to it.value }
            recorded.map { ChartPoint(it * 1000L, have[it] ?: 0.0) }
        }
        if (points.isEmpty()) {
            return MetricChart(
                spec = spec, chunk = null, buckets = emptyList(), bars = emptyList(),
                headline = "—", headlineBand = null, subtitle = HuaweiText.noData,
            )
        }
        // The Hume era, prepended. It stops at the cutover, so no instant carries a reading from
        // both wrists — the prefix is the years this band did not exist for, not a second opinion.
        val prefix = if (historyFromMs == null) HuaweiHistory.Prefix(emptyList(), cutoverMs, 0)
        else HuaweiHistory.prefix(db, spec.key, historyFromMs, cutoverMs, spec.cadenceSec)
        val withHistory = prefix.points + points

        // mixedCadence is on for every row in this table: the real cadence is unmeasured, so the gap
        // threshold has to come from a high percentile rather than a median it cannot trust.
        val chunk = ChartPipeline.qualifyAndSegment(withHistory, spec, mixedCadence = spec.mixedCadence)
        val retained = chunk.segments.flatMap { it.points }
        val lastDay = HealthIndexSource.lastDay(retained)

        val headline = when {
            retained.isEmpty() -> "—"
            // A bar metric headlines its 24 h TOTAL, matching the Hume screen so the two figures
            // answer the same question.
            spec.render == RenderKind.BARS -> spec.format(lastDay.sumOf { it.value }) + " " + spec.unit
            spec.headlineIsRange -> {
                val lo = lastDay.minOfOrNull { it.value }
                val hi = lastDay.maxOfOrNull { it.value }
                if (lo == null || hi == null) "—"
                else "${spec.format(lo)}–${spec.format(hi)} ${spec.unit}"
            }
            else -> HealthIndexSource.median(lastDay.map { it.value })
                ?.let { "${spec.format(it)} ${spec.unit}" } ?: "—"
        }

        return MetricChart(
            spec = spec,
            chunk = chunk,
            buckets = emptyList(),
            // The bar renderer reads raw retained points and must never see a filtered series —
            // though with every filter off on this table, retained is currently everything.
            bars = if (spec.render == RenderKind.BARS) retained else emptyList(),
            headline = headline,
            headlineBand = null,
            // Stated on every card, because every gate on this table is a placeholder — and, when
            // some of what is drawn came off the other wrist, that too. A reader must never have to
            // guess which band a stretch of chart belongs to.
            subtitle = when {
                prefix.humeCount > 0 -> Loc(
                    "${prefix.humeCount} earlier readings are the Hume band's — this band did not " +
                        "exist yet. Nothing is mixed: they stop where this one starts." +
                        (if (spec.provisional) " · " + HuaweiText.provisional.en else ""),
                    "古い ${prefix.humeCount} 件は Hume のもの — このバンドはまだ無かった。" +
                        "混ぜてはいない。こちらが始まるところで終わる。" +
                        (if (spec.provisional) " ／ " + HuaweiText.provisional.ja else ""),
                )
                spec.provisional -> HuaweiText.provisional
                else -> Loc("", "")
            },
        )
    }

    /**
     * Announce the sync on the main thread BEFORE dispatching, then hand off to the app-scoped
     * runner so it survives this window closing. Both halves matter: reaching a usable session means
     * a socket connect plus a full HiChain pass, and nothing is visible on screen until then.
     */
    fun sync() {
        if (!HuaweiSyncState.arm()) return
        HuaweiSyncRunner.scope.launch {
            runCatching {
                val status = HuaweiSyncRunner.status(db)
                HuaweiSyncRunner.sync(
                    appContext, db,
                    HuaweiSyncRunner.Request(
                        address = HuaweiSettings.address(appContext),
                        windows = HuaweiSyncArgs.resolve(
                            from = HuaweiFrom.Auto,
                            lastSuccessAtSeconds = status.lastSuccessAtMillis?.let { it / 1000 },
                            overlapMinutes = HuaweiSettings.overlapMinutes(appContext),
                            nowSeconds = System.currentTimeMillis() / 1000,
                        ),
                        timeoutSec = HuaweiSettings.timeoutSec(appContext),
                        source = "window",
                    ),
                )
            }.onFailure {
                // The one path where arming without reaching the runner would leave it spinning.
                HuaweiSyncState.finish(it.message ?: "failed")
            }
            refresh()
        }
    }
}

class HuaweiDashboardModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HuaweiDashboardModel::class.java)) {
            return HuaweiDashboardModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
