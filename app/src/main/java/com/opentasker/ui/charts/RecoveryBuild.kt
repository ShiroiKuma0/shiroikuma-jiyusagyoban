package com.opentasker.ui.charts

import com.opentasker.core.band.BandMetric
import com.opentasker.core.band.TrainingSessions

/**
 * Assembling 回復 from the loaded charts — the one place that knows both halves.
 *
 * Split out of [BandDashboardModel] because it is pure and therefore testable: everything it needs
 * arrives as arguments, including the self-ratings and the zone offset, so the whole assembly can be
 * driven from a unit test with no database and no clock.
 */
object RecoveryBuild {

    /** What the card shows about training load, beside the night's markers. */
    data class LoadReading(
        /** MET-minutes above rest over the trailing 7 days, both channels combined. */
        val weekly: Double?,
        /** The marked-session half of [weekly] — the part walking cadence could never see. */
        val weeklyFromSessions: Double?,
        /** Marked sessions in the trailing 7 days. */
        val sessionsThisWeek: Int,
        /** True while a session is open and unclosed, so the card can say so. */
        val sessionOpen: Boolean,
        /** 7-day mean over 28-day mean, or null until there is enough history. */
        val ratio: Double?,
        val band: LoadBand?,
        val daysOfHistory: Int,
    )

    /**
     * A score is filed under the MORNING 白い熊 woke on, and the morning to offer is simply today's.
     *
     * ## Why the morning, and not the evening the night began on
     *
     * Because that is what the score is: an appraisal made on waking, of the night just ended. Keyed
     * on the night's START it depended on which side of midnight the bedtime fell — a night begun at
     * 23:09 was filed a day before the morning it was rated on, one begun at 00:21 on the morning
     * itself. 白い熊's own archive holds both: thirteen nights begun before midnight and one after, so
     * the same nightly habit drew two different pictures in the same grid, and the odd one out was
     * simply the one nobody could see was odd. (2026-08-16.)
     *
     * Worse, the two names collide. A night begun at 00:21 on the 15th and the night begun that same
     * evening are both "the 15th" — so the morning of the 16th had nowhere to be filed at all, and the
     * card offered back the 15th's answer instead of asking for a new one. Two nights can never end on
     * the same morning, so naming by the morning cannot collide, whatever time the light goes out.
     *
     * ## Why today's, unconditionally
     *
     * Every morning that has happened can be rated, and none that has not. There is nothing to derive
     * from what the band recorded: a night it missed is exactly as rateable as one it caught, which is
     * the whole point — the morning after a night off the wrist is the one most likely to need typing
     * in by hand. No fallback, no "later of", no dependence on the sync having run.
     *
     * ## The 04:00 boundary
     *
     * A day here starts at 04:00, not midnight, so that being awake at 01:00 still offers the morning
     * 白い熊 last woke on rather than one they have not slept into yet. Four is late enough to cover an
     * ordinary late night and early enough that a genuine early start is never misfiled.
     */
    fun ratableMorning(now: java.time.LocalDateTime): Long =
        // Through SessionRegister so the card and the grid cannot come to disagree about the key for a
        // given day — a tile and the card's row must write to the same place.
        SessionRegister.dateKeyOf(now.minusHours(DAY_STARTS_AT_HOUR).toLocalDate().toEpochDay())

    /** See [ratableMorning]. */
    const val DAY_STARTS_AT_HOUR = 4L

    data class Assembled(
        val recovery: RecoveryResult?,
        val load: LoadReading,
        /** Sleep Regularity Index — see [SleepRegularity]. Null until a week of nights exists. */
        val sri: Double?,
        /** Last night, on Apple's published 50/30/20 weights — see [SleepScore]. */
        val sleepScore: SleepScore.Breakdown?,
        /** Mean of the 30 highest step-count minutes of [peakCadenceDay]. NHANES norm 71.1. */
        val peak30Cadence: Double?,
        /** Which day that peak belongs to — today's is meaningless before the day has happened. */
        val peakCadenceDay: Long?,
        /** Travel and altitude, detected and said out loud — see [RecoveryRegime]. */
        val regime: RecoveryRegime.Regime,
        /** Every marked session beside the night that followed it — see [SessionRegister]. */
        val register: SessionRegister.Register,
        /**
         * Last night's heart rate in twelfths, for the deviation strip's sparkline.
         *
         * Handed out from here rather than recomputed in the UI: it is the same curve
         * [RecoveryMarker.HR_SWING] is measured from, and two derivations of one curve drift the
         * first time either is touched.
         */
        val lastNightHrCurve: List<Double> = emptyList(),
        /**
         * The per-night series behind every row of the strip, so each row can be opened.
         *
         * Assembled here because this is where the nights already are, banded and in order. Building
         * it again in the UI would mean a second definition of "the history of this marker", and the
         * two would disagree the first time either was touched.
         */
        val markerHistory: Map<RecoveryMarker, MarkerHistory.Series> = emptyMap(),
        /** Last night's descent beside the usual one — the curve's commentary. */
        val descent: MarkerHistory.DescentComparison? = null,
        /** The deepest-dropping nights on record, to aim that comparison at. */
        val bestDescent: MarkerHistory.Descent? = null,
        /** Recent nights' HR curves, newest last — stacked under the swing's history page. */
        val recentCurves: List<Pair<Long, List<Double>>> = emptyList(),
    )

    /**
     * [ratings] maps a `yyyyMMdd` local date to 1–5, keyed by the MORNING the night ended — the day
     * 白い熊 woke and answered. See [ratableMorning] for why that and not the evening it began.
     */
    fun build(
        metrics: List<MetricChart>,
        sessions: List<SleepSession>,
        ratings: Map<Long, Int>,
        /** The written notes, keyed exactly as [ratings] are. Carried straight to the register. */
        notes: Map<Long, String>,
        /**
         * RMSSD windows, already filtered to the ones the band itself would publish.
         *
         * A parameter rather than another entry in [metrics] because it is not a charted series and
         * has no [MetricSpec]: it arrives from the Huawei sample table by storage key. The Hume side
         * passes nothing and is right to — that band reported a device-state index it CALLED HRV and
         * never sent a beat-to-beat interval in its life.
         */
        hrvPoints: List<ChartPoint> = emptyList(),
        /**
         * Per-record respiratory rates, from the band's own per-beat series.
         *
         * A parameter for the same reason [hrvPoints] is: not a charted series, no [MetricSpec], and
         * arriving from the Huawei sample table by storage key. The Hume side passes nothing — that
         * band never sent a beat-to-beat interval, so there is nothing to derive a rate from.
         */
        respirationPoints: List<ChartPoint> = emptyList(),
        /** Per-window HF power (`rri_f8`), for the consecutive-night run. Empty for the Hume band. */
        hfPoints: List<ChartPoint> = emptyList(),
        sessions_: List<TrainingSessions.Session>,
        sessionOpen: Boolean,
        localDateOf: (Long) -> Long,
        zoneOffsetMs: Long,
        todayEpochDay: Long,
        nowMs: Long,
        /** Epoch-day → the device's UTC offset in minutes that day, for the travel check. */
        offsetsByDay: Map<Long, Int> = emptyMap(),
        minuteOfDayOf: (Long) -> Double = { 0.0 },
    ): Assembled {
        val hrPoints = pointsOf(metrics, BandMetric.HEART_RATE)
        val tempPoints = pointsOf(metrics, BandMetric.TEMPERATURE)
        val stepPoints = metrics.firstOrNull { it.spec.key == BandMetric.STEPS_MINUTE }?.bars.orEmpty()

        // The spot population, which is the only one that tracks exertion — see MetricSpecs.HEART_RATE.
        val spotPoints = metrics.firstOrNull { it.spec.key == BandMetric.HEART_RATE }?.spots.orEmpty()
        val load = buildLoad(stepPoints, spotPoints, sessions_, sessionOpen, zoneOffsetMs, todayEpochDay, nowMs)
        val sri = SleepRegularity.of(sessions)
        val peakDay = RecoverySource.lastCompleteDay(stepPoints, zoneOffsetMs, todayEpochDay)
        val peak30 = peakDay?.let { RecoverySource.peakCadence(it.second, 30) }

        val nights = RecoverySource.nights(sessions, minuteOfDayOf)
        val spo2Points = pointsOf(metrics, BandMetric.SPO2)
        val spo2ByNight = nights.mapNotNull { n ->
            HealthIndexSource.median(spo2Points.filter { it.tMs in n.startMs..n.endMs }.map { it.value })
        }
        val regime = RecoveryRegime.detect(offsetsByDay, todayEpochDay, spo2ByNight)

        val restingSpot = RecoverySource.restingSpotHr(spotPoints)
        val gridFrom = gridStart(todayEpochDay)
        val history = nights.map {
            RecoverySource.metricsFor(
                it, hrPoints, tempPoints, spo2Points, hrvPoints, respirationPoints, hfPoints,
            )
        }
        // By the night's END: the morning it is filed under. See [ratableMorning].
        val feltFor = { m: RecoverySource.NightMetrics -> ratings[localDateOf(m.endMs)]?.toDouble() }
        val register = SessionRegister.build(
            sessions = sessions_,
            nights = SessionRegister.readNights(history, feltFor),
            spotPoints = spotPoints,
            restingHr = restingSpot,
            zoneOffsetMs = zoneOffsetMs,
            fromEpochDay = gridFrom,
            toEpochDay = todayEpochDay,
            ratings = ratings,
            notes = notes,
            dateOfNight = localDateOf,
        )

        if (nights.isEmpty()) {
            return Assembled(null, load, sri, null, peak30, peakDay?.first, regime, register)
        }

        val onsets = nights.map(minuteOfDayOf.let { f -> { n: SleepSession -> f(n.startMs) } })
        val lastNight = nights.last()
        val sleepScore = SleepScore.score(
            asleepMinutes = RecoverySource.sleepMinutes(lastNight),
            awakeMinutes = lastNight.awake.toDouble(),
            onsetMinuteOfDay = onsets.last(),
            previousOnsetsMinutesOfDay = onsets.dropLast(1),
        )

        val latest = history.last()
        // The current night is judged against the ones BEFORE it, never including itself: a value
        // cannot be part of the baseline it is measured against.
        val prior = history.dropLast(1).takeLast(Recovery.BASELINE_NIGHTS)
        val confidence = Recovery.confidenceFor(prior.size)

        val hr = Recovery.bandNocturnalHr(
            latest.nocturnalHr, prior.mapNotNull { it.nocturnalHr }, confidence,
        )
        val sleep = Recovery.band(
            RecoveryMarker.SLEEP, latest.sleepMinutes, prior.mapNotNull { it.sleepMinutes },
            Recovery.SLEEP_MEANINGFUL_MIN, confidence, counted = true,
        )
        val felt = Recovery.band(
            RecoveryMarker.FELT, feltFor(latest), prior.mapNotNull(feltFor),
            Recovery.FELT_MEANINGFUL_STEPS, confidence, counted = true,
        )
        val temp = Recovery.band(
            RecoveryMarker.TEMPERATURE, latest.skinTemp, prior.mapNotNull { it.skinTemp },
            Recovery.TEMP_MEANINGFUL_C, confidence, counted = false, oneSidedHigh = true,
        )
        // Display-only, both of them, and banded on the heart-rate machinery where that applies: a
        // bedtime level is a heart rate and carries the same measured 3.5 % sensor dispersion the
        // nocturnal one does, so it gets the same floor. Giving it a floor of zero would let a quiet
        // fortnight score a 2 bpm drift at z = 4 — exactly the failure HR_SIGMA_FLOOR_FRACTION
        // exists to prevent, and it would do it on the one marker a conjunction now reads.
        val bedtime = Recovery.band(
            RecoveryMarker.BEDTIME_HR, latest.bedtimeHr, prior.mapNotNull { it.bedtimeHr },
            Recovery.HR_MEANINGFUL_BPM, confidence, counted = false,
            sigmaFloor = (Recovery.median(prior.mapNotNull { it.bedtimeHr }) ?: 0.0) *
                Recovery.HR_SIGMA_FLOOR_FRACTION,
        )
        val respiration = Recovery.band(
            RecoveryMarker.RESPIRATION, latest.respirationBpm, prior.mapNotNull { it.respirationBpm },
            Recovery.RESPIRATION_MEANINGFUL_BPM, confidence, counted = false,
        )
        // Banded here as well as in the night table, so the strip can print them. Their floors are
        // the display-only ones — a quarter-hour of deep sleep, five milliseconds of RMSSD — which
        // exist to stop a short history manufacturing colour, and are NOT smallest-worthwhile-
        // changes: no published figure exists for either on a consumer band.
        val deep = Recovery.band(
            RecoveryMarker.DEEP, latest.deepMinutes, prior.mapNotNull { it.deepMinutes },
            Recovery.DEEP_MEANINGFUL_MIN, confidence, counted = false,
        )
        val hrv = Recovery.band(
            RecoveryMarker.HRV, latest.hrvMs, prior.mapNotNull { it.hrvMs },
            Recovery.HRV_MEANINGFUL_MS, confidence, counted = false,
        )
        // The swing is a heart rate in bpm, so it takes the heart rate's own published
        // smallest-worthwhile-change rather than a figure invented for it. No dispersion floor: that
        // one is a fraction of a RESTING heart rate and means nothing applied to a range.
        val swing = Recovery.band(
            RecoveryMarker.HR_SWING, latest.hrSwing, prior.mapNotNull { it.hrSwing },
            Recovery.HR_MEANINGFUL_BPM, confidence, counted = false,
        )
        // Over the whole history, not just the baseline window: a run is a property of the sequence
        // and truncating the sequence truncates the run.
        val hrvRun = Recovery.runAbove(history.map { it.hfPower })

        // "Sustained" means the night before was warm too. One warm night at the wrist is the
        // bedroom, not 白い熊 — ambient correlates with the sensor at r = 0.961.
        val previousWarm = prior.lastOrNull()?.skinTemp?.let { previous ->
            val beforeThat = prior.dropLast(1).mapNotNull { it.skinTemp }
            Recovery.band(
                RecoveryMarker.TEMPERATURE, previous, beforeThat,
                Recovery.TEMP_MEANINGFUL_C, confidence, counted = false, oneSidedHigh = true,
            ).band == RecoveryBand.HIGH
        } ?: false

        val assembledRecovery = Recovery.assemble(
                nightStartMs = latest.startMs,
                nightEndMs = latest.endMs,
                nocturnalHr = hr,
                sleep = sleep,
                felt = felt,
                temperature = temp,
                temperatureSustained = temp.band == RecoveryBand.HIGH && previousWarm,
                lateEffortMinutesBeforeSleep = RecoverySource.lateEffortMinutes(latest.startMs, stepPoints),
                nightsOfHistory = prior.size,
                bedtimeHr = bedtime,
                respiration = respiration,
                deep = deep,
                hrv = hrv,
                hrSwing = swing,
                hrvRunNights = hrvRun,
        )
        return Assembled(
            recovery = assembledRecovery,
            load = load,
            sri = sri,
            sleepScore = sleepScore,
            peak30Cadence = peak30,
            peakCadenceDay = peakDay?.first,
            regime = regime,
            register = register,
            lastNightHrCurve = latest.hrCurve,
            markerHistory = markerHistory(history, assembledRecovery.markers.associateBy { it.marker }),
            descent = descentOf(history, latest),
            bestDescent = bestDescentOf(history),
            recentCurves = history.filter { it.hrCurve.isNotEmpty() }
                .takeLast(CURVE_HISTORY).map { it.endMs to it.hrCurve },
        )
    }

    /** How many nights' curves the OVERLAY stacks. Enough to see a habit, few enough to read. */
    const val RECENT_CURVES = 5

    /**
     * How many nights the swing's page carries curves for, as SMALL MULTIPLES under that overlay.
     *
     * Far more than the overlay can hold, and for the opposite reason: five lines in one frame is
     * the most that is still legible, while five separate charts is barely a history. A fortnight
     * is the span over which 白い熊 actually asks "was it like this last week", and each tile is
     * cheap — one small chart, scrolled past if it is not wanted. (白い熊, 2026-09-26.)
     */
    const val CURVE_HISTORY = 14

    /**
     * One series per marker, from the same nights the strip is banded against.
     *
     * A night with no value for a marker is ABSENT rather than zero-filled: the band records a field
     * only when it measured one, and a zero on a chart of nightly RMSSD would be a night 白い熊's
     * heart stopped varying rather than a night the band was on the charger.
     */
    private fun markerHistory(
        history: List<RecoverySource.NightMetrics>,
        markers: Map<RecoveryMarker, MarkerReading>,
    ): Map<RecoveryMarker, MarkerHistory.Series> {
        fun series(marker: RecoveryMarker, pick: (RecoverySource.NightMetrics) -> Double?) =
            marker to MarkerHistory.Series(
                marker = marker,
                nights = history.mapNotNull { n -> pick(n)?.let { MarkerHistory.Night(n.endMs, it) } },
                latest = markers[marker],
            )
        return listOf(
            series(RecoveryMarker.SLEEP) { it.sleepMinutes },
            series(RecoveryMarker.BEDTIME_HR) { it.bedtimeHr },
            series(RecoveryMarker.NOCTURNAL_HR) { it.nocturnalHr },
            series(RecoveryMarker.DEEP) { it.deepMinutes },
            series(RecoveryMarker.HRV) { it.hrvMs },
            series(RecoveryMarker.HR_SWING) { it.hrSwing },
            series(RecoveryMarker.RESPIRATION) { it.respirationBpm },
        ).toMap()
    }

    /**
     * Last night's descent beside the usual one.
     *
     * "Usual" is the median over EVERY night on record, not the trailing window the bands use: this
     * is the sentence's "usually", and a reader comparing last night to it means 白い熊's normal —
     * not the normal of the last seven days, which last night may itself have helped set.
     */
    /**
     * One night's descent, measured on the SAME curve the chart draws.
     *
     * It used `bedtimeHr` and `lowestHr` — a median of the first hour, and the minimum of every raw
     * per-minute sample in the session — and that put two answers to one question on a single card:
     * 白い熊's screenshot of 2026-09-13 reads *"9 bpm between its highest and lowest"* directly above
     * *"72 to 56, a drop of 16 bpm"*, with the chart's own low point marked at 64.
     *
     * The raw minimum is not wrong, it is a different quantity: one stray quiet minute, where the
     * binned curve is twelve medians. But the commentary sits UNDER the chart and describes it, so it
     * has to be about the thing drawn. First bin to lowest bin — the descent the reader can trace
     * with a finger.
     */
    private fun curveDescent(n: RecoverySource.NightMetrics): MarkerHistory.Descent? =
        n.hrCurve.takeIf { it.isNotEmpty() }?.let { MarkerHistory.Descent(it.first(), it.min()) }

    private fun descentOf(
        history: List<RecoverySource.NightMetrics>,
        latest: RecoverySource.NightMetrics,
    ): MarkerHistory.DescentComparison? {
        val last = curveDescent(latest) ?: return null
        val all = history.mapNotNull { curveDescent(it) }
        val usual = MarkerHistory.descent(all.map { it.from }, all.map { it.to }) ?: return null
        return MarkerHistory.DescentComparison(last, usual)
    }

    /** The median descent of the three nights that fell furthest — something to aim at. */
    private fun bestDescentOf(history: List<RecoverySource.NightMetrics>): MarkerHistory.Descent? {
        val all = history.mapNotNull { curveDescent(it) }
        if (all.size < 3) return null
        val deepest = all.sortedByDescending { it.drop }.take(3)
        return MarkerHistory.descent(deepest.map { it.from }, deepest.map { it.to })
    }

    /**
     * The two load channels, added rather than maxed.
     *
     * Walking cadence covers ambulatory work all day; marked sessions cover what leaves no step
     * signature. They are summed because a marked lifting session contributes essentially nothing to
     * the cadence channel (白い熊's real session ran 17–36 steps/min, well under the 100 that scores
     * anything), so there is nothing to double-count. A marked WALK would be counted twice — which is
     * why the action's own description says to mark what the band cannot see, not everything.
     */
    /**
     * The calendar's window: seven whole weeks ending on today's, Monday-aligned.
     *
     * A month and a half rather than the five weeks it used to be, and **fixed** — it does not grow
     * with the data and does not shrink when there is none. The card that draws it gives the rows a
     * fixed height and scrolls them, so the window's length is a statement about how far back the
     * calendar reaches, not about how tall the card is. (白い熊, 2026-08-18: "make the view fixed to
     * cca month and a half going back … the days should scroll within the fixed view".)
     *
     * Monday-aligned at the start because the grid's first column is Monday: aligning here means the
     * first row is a whole week, and — more to the point — that the window's length does not shift
     * by up to six days depending on which weekday today happens to be. `today - 44` lands 45 days
     * back and the alignment rounds that out to 45–51.
     */
    fun gridStart(todayEpochDay: Long): Long =
        java.time.LocalDate.ofEpochDay(todayEpochDay)
            .minusDays(44)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .toEpochDay()

    private fun buildLoad(
        stepPoints: List<ChartPoint>,
        spotPoints: List<ChartPoint>,
        sessions: List<TrainingSessions.Session>,
        sessionOpen: Boolean,
        zoneOffsetMs: Long,
        todayEpochDay: Long,
        nowMs: Long,
    ): LoadReading {
        val daily = if (stepPoints.isEmpty()) emptyMap() else RecoverySource.dailyLoad(stepPoints, zoneOffsetMs)
        val resting = RecoverySource.restingSpotHr(spotPoints)
        val weekAgo = nowMs - 7 * 86_400_000L
        val recent = sessions.filter { it.startMs >= weekAgo }
        val sessionLoad = if (resting == null) 0.0 else {
            recent.sumOf { RecoverySource.sessionLoad(it, spotPoints, resting) }
        }
        val cadenceWeekly = (0 until 7).mapNotNull { daily[todayEpochDay - it] }.sum()
        val weekly = (cadenceWeekly + sessionLoad).takeIf { daily.isNotEmpty() || recent.isNotEmpty() }
        // The ratio stays on the cadence channel alone: it compares like with like over 28 days, and
        // marked sessions have existed for less than that.
        val ratio = RecoverySource.loadRatio(daily, todayEpochDay)
        return LoadReading(
            weekly = weekly,
            weeklyFromSessions = sessionLoad.takeIf { recent.isNotEmpty() },
            sessionsThisWeek = recent.size,
            sessionOpen = sessionOpen,
            ratio = ratio,
            band = ratio?.let { RecoverySource.loadBand(it) },
            daysOfHistory = daily.size,
        )
    }

    private fun pointsOf(metrics: List<MetricChart>, key: String): List<ChartPoint> {
        val chart = metrics.firstOrNull { it.spec.key == key } ?: return emptyList()
        return chart.chunk?.segments?.flatMap { it.points } ?: chart.bars
    }
}
