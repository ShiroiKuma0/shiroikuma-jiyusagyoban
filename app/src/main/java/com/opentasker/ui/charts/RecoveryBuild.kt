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

    data class Assembled(
        val recovery: RecoveryResult?,
        val load: LoadReading,
        /** Sleep Regularity Index — see [SleepRegularity]. Null until a week of nights exists. */
        val sri: Double?,
        /** Last night, on Apple's published 50/30/20 weights — see [SleepScore]. */
        val sleepScore: SleepScore.Breakdown?,
        /** Mean of the day's 30 highest step-count minutes. NHANES norm 71.1. */
        val peak30Cadence: Double?,
        /** Travel and altitude, detected and said out loud — see [RecoveryRegime]. */
        val regime: RecoveryRegime.Regime,
        /** Every marked session beside the night that followed it — see [SessionRegister]. */
        val register: SessionRegister.Register,
    )

    /**
     * [ratings] maps a `yyyyMMdd` local date to 1–5; a night is attributed to the day it STARTED,
     * matching [DailySummary] and the band's own noon-to-noon chunking. So the rating 白い熊 gives on
     * waking is filed against the night before it, which is the night it describes.
     */
    fun build(
        metrics: List<MetricChart>,
        sessions: List<SleepSession>,
        ratings: Map<Long, Int>,
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
        val peak30 = RecoverySource.peakCadence(RecoverySource.today(stepPoints, zoneOffsetMs, todayEpochDay), 30)

        val nights = RecoverySource.nights(sessions)
        val spo2Points = pointsOf(metrics, BandMetric.SPO2)
        val spo2ByNight = nights.mapNotNull { n ->
            HealthIndexSource.median(spo2Points.filter { it.tMs in n.startMs..n.endMs }.map { it.value })
        }
        val regime = RecoveryRegime.detect(offsetsByDay, todayEpochDay, spo2ByNight)

        val restingSpot = RecoverySource.restingSpotHr(spotPoints)
        // Five weeks ending today: long enough to see a training pattern, short enough that every
        // square on screen is a day 白い熊 might remember.
        val gridFrom = todayEpochDay - 34
        val history = nights.map { RecoverySource.metricsFor(it, hrPoints, tempPoints) }
        val feltFor = { m: RecoverySource.NightMetrics -> ratings[localDateOf(m.startMs)]?.toDouble() }
        val register = SessionRegister.build(
            sessions = sessions_,
            nights = SessionRegister.readNights(history, feltFor),
            spotPoints = spotPoints,
            restingHr = restingSpot,
            zoneOffsetMs = zoneOffsetMs,
            fromEpochDay = gridFrom,
            toEpochDay = todayEpochDay,
        )

        if (nights.isEmpty()) {
            return Assembled(null, load, sri, null, peak30, regime, register)
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

        // "Sustained" means the night before was warm too. One warm night at the wrist is the
        // bedroom, not 白い熊 — ambient correlates with the sensor at r = 0.961.
        val previousWarm = prior.lastOrNull()?.skinTemp?.let { previous ->
            val beforeThat = prior.dropLast(1).mapNotNull { it.skinTemp }
            Recovery.band(
                RecoveryMarker.TEMPERATURE, previous, beforeThat,
                Recovery.TEMP_MEANINGFUL_C, confidence, counted = false, oneSidedHigh = true,
            ).band == RecoveryBand.HIGH
        } ?: false

        return Assembled(
            recovery = Recovery.assemble(
                nightStartMs = latest.startMs,
                nocturnalHr = hr,
                sleep = sleep,
                felt = felt,
                temperature = temp,
                temperatureSustained = temp.band == RecoveryBand.HIGH && previousWarm,
                lateEffortMinutesBeforeSleep = RecoverySource.lateEffortMinutes(latest.startMs, stepPoints),
                nightsOfHistory = prior.size,
            ),
            load = load,
            sri = sri,
            sleepScore = sleepScore,
            peak30Cadence = peak30,
            regime = regime,
            register = register,
        )
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
