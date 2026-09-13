package com.opentasker.core.huawei

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HRV statistics — and a respiratory rate — computed from the band's per-beat series.
 *
 * ## Why compute anything, when the band already publishes a panel
 *
 * `rrisqi_data.bin` hands over ten floats per window and seven of them are named. Two of the four
 * quantities here are simply **not in that panel at any offset**: SDNN and pNN50 are the two most
 * commonly reported time-domain HRV metrics in the literature and Huawei does not compute them.
 * [rmssd] *is* in the panel, as f5, and is computed here anyway — it is the control that says the
 * pairing between a record and a window is sound, which is how f5 was identified in the first place.
 *
 * The fourth, [respirationBpm], is the reason the whole stream is now pulled routinely.
 *
 * ## The respiratory rate, and what it is honestly measuring
 *
 * Breathing modulates heart rate — respiratory sinus arrhythmia — so the dominant frequency of the
 * interval series inside the high-frequency band IS the breathing rate. This is the standard
 * "ECG-derived respiration" estimate applied to a PPG-derived tachogram, and the pipeline is the one
 * this repository already settled on when it identified f8 and f9 as HF and LF power: **linear
 * detrend, 4 Hz interpolation of the tachogram, a Hanning window, standard band edges.** Using a
 * different convention here would make the two incomparable for no reason.
 *
 * Three limits, stated rather than hidden:
 *
 *  * **Resolution is the window length.** A 60 s record resolves 1/60 Hz ≈ 1 breath/min, and no
 *    interpolation of the spectrum changes what was measured. The value is rounded to a tenth and
 *    should never be read as finer than a breath.
 *  * **The amplitude is not a tidal volume.** [rsaAmplitudeMs] is returned alongside the rate
 *    because the two together separate what HF power alone cannot — slower, deeper breathing lowers
 *    the rate, higher vagal tone raises the amplitude, and Huawei's f8 rises either way. But RSA
 *    amplitude depends on tidal volume and vagal tone *together*, so it is a modulation depth in
 *    milliseconds and never a volume in millilitres.
 *  * **RSA collapses when vagal tone does.** During hard effort, and in some people during illness,
 *    the HF peak flattens into the noise. That is why [respirationBpm] returns null on a weak peak
 *    rather than the argmax of a flat band — an argmax always exists, and a confident 14.3
 *    breaths/min computed from nothing is worse than an absent reading.
 *
 * ## Artefact correction is part of the measurement, not a polish step
 *
 * The band's PPG misses beats, and a missed beat records one interval of twice the local value. Over
 * 白い熊's own three days the uncorrected median RMSSD came out at **133 ms** — four to six times any
 * resting figure the band itself reports. [usable] therefore applies Malik's rule before any
 * time-domain statistic, at a threshold calibrated against the band's own f5 rather than chosen.
 *
 * Pure Kotlin, no Android, no clock — every input is passed in, so all of it is JVM-testable.
 */
object HuaweiBeatMetrics {

    /**
     * Which estimator produced a stored `beat_` value.
     *
     * Bumped whenever a change here would give a different answer for the SAME beats, so the sync
     * knows to recompute the stored series instead of leaving history frozen at whatever was current
     * the day it arrived.
     *
     * The band will not re-serve a file it has already handed over — a repeat request is answered
     * `0x00023281` rather than with bytes — so "it will heal on the next sync" is false for anything
     * derived from a file. The stored blobs are the only copy, which is exactly why they are stored.
     *
     *  * **1** — the first estimator. RAW intervals: no artefact correction, median RMSSD 133 ms.
     *  * **2** — Malik's 20 % rule before every time-domain statistic (2026-09-12).
     *  * **3** — the same arithmetic as 2, re-run because version 2's repair could not RETRACT
     *    (2026-09-12). It overwrote a value with a value and wrote nothing where the corrected
     *    window fell under [MIN_BEATS], so the four most artefact-ridden windows of 265 kept the
     *    four most absurd figures in the series. A version bump is the only way to reach rows a
     *    previous pass has already marked as current.
     */
    const val VERSION = 3

    /** The band's own quality index, at or above which a beat is used. Observed values: 100, 50, 0. */
    const val MIN_QUALITY = 50

    /** Fewest usable beats before any statistic is returned. */
    const val MIN_BEATS = 12

    /** The high-frequency band, in hertz — the standard respiratory window (9–24 breaths/min). */
    const val HF_LOW_HZ = 0.15
    const val HF_HIGH_HZ = 0.40

    /** Interpolation rate for the tachogram, in hertz. The convention f8/f9 were identified under. */
    const val RESAMPLE_HZ = 4.0

    /**
     * How far the HF peak must stand above the band's own mean power to be called a breathing rate.
     *
     * A flat band still has a maximum, so without this a respiratory rate exists for every window
     * ever recorded. **Both this and [MIN_RSA_AMPLITUDE_MS] are required, because each one alone
     * passes a case the other catches** — measured over synthetic tachograms rather than guessed:
     *
     * | input | peak ratio | amplitude |
     * |---|---|---|
     * | real RSA, 40 ms, clean | 9.9 | 32 ms |
     * | real RSA, 25 ms + 20 ms noise | 6.4 | 21 ms |
     * | white noise, SD 20 ms | 2.4–4.5 | 6.6–11 ms |
     * | a pure heart-rate trend | **10.4** | **0.001 ms** |
     *
     * The trend is the instructive row. A drifting heart rate leaves a residual whose leakage all
     * falls in the bottom bin of the band, so it is *more* peaked than real breathing — a ratio test
     * alone reports 9 breaths/min on a tachogram with no oscillation in it whatsoever. Only the
     * amplitude sees that there is nothing there. Conversely white noise has a perfectly respectable
     * amplitude and no peak, and only the ratio sees that.
     *
     * **5.0 is an operating point, chosen off a measured curve rather than picked.** 400 white-noise
     * windows against 200 windows per (RSA amplitude + noise) combination, all at 60 s:
     *
     * ```
     * threshold   false positives   detection: 40+10  40+20  25+10  25+20  15+10
     *   4.0           18.2 %                   100%   100%   100%    98%   100%
     *   5.0            5.0 %                   100%   100%   100%    87%    97%
     *   6.0            0.8 %                   100%    99%   100%    68%    86%
     *   7.0            0.2 %                   100%    92%   100%    28%    60%
     * ```
     *
     * 4.0 lets nearly a fifth of pure artefact through; 6.0 starts discarding a third of genuine
     * breathing at 25 ms RSA. 5.0 keeps everything above 25 ms modulation in ordinary noise and
     * costs a twentieth of the artefact windows — and a NIGHT is a median over some sixty records,
     * where a twentieth contaminates nothing.
     */
    const val PEAK_RATIO = 5.0

    /**
     * The smallest RSA oscillation, in milliseconds, that counts as breathing.
     *
     * Amplitude of the peak component in the tachogram's own unit, so it is directly comparable to
     * an interval. It tracks the true modulation almost independently of noise (40 → 32, 25 → 21,
     * 15 → 13 in the measurements above), which is what makes it a usable gate rather than another
     * threshold to tune. 2.0 ms is an order of magnitude under the weakest genuine RSA measured and
     * three orders above a pure trend's residual.
     */
    const val MIN_RSA_AMPLITUDE_MS = 2.0

    /**
     * Malik's tolerance: how far an interval may sit from the previous ACCEPTED one and be believed.
     *
     * The standard published artefact filter, and 20 % is its standard threshold. It is here because
     * the band's PPG misses beats: a missed detection records one interval of twice the local value
     * (714 → 1434 ms in 白い熊's own data, and 717 → 2106 for two missed in a row), and a spurious
     * detection records a half one (382 ms). Every successive-difference statistic is destroyed by
     * those — they are the largest differences in the window by an order of magnitude.
     */
    const val MALIK_TOLERANCE = 0.20

    /**
     * The beats a statistic may be computed from: readable, the band did not disown them, and
     * artefact-corrected.
     *
     * **The correction is not optional and is not a matter of taste — it was calibrated against the
     * band's own figure.** `rrisqi` field f5 IS RMSSD (established to within 1 % over 69 of 124
     * windows), so the band publishes the answer and this can be checked rather than argued about.
     * Over the 109 records from 白い熊's wrist that provably pair with an rrisqi window:
     *
     * ```
     * rule                    ratio to f5   |err| median   within 10%   whole-set median
     * quality >= 50, raw          1.007          0.9%          62%          133 ms
     * quality = 100, raw          1.004          0.7%          74%           43 ms
     * quality >= 50 + Malik 20%   1.000          0.7%          80%           34 ms
     * quality >= 50 + median 20%  1.003          0.7%          78%           35 ms
     * ```
     *
     * The raw median of **133 ms is not a heart-rate variability**, it is an artefact count wearing
     * the name of one — and the paired-window ratio of 1.007 would have hidden that completely,
     * because the windows that pair are the clean ones. It took the whole-set column to see it.
     * Malik wins on both agreement and spread (p90 61 ms against 92 for the median filter), so that
     * is the rule, at its own published threshold.
     *
     * **Dropping intervals breaks the time axis**, so this is for the time-domain statistics only.
     * The respiratory estimate takes [timeSeries] instead — see the note there.
     */
    fun usable(beats: List<HuaweiBeats.Beat>): List<Int> = corrected(timeSeries(beats))

    /**
     * Quality-gated intervals with NO artefact correction — the series as the band recorded it.
     *
     * For the respiratory estimate, which measures a frequency and therefore cannot have intervals
     * deleted from under it: removing one interval removes the time it occupied, and everything
     * after it slides earlier. A missed beat does inject a spike here, but that spreads power across
     * the whole band rather than concentrating it, so it pushes [hfPeak] toward REJECTING the window
     * — the safe direction, and the one its two gates were measured on.
     */
    fun timeSeries(beats: List<HuaweiBeats.Beat>): List<Int> =
        beats.filter { (it.quality ?: 0) >= MIN_QUALITY }.map { it.intervalMs }

    /**
     * Malik's rule: keep an interval only if it is within [MALIK_TOLERANCE] of the last one kept.
     *
     * Compared against the last ACCEPTED interval rather than the last one seen, which is what makes
     * it survive a run of bad beats — comparing to the previous raw value lets the first artefact
     * become the reference and admits the second.
     */
    fun corrected(intervals: List<Int>, tolerance: Double = MALIK_TOLERANCE): List<Int> {
        if (intervals.isEmpty()) return intervals
        val out = ArrayList<Int>(intervals.size)
        out += intervals.first()
        for (x in intervals.drop(1)) {
            if (abs(x - out.last()) <= tolerance * out.last()) out += x
        }
        return out
    }

    /** Mean RR interval, ms — the panel's f6, recomputed as the pairing anchor. */
    fun meanRrMs(intervals: List<Int>): Double? =
        if (intervals.size < MIN_BEATS) null else intervals.sumOf { it.toDouble() } / intervals.size

    /**
     * SDNN — the standard deviation of the intervals, ms.
     *
     * Not in Huawei's panel. Reflects total variability over the window, where [rmssd] reflects the
     * beat-to-beat part; on a ~60 s window it is dominated by respiratory and slower rhythms.
     */
    fun sdnn(intervals: List<Int>): Double? {
        val mean = meanRrMs(intervals) ?: return null
        // Sample SD (n − 1). On a 60-beat window the difference from the population form is under
        // 1 %, but this is a published statistic with a published definition and quoting a number
        // under its name means computing it that way.
        val ss = intervals.sumOf { (it - mean) * (it - mean) }
        return sqrt(ss / (intervals.size - 1))
    }

    /** RMSSD, ms — the panel's f5. Computed here as the check that a record and a window pair. */
    fun rmssd(intervals: List<Int>): Double? {
        if (intervals.size < MIN_BEATS) return null
        val d = intervals.zipWithNext { a, b -> (b - a).toDouble() }
        return sqrt(d.sumOf { it * it } / d.size)
    }

    /**
     * pNN50 — the share of successive intervals differing by more than 50 ms, as a percentage.
     *
     * Not in Huawei's panel either. A blunt instrument by design: it is the one HRV metric whose
     * threshold is fixed in absolute milliseconds rather than scaled to the person, which is exactly
     * why it is reported alongside RMSSD rather than instead of it.
     */
    fun pnn50(intervals: List<Int>): Double? {
        if (intervals.size < MIN_BEATS) return null
        val d = intervals.zipWithNext { a, b -> abs(b - a) }
        return 100.0 * d.count { it > 50 } / d.size
    }

    /**
     * The respiratory peak: how fast, how strong, and how clearly it stood out.
     *
     * [amplitudeMs] is the second half of the answer and the reason this is not just a frequency.
     * Huawei's panel publishes HF *power* (f8) and no peak frequency, so a night of elevated f8
     * cannot be read: slower, deeper breathing and higher vagal tone both raise it. A rate and an
     * amplitude separate them — the rate falls in the first case and the amplitude rises in the
     * second — which is exactly the distinction the stored `beat_` metrics exist to make.
     */
    data class Peak(val hz: Double, val amplitudeMs: Double, val peakRatio: Double) {
        val breathsPerMinute: Double get() = (hz * 60.0 * 10.0).roundToInt() / 10.0
    }

    /**
     * Breaths per minute from respiratory sinus arrhythmia, or null when no peak stands out.
     *
     * Null is a real answer and the common one during effort — see the class note. A caller must not
     * substitute a default for it.
     */
    fun respirationBpm(intervals: List<Int>): Double? = hfPeak(intervals)?.breathsPerMinute

    /** How far the intervals swing at the respiratory frequency, in ms. Null when there is no peak. */
    fun rsaAmplitudeMs(intervals: List<Int>): Double? =
        hfPeak(intervals)?.let { (it.amplitudeMs * 10.0).roundToInt() / 10.0 }

    /**
     * The dominant oscillation inside the HF band, or null when it is not convincing.
     *
     * Separated from [respirationBpm] so the frequency can be tested directly against a synthetic
     * tachogram — a test that asserts "14.4 breaths/min" is one rounding away from being about the
     * rounding.
     */
    fun hfPeak(intervals: List<Int>): Peak? {
        if (intervals.size < MIN_BEATS) return null
        val series = resample(intervals) ?: return null
        detrend(series)
        // A Hanning window, because the tachogram is not periodic in the window and the leakage from
        // a rectangular one spreads a real peak across the whole band. It also fixes the spectral
        // normalisation this repository already had to pin down: a Hanning window sums to n/2, so
        // normalising by n² as if it were rectangular is a factor of exactly 4 — the 0.248 scale f8
        // and f9 first fitted at.
        val n = series.size
        for (i in 0 until n) series[i] *= 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))

        val df = RESAMPLE_HZ / n
        val lo = kotlin.math.ceil(HF_LOW_HZ / df).toInt()
        val hi = kotlin.math.floor(HF_HIGH_HZ / df).toInt()
        if (hi <= lo) return null

        var best = -1
        var bestPower = 0.0
        var total = 0.0
        for (k in lo..hi) {
            val p = power(series, k)
            total += p
            if (p > bestPower) {
                bestPower = p
                best = k
            }
        }
        val bins = hi - lo + 1
        if (best < 0 || total <= 0.0) return null
        // Peak against the mean of the band INCLUDING itself. Excluding it would make the ratio
        // climb without bound as the band narrows, which is the opposite of a stability property.
        val ratio = bestPower * bins / total
        if (ratio < PEAK_RATIO) return null
        // Back to an amplitude in the tachogram's own unit. A Hanning window sums to n/2, so a
        // sinusoid of amplitude A puts |X| = A·n/4 in its bin — the same factor of 4 this repository
        // had to pin down when f8 and f9 first fitted at a scale of 0.248.
        val amplitude = 4.0 * sqrt(bestPower) / n
        if (amplitude < MIN_RSA_AMPLITUDE_MS) return null
        return Peak(best * df, amplitude, ratio)
    }

    /**
     * The tachogram, interpolated onto an even grid at [RESAMPLE_HZ].
     *
     * The series is irregular by construction — each interval is both a value and its own duration —
     * and a DFT of the raw values would be a spectrum against beat NUMBER rather than against time.
     * That is the single mistake that makes an RSA estimate quietly wrong rather than noisy: at 60
     * bpm the two coincide, and they diverge exactly when the heart rate moves.
     */
    private fun resample(intervals: List<Int>): DoubleArray? {
        // Each interval is placed at the instant the beat it measures arrived.
        val times = DoubleArray(intervals.size)
        var t = 0.0
        for (i in intervals.indices) {
            t += intervals[i] / 1000.0
            times[i] = t
        }
        val span = times.last() - times.first()
        if (span <= 0.0) return null
        val n = (span * RESAMPLE_HZ).toInt()
        // Under two seconds of signal cannot hold a breath, let alone resolve one.
        if (n < 8) return null
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val ti = times.first() + i / RESAMPLE_HZ
            while (j < times.size - 2 && times[j + 1] < ti) j++
            val t0 = times[j]
            val t1 = times[j + 1]
            val v0 = intervals[j].toDouble()
            val v1 = intervals[j + 1].toDouble()
            out[i] = if (t1 > t0) v0 + (v1 - v0) * (ti - t0) / (t1 - t0) else v0
        }
        return out
    }

    /** Remove the least-squares straight line, in place. A trend leaks into every low bin otherwise. */
    private fun detrend(x: DoubleArray) {
        val n = x.size
        if (n < 2) return
        val meanI = (n - 1) / 2.0
        val meanX = x.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (i - meanI) * (x[i] - meanX)
            den += (i - meanI) * (i - meanI)
        }
        val slope = if (den > 0) num / den else 0.0
        for (i in 0 until n) x[i] -= meanX + slope * (i - meanI)
    }

    /** |X(k)|² for one bin. A whole FFT would be wasted — the HF band is about fifteen bins. */
    private fun power(x: DoubleArray, k: Int): Double {
        var re = 0.0
        var im = 0.0
        val n = x.size
        for (i in 0 until n) {
            val a = 2.0 * PI * k * i / n
            re += x[i] * cos(a)
            im -= x[i] * sin(a)
        }
        return re * re + im * im
    }
}
