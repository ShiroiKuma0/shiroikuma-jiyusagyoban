package com.opentasker.ui.charts

import com.opentasker.core.band.TrainingSessions

/**
 * Turning stored samples into the per-night numbers [Recovery] bands.
 *
 * Kept apart from [Recovery] for the same reason [HealthIndexSource] is kept apart from
 * [HealthIndex]: the arithmetic of "how unusual is this" should not know where numbers come from,
 * and the extraction should not know what they mean.
 *
 * Pure Kotlin. Every window, every exclusion and every fallback below was checked against 白い熊's
 * own ten days before it was written down; where the measurement disagreed with the literature's
 * recommendation, the comment says so and says which won.
 */
object RecoverySource {

    /** A nap is not a night. Below this a session is ignored entirely. */
    const val MIN_NIGHT_MINUTES = 240

    /** Sleep4h: skip the first half hour, then take four hours. */
    const val WINDOW_SKIP_MS = 30 * 60_000L
    const val WINDOW_LENGTH_MS = 4 * 3_600_000L

    /** ≥100 steps/min is the moderate-intensity cadence threshold (Tudor-Locke 2005). */
    const val MODERATE_CADENCE = 100.0

    /** One night's extracted numbers, before any banding. */
    data class NightMetrics(
        val startMs: Long,
        val nocturnalHr: Double?,
        val sleepMinutes: Double?,
        val skinTemp: Double?,
    )

    /** Real nights only, oldest first. */
    fun nights(sessions: List<SleepSession>): List<SleepSession> =
        sessions.filter { it.totalMinutes >= MIN_NIGHT_MINUTES }.sortedBy { it.startMs }

    /**
     * Nocturnal heart rate over the Sleep4h window — the single best-evidenced marker here.
     *
     * ## The window
     *
     * Sleep onset + 30 min, then four hours. Nuuttila et al. (2022, *IJSPP*; 2024, *Sports Med Open*)
     * found this segment the most responsive to training load, and — importantly — that morning
     * orthostatic measures were **not** sensitive at all in the same cohort. Polar's ANS charge uses
     * the same first-four-hours window and gives the same reason.
     *
     * ## Both populations, pooled — measured, not assumed
     *
     * The literature review said to use the periodic series alone, because the SpO₂-coincident
     * readings carry a different bias. That is true **during the day**, where they diverge with
     * movement (+22 bpm median with a hundred steps nearby). Inside a sleep window there is no
     * movement, and on 白い熊's own nights the two agree to 1 bpm.
     *
     * Pooling was checked rather than assumed: over eight nights the pooled and periodic-only means
     * correlate at **r = +0.993**, with the pooled version carrying a constant +0.34 bpm that cancels
     * in a within-person delta — and it **doubles the sample count**, from 23–33 to 47–57. That
     * matters: at the periodic-only rate two of those eight nights fell below the viability floor and
     * would have been thrown away. So: pooled.
     *
     * ## Awake minutes are excluded
     *
     * An arousal is exactly the sort of high reading whose presence or absence is decided by whether
     * 白い熊 happened to move, and letting them in makes the estimator noisier in proportion to how
     * restless the night was — i.e. biased by the thing being measured.
     */
    fun nocturnalHr(session: SleepSession, hrPoints: List<ChartPoint>): Double? {
        val from = session.startMs + WINDOW_SKIP_MS
        val to = from + WINDOW_LENGTH_MS
        val awake = session.runs.filter { it.code == '5' }
        val inWindow = hrPoints.filter { p ->
            p.tMs in from..to && awake.none { p.tMs >= it.startMs && p.tMs < it.endMs }
        }
        if (inWindow.size < Recovery.MIN_HR_SAMPLES_IN_WINDOW) return null
        return inWindow.sumOf { it.value } / inWindow.size
    }

    /**
     * Time actually asleep — total session minus the awake runs.
     *
     * Only sleep-vs-wake is used, never the deep/REM split. Consumer staging runs κ 0.20–0.53 against
     * polysomnography with per-stage sensitivity 0.33–0.70, and the single-night limits of agreement
     * on deep sleep (±60 to ±290 min across devices) exceed the physiological range of the quantity.
     * There is also no literature linking stage proportions to next-day readiness — the mechanisms are
     * real, the *marker* is unvalidated. Apple, which has the best-validated staging of any consumer
     * device, excludes stages from its own sleep score for the same reason.
     */
    fun sleepMinutes(session: SleepSession): Double =
        (session.totalMinutes - session.awake).toDouble()

    /** Median skin temperature inside the sleep window. Never a raw absolute — only a deviation. */
    fun skinTemp(session: SleepSession, tempPoints: List<ChartPoint>): Double? {
        val inSession = tempPoints.filter { it.tMs in session.startMs..session.endMs }
        return HealthIndexSource.median(inSession.map { it.value })
    }

    fun metricsFor(
        session: SleepSession,
        hrPoints: List<ChartPoint>,
        tempPoints: List<ChartPoint>,
    ): NightMetrics = NightMetrics(
        startMs = session.startMs,
        nocturnalHr = nocturnalHr(session, hrPoints),
        sleepMinutes = sleepMinutes(session),
        skinTemp = skinTemp(session, tempPoints),
    )

    /**
     * Minutes between the end of the last moderate-cadence effort and sleep onset, or null if there
     * was none within the window that matters.
     *
     * Leota et al. (2025): a bout ending ≥4 h before sleep onset has **no** measured association with
     * nocturnal heart rate; inside that window the effect climbs to +15 %. So this is the difference
     * between "you are not recovering" and "you trained at nine o'clock".
     */
    fun lateEffortMinutes(sessionStartMs: Long, stepPoints: List<ChartPoint>): Int? {
        val windowStart = sessionStartMs - Recovery.LATE_EFFORT_WINDOW_MIN * 60_000L
        val last = stepPoints
            .filter { it.value >= MODERATE_CADENCE && it.tMs in windowStart..sessionStartMs }
            .maxByOrNull { it.tMs } ?: return null
        return ((sessionStartMs - last.tMs) / 60_000L).toInt()
    }


    // --- marked sessions: where the heart-rate channel becomes legitimate --------------------------

    /**
     * MET-minutes above rest from heart rate, for ONE marked training session.
     *
     * The Wicks HR-index (`METs = 6·HR/HR_rest − 5`, from a meta-analysis of 220 datasets and 11 257
     * subjects) applied to each spot reading inside the window, each held across its own bucket.
     *
     * ## Why this is sound here and was not all-day
     *
     * Run across a whole day this same arithmetic produced 13 240 MET-min/week against a 500–1 000
     * reference band, because holding a ten-minute reading turns every transient — standing up,
     * stairs, a phone call — into ten minutes of exercise, 144 times over. Inside a window 白い熊 has
     * marked as a workout there are no transients to mistake: the elevation IS the session. The load
     * review's own simulation puts per-session error at a median of −4 %, and specifically about
     * −18 % for strength work, which is stated on the card rather than hidden.
     *
     * Strength sessions read low for a real reason: heart rate falls between sets, and the ten-minute
     * grid samples that trough as often as the effort. It is a floor for lifting, more than for
     * anything else.
     */
    fun sessionLoad(
        session: TrainingSessions.Session,
        spotPoints: List<ChartPoint>,
        restingHr: Double,
    ): Double {
        if (restingHr <= 0) return 0.0
        val inside = spotPoints.filter { it.tMs in session.startMs until session.endMs }
        if (inside.isEmpty()) return 0.0
        // Each reading stands for its share of the session, so a 40-minute session with four readings
        // credits ten minutes each — and one with a single reading credits the whole 40 rather than
        // silently under-reporting to a tenth of it.
        val minutesEach = session.minutes.toDouble() / inside.size
        return inside.sumOf { p ->
            val mets = maxOf(1.0, 6.0 * (p.value / restingHr) - 5.0)
            (mets - 1.0) * minutesEach
        }
    }

    /**
     * Resting heart rate for the HR-index, taken from the SPOT population.
     *
     * The 5th percentile over whatever history is loaded. It must come from the same population the
     * numerator does — the spot readings — because the periodic series reads a few bpm differently
     * and a mismatched denominator would bias every session in the same direction.
     */
    fun restingSpotHr(spotPoints: List<ChartPoint>): Double? =
        HealthIndexSource.percentile(spotPoints.map { it.value }, 0.05)

    // --- training load ----------------------------------------------------------------------------

    /**
     * MET-minutes above rest for one minute of walking at [cadence] steps/min.
     *
     * Moore et al. (2021, *MSSE*, n = 235, ages 21–84): `VO2 = 1.811 + 0.02014·cadence +
     * 7.427e-4·cadence²`, RMSE 2.09 ml/kg/min (≈0.6 MET), bias −0.01 — beating the ACSM equation by
     * 23–35 %. Level-grade walking only.
     *
     * ## Why cadence and not heart rate
     *
     * The load review's recommended metric made a heart-rate channel primary: Wicks HR-index METs
     * from each 10-minute spot reading, held across the bucket. Run against 白い熊's ten days that
     * produced **13 240 MET-min/week** against a 500–1 000 public-health reference band — 13× too
     * high, and still 7× too high after gating to ≥3 METs.
     *
     * The reason is visible once you look: the review's simulation measured reconstruction error
     * *inside training sessions*, and holding one spot reading across ten minutes of ordinary life
     * turns every transient — standing up, stairs, a phone call, digestion — into ten minutes of
     * exercise, 144 times a day. This channel, with no tuning at all, lands at **567 MET-min/week**.
     *
     * The cost is stated on screen rather than hidden: cycling, carrying and strength work leave no
     * step signature, so the figure is a floor, not a total.
     */
    fun metMinutes(cadence: Double): Double {
        if (cadence <= 0) return 0.0
        val vo2 = 1.811 + 0.02014 * cadence + 7.427e-4 * cadence * cadence
        return maxOf(0.0, vo2 / 3.5 - 1.0)
    }

    /** Daily MET-minute totals, keyed by day index (epoch days), from per-minute step points. */
    fun dailyLoad(stepPoints: List<ChartPoint>, zoneOffsetMs: Long): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        for (p in stepPoints) {
            val day = (p.tMs + zoneOffsetMs) / 86_400_000L
            out[day] = (out[day] ?: 0.0) + metMinutes(p.value)
        }
        return out
    }

    /**
     * Acute-to-chronic load, the 7-day mean over the 28-day mean.
     *
     * Polar's Cardio Load Status shape, with its published bands. It is deliberately **not** the
     * ACWR of the sports-science literature: that construct's own field has called for its dismissal
     * — replacing the chronic denominator with random numbers predicts injury about as well as the
     * real one (Impellizzeri 2021), and the single RCT of coaches acting on it was null (RR 1.01,
     * 95 % CI 0.91–1.12). Simulated over recreational load histories it is also a flat line: median
     * 0.99, with the "danger zone" firing on 0.14 % of days.
     *
     * So this is shown as *what you did lately against what you usually do*, with no injury claim
     * attached to it, which is the only reading the evidence supports.
     */
    fun loadRatio(daily: Map<Long, Double>, today: Long): Double? {
        val acute = (0 until 7).mapNotNull { daily[today - it] }
        val chronic = (0 until 28).mapNotNull { daily[today - it] }
        if (acute.size < 4 || chronic.size < 14) return null
        val a = acute.sum() / acute.size
        val c = chronic.sum() / chronic.size
        return if (c > 0) a / c else null
    }

    /**
     * Peak 30-minute cadence — the mean of the day's 30 highest step-count minutes.
     *
     * Not necessarily consecutive, which is the published definition. It matters because it is one of
     * the very few intensity measures that **survived adjustment for total volume**: in 15 cohorts and
     * 47 471 adults, peak-30 cadence carried a mortality hazard ratio of 0.67 (0.56–0.83) independent
     * of how many steps were taken, where "minutes above 100 steps/min" did not (HR 0.86, ns). So it
     * says something daily steps cannot, from data already stored.
     *
     * The NHANES population norm is 71.1 steps/min for peak-30 and 100.7 for peak-1.
     */
    fun peakCadence(stepPoints: List<ChartPoint>, minutes: Int): Double? {
        if (stepPoints.isEmpty()) return null
        val top = stepPoints.map { it.value }.sortedDescending().take(minutes)
        if (top.isEmpty()) return null
        return top.sum() / minutes
    }

    /** Today's per-minute step points, for the peak-cadence measures. */
    fun today(stepPoints: List<ChartPoint>, zoneOffsetMs: Long, todayEpochDay: Long): List<ChartPoint> =
        stepPoints.filter { (it.tMs + zoneOffsetMs) / 86_400_000L == todayEpochDay }

    /** Polar's published bands for that ratio. */
    fun loadBand(ratio: Double): LoadBand = when {
        ratio < 0.8 -> LoadBand.DETRAINING
        ratio <= 1.0 -> LoadBand.MAINTAINING
        ratio <= 1.3 -> LoadBand.PRODUCTIVE
        else -> LoadBand.OVERREACHING
    }
}

enum class LoadBand { DETRAINING, MAINTAINING, PRODUCTIVE, OVERREACHING }
