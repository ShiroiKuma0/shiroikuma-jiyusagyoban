package com.opentasker.ui.charts

import kotlin.math.abs
import kotlin.math.max

/**
 * 回復 — what last night cost, and how far it is from 白い熊's own normal.
 *
 * ## What this is NOT, and why
 *
 * It is not a readiness percentage. 白い熊 asked for one (2026-08-09) and four independent literature
 * reviews came back saying the same thing from four directions, so the shape below is the compromise
 * 白い熊 chose knowing the evidence:
 *
 * - **No commercial readiness composite has ever been validated.** Zero peer-reviewed studies test
 *   Garmin Body Battery, Garmin Training Readiness, Polar Nightly Recharge or Fitbit Daily Readiness
 *   against any outcome; the one positive study of WHOOP Recovery was written by six WHOOP employees.
 *   No RCT has ever tested whether showing someone a readiness score changes anything at all.
 * - **The only composite shape with published support is a COUNTING rule.** Nuuttila et al. (2024,
 *   *Scand J Med Sci Sports*) got PPV 92 % / NPV 100 % for detecting functional overreaching from
 *   "≥2 of 3 criteria elevated" — against ≥85 % for each criterion alone. Not a weighted sum: there
 *   is no validated weighting scheme in existence, and inventing coefficients would hide which
 *   component fired. So this file counts, and never weights.
 * - **A confident wrong verdict has a measured cost.** Randomised sham "you slept badly" feedback
 *   degraded self-reported daytime function at d = 0.55–0.79 (Gavriloff 2018, n = 63; Draganich &
 *   Erdal 2014, n = 164). Both studies found the harm in *appraisal* rather than capacity — which is
 *   still a real harm, and it is why nothing here says "take it easy".
 * - **Numbers beat hedges.** Across five experiments (van der Bles et al. 2020, *PNAS*, n = 5 780) an
 *   explicit numeric range cost almost nothing in trust (d = −0.15) where a verbal hedge cost a lot
 *   (d = −0.55). So every marker carries its number and its usual range, and the text never waffles.
 *
 * ## The three counted markers
 *
 * Deliberately the same shape as Nuuttila's validated trio — one cardiac, one sleep, one subjective:
 *
 * | marker | why it is here |
 * |---|---|
 * | [nocturnal HR][RecoveryMarker.NOCTURNAL_HR] | the best-evidenced signal this hardware can produce, and the most accurate (MAPE 1.7–3.0 % against ECG across consumer devices) |
 * | [sleep duration][RecoveryMarker.SLEEP] | half of the only validated illness conjunction (Radin 2020, n = 47 249), and the one stage-derived quantity wearables measure well |
 * | [how you feel][RecoveryMarker.FELT] | out-performed every objective marker in 56 studies (Saw 2016) and beat HRV-guided training in a 3-arm RCT (Figueiredo 2022) |
 *
 * **Skin temperature is shown but NOT counted**, and only ever downward — see [RecoveryMarker.TEMPERATURE].
 *
 * ## Why every threshold is a conjunction
 *
 * A marker fires only when the deviation is **both statistically unusual for 白い熊 AND large enough
 * that the literature calls it meaningful**. Either test alone misbehaves: a z-score alone goes off in
 * a quiet fortnight when the dispersion estimate collapses (白い熊's own eight nights sit at a 3.4 %
 * CV, which is the published same-condition *noise floor* — nearly all of that spread is the sensor),
 * and an absolute threshold alone ignores that one person's 3 bpm is another's 8.
 *
 * Pure Kotlin, no Android, no clock — every input is passed in, so all of it is JVM-testable.
 */

/** One thing measured about a night, and how unusual it was. */
enum class RecoveryMarker { NOCTURNAL_HR, SLEEP, FELT, TEMPERATURE }

/** Which side of usual a marker landed on. */
enum class RecoveryBand { LOW, USUAL, HIGH, UNKNOWN }

/**
 * One marker's reading for one night.
 *
 * [value] and [baseline] are in the marker's own unit; [usualLo]/[usualHi] are the band edges that
 * would have to be crossed to leave [RecoveryBand.USUAL], so the UI can print "usual 55–61" without
 * re-deriving anything.
 */
data class MarkerReading(
    val marker: RecoveryMarker,
    val value: Double?,
    val baseline: Double?,
    val usualLo: Double?,
    val usualHi: Double?,
    val z: Double?,
    val band: RecoveryBand,
    /** True when this marker is one of the three the headline counts. */
    val counted: Boolean,
) {
    val delta: Double? get() = if (value != null && baseline != null) value - baseline else null

    /** Fired in the direction that means "worse", which is the only direction the count cares about. */
    val adverse: Boolean
        get() = when (marker) {
            // A LOW resting HR is a good night, not an event. Only elevation counts.
            RecoveryMarker.NOCTURNAL_HR -> band == RecoveryBand.HIGH
            // Sleeping much longer than usual is not a problem to report.
            RecoveryMarker.SLEEP -> band == RecoveryBand.LOW
            // 体感 runs 1 = best … 5 = worst since 2026-08-12, so a HIGH rating is now the bad night.
            // The stored ratings were re-numbered the same day (see RecoveryLog), which is what keeps
            // this one-word change honest: history is on the new scale too, not half on each.
            RecoveryMarker.FELT -> band == RecoveryBand.HIGH
            RecoveryMarker.TEMPERATURE -> band == RecoveryBand.HIGH
        }

    /**
     * This reading on the shared 1–5 scale, or null when there is nothing to grade.
     *
     * 3 is inside the usual range; 4 and 5 are outside it in the direction that means worse, 2 and 1
     * outside it in the direction that means better, with the far step reached at twice the band's own
     * half-width. It is a re-expression of the banding that is already computed and NOT a new statistic:
     * the same baseline, the same half-width, the same directions [adverse] uses. Nothing is averaged
     * and no threshold is invented — the scale only says which side of the band the value fell, and
     * how far.
     *
     * Temperature never reaches 1 or 2. It is banded one-sided because only elevation is meaningful at
     * the wrist; a cool night is unremarkable, not good, and colouring it as a best-step night would
     * assert something the measurement cannot support.
     */
    val scaleStep: Int?
        get() {
            val v = value ?: return null
            val b = baseline ?: return null
            val halfWidth = usualHi?.minus(b)?.takeIf { it > 0 } ?: return null
            if (band == RecoveryBand.UNKNOWN) return null
            val deviations = (v - b) / halfWidth
            if (deviations in -1.0..1.0) return 3
            // 体感 sits with heart rate and temperature since the 2026-08-12 flip: on the new scale a
            // HIGHER number is a worse night, the same direction those two already ran in.
            val worse = when (marker) {
                RecoveryMarker.NOCTURNAL_HR, RecoveryMarker.TEMPERATURE, RecoveryMarker.FELT ->
                    deviations > 0
                RecoveryMarker.SLEEP -> deviations < 0
            }
            val far = kotlin.math.abs(deviations) >= 2.0
            if (!worse && marker == RecoveryMarker.TEMPERATURE) return 3
            return when {
                worse && far -> 5
                worse -> 4
                far -> 1
                else -> 2
            }
        }
}

/** How much history the baselines rest on — and therefore what may honestly be displayed. */
enum class RecoveryConfidence {
    /** Under 5 nights. Collect only: show the raw numbers, no baseline, no bands, no headline. */
    COLLECTING,

    /**
     * 5–13 nights. Absolute deltas against a median, no z-scores.
     *
     * The location estimate is usable from about 7 nights (its standard error drops under the
     * smallest worthwhile change), but the DISPERSION estimate is not: at n = 7 the SD is itself
     * ±29 %, so z-scores built on it produce false alarms. Fixed absolute thresholds sidestep it.
     */
    PROVISIONAL,

    /** 14+ nights. Full z-scores against a rolling median with a MAD-derived dispersion. */
    ESTABLISHED,
}

/** Everything the 回復 card and screen draw. */
data class RecoveryResult(
    val nightStartMs: Long?,
    val markers: List<MarkerReading>,
    val confidence: RecoveryConfidence,
    val nightsOfHistory: Int,
    /** How many of the three counted markers fired adversely: 0, 1, 2 or 3. */
    val adverseCount: Int,
    /** The counted markers that fired, for naming them in the headline. */
    val adverseMarkers: List<RecoveryMarker>,
    /**
     * A late-workout annotation, when one plausibly explains an elevated heart rate.
     *
     * Leota et al. (2025, *Nat Commun*, n = 14 689, >4 M person-nights) measured a dose–response:
     * exercise ending 2 h BEFORE habitual sleep onset raised nocturnal resting HR 6.8 %, ending 2 h
     * AFTER onset raised it 15.0 %, and **bouts ending ≥4 h before onset showed no association at
     * all**. So a hard effort inside that window is an explanation for the night, not a verdict about
     * 白い熊 — and saying so turns a false alarm into the actual insight.
     */
    val lateEffortMinutesBeforeSleep: Int?,
    /**
     * The published illness conjunction, kept apart from the recovery reading.
     *
     * Radin et al. (2020, *Lancet Digit Health*, 47 249 Fitbit users, 13.3 M measurements): a week
     * was abnormal when resting HR ran >0.5 SD above the person's own average AND sleep ran >0.5 SD
     * below theirs. That threshold pair gave the highest correlation with CDC influenza-like-illness
     * rates. It is a flag in its own right, not a component of anything.
     */
    val illnessSigns: Boolean,
) {
    val hasHeadline: Boolean get() = confidence != RecoveryConfidence.COLLECTING
}

object Recovery {

    // --- band edges -------------------------------------------------------------------------------

    /**
     * How many robust SDs from the baseline before a marker leaves "usual".
     *
     * 1.5 rather than 1.0 because that is where the two independent anchors meet: the smallest
     * deviation the wearable-validation literature calls clinically meaningful for resting HR is
     * 5–7 bpm (Dial et al. 2025, 536 nights) against a within-person night-to-night SD of 3–4 bpm,
     * which is ~1.5 SD. At 1.0 SD each marker would fire ~16 % of nights and the ≥2-of-3 headline
     * would fire roughly fortnightly on noise alone.
     */
    const val BAND_SIGMA = 1.5

    /**
     * The dispersion floor for **nocturnal heart rate**, as a fraction of the baseline.
     *
     * Nocturnal HR repeated under the SAME conditions still moves ~3.5 % (Mishica 2022; Nuuttila
     * 2022 reports ICC .97–.98 with CV < 4 %). That is the sensor, not 白い熊. Without this floor a
     * quiet fortnight shrinks the estimated dispersion toward zero and a 2 bpm drift scores z = 4.
     *
     * **It is a fraction of the heart rate and of nothing else.** Applied to a marker on an absolute
     * scale it is nonsense: 3.5 % of a 36.4 °C skin temperature is a 1.27 °C floor, roughly four
     * times the entire physiological signal, which would silence the temperature marker permanently.
     * Every other marker therefore passes a floor of zero and leans on its own smallest-worthwhile-
     * change instead. Caught by [RecoveryTest] before it ever ran on a device.
     */
    const val HR_SIGMA_FLOOR_FRACTION = 0.035

    /** Smallest worthwhile change per marker — the second half of every conjunction. */
    const val HR_MEANINGFUL_BPM = 5.0
    const val SLEEP_MEANINGFUL_MIN = 30.0
    const val FELT_MEANINGFUL_STEPS = 1.0
    const val TEMP_MEANINGFUL_C = 0.3

    const val MIN_NIGHTS_FOR_ANY = 5
    const val MIN_NIGHTS_FOR_Z = 14

    /** Rolling baseline length once established. Long enough to be stable, short enough to track drift. */
    const val BASELINE_NIGHTS = 28

    /** Below this the Sleep4h window is too thin to mean anything. */
    const val MIN_HR_SAMPLES_IN_WINDOW = 25

    /** Leota's threshold: a bout ending this long before sleep onset has no measured effect. */
    const val LATE_EFFORT_WINDOW_MIN = 240

    // --- robust statistics ------------------------------------------------------------------------

    fun median(values: List<Double>): Double? = HealthIndexSource.percentile(values, 0.5)

    /**
     * Median absolute deviation, scaled to a standard deviation.
     *
     * MAD rather than SD because a single illness night or a night on a plane would otherwise inflate
     * the dispersion and hide the next month of real deviations. 1.4826 is the consistency constant
     * that makes MAD estimate σ for normally-distributed data.
     */
    fun madSigma(values: List<Double>): Double? {
        val m = median(values) ?: return null
        return median(values.map { abs(it - m) })?.times(1.4826)
    }

    // --- the per-marker test ----------------------------------------------------------------------

    /**
     * Band one marker against its own history.
     *
     * [history] is the marker's previous nights, newest-first or oldest-first — order is irrelevant,
     * only the distribution is used, and the current night must NOT be in it (a value cannot be part
     * of the baseline it is judged against).
     *
     * [meaningful] is the smallest change the literature calls worth acting on, in the marker's unit,
     * and it floors the band edge: a marker never fires on a statistically-unusual-but-trivial move.
     */
    fun band(
        marker: RecoveryMarker,
        value: Double?,
        history: List<Double>,
        meaningful: Double,
        confidence: RecoveryConfidence,
        counted: Boolean,
        oneSidedHigh: Boolean = false,
        /** Absolute floor under the robust dispersion, in the marker's own unit. Zero for most. */
        sigmaFloor: Double = 0.0,
    ): MarkerReading {
        val baseline = median(history)
        if (value == null || baseline == null || confidence == RecoveryConfidence.COLLECTING) {
            return MarkerReading(marker, value, baseline, null, null, null, RecoveryBand.UNKNOWN, counted)
        }
        // PROVISIONAL uses the absolute threshold alone: the dispersion estimate is not yet worth
        // trusting, and pretending otherwise is how a short history manufactures alarms.
        val sigma = if (confidence == RecoveryConfidence.ESTABLISHED) {
            max(madSigma(history) ?: 0.0, sigmaFloor)
        } else {
            null
        }
        val halfWidth = if (sigma != null) max(sigma * BAND_SIGMA, meaningful) else meaningful
        val lo = baseline - halfWidth
        val hi = baseline + halfWidth
        val z = if (sigma != null && sigma > 0) (value - baseline) / sigma else null
        val raw = when {
            value > hi -> RecoveryBand.HIGH
            value < lo -> RecoveryBand.LOW
            else -> RecoveryBand.USUAL
        }
        // A one-sided marker can leave "usual" upward only — see TEMPERATURE.
        val band = if (oneSidedHigh && raw == RecoveryBand.LOW) RecoveryBand.USUAL else raw
        return MarkerReading(marker, value, baseline, lo, hi, z, band, counted)
    }

    /** The heart-rate marker, with its own measured dispersion floor applied. */
    fun bandNocturnalHr(
        value: Double?,
        history: List<Double>,
        confidence: RecoveryConfidence,
    ): MarkerReading = band(
        marker = RecoveryMarker.NOCTURNAL_HR,
        value = value,
        history = history,
        meaningful = HR_MEANINGFUL_BPM,
        confidence = confidence,
        counted = true,
        sigmaFloor = (median(history) ?: 0.0) * HR_SIGMA_FLOOR_FRACTION,
    )

    fun confidenceFor(nights: Int): RecoveryConfidence = when {
        nights < MIN_NIGHTS_FOR_ANY -> RecoveryConfidence.COLLECTING
        nights < MIN_NIGHTS_FOR_Z -> RecoveryConfidence.PROVISIONAL
        else -> RecoveryConfidence.ESTABLISHED
    }

    /**
     * Assemble one night's reading.
     *
     * [temperatureSustained] must be true only when the previous night's temperature was ALSO
     * elevated. A single-night wrist-temperature spike is not actionable: the sensor is measuring the
     * bedroom nearly as much as 白い熊 (r = 0.961 with ambient in simultaneous measurement, Sato
     * 2024), and the ambient/bedding term is 3–20× the size of the physiological signal.
     */
    fun assemble(
        nightStartMs: Long?,
        nocturnalHr: MarkerReading,
        sleep: MarkerReading,
        felt: MarkerReading,
        temperature: MarkerReading,
        temperatureSustained: Boolean,
        lateEffortMinutesBeforeSleep: Int?,
        nightsOfHistory: Int,
    ): RecoveryResult {
        val confidence = confidenceFor(nightsOfHistory)
        val tempShown = if (temperatureSustained) temperature else temperature.copy(band = RecoveryBand.USUAL)
        val markers = listOf(nocturnalHr, sleep, felt, tempShown)
        val fired = markers.filter { it.counted && it.adverse }.map { it.marker }
        // Radin's conjunction, at ITS thresholds (0.5 SD), not the 1.5 SD display band: it is a
        // separate published rule with its own operating point, and re-using our band edges would
        // silently change what it detects.
        val illness = (nocturnalHr.z ?: 0.0) >= 0.5 && (sleep.z ?: 0.0) <= -0.5
        return RecoveryResult(
            nightStartMs = nightStartMs,
            markers = markers,
            confidence = confidence,
            nightsOfHistory = nightsOfHistory,
            adverseCount = fired.size,
            adverseMarkers = fired,
            lateEffortMinutesBeforeSleep = lateEffortMinutesBeforeSleep,
            illnessSigns = illness && confidence == RecoveryConfidence.ESTABLISHED,
        )
    }
}
