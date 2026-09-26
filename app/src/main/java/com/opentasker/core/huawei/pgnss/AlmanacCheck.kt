package com.opentasker.core.huawei.pgnss

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * How far the almanac puts a satellite from where it actually is — in kilometres, not in days.
 *
 * ## Why an age was never the answer
 *
 * The almanac decides **which satellites the receiver looks for and roughly where**, so a bad one
 * costs the fix outright while every other instrument reads clean. Until 2026-09-25 the only thing
 * this project said about an almanac was **how old it was**, which is a proxy and behaves like one:
 * on the morning of the 25th a build shipped a Galileo almanac published on the 22nd whose worst
 * satellite sat **2 227 km** from where that same set's ephemeris put it, against **96 km** for a
 * current one. The band searched the wrong sky and 白い熊 got no fix before the walk. Everything
 * needed to say so was on the phone; nothing measured it.
 *
 * So the build measures it now, and `0d` is backed by a distance:
 *
 *     gps-yuma 0d/38 km · galileo 0d/61 km · glonass 0d
 *
 * ## What is compared, and against what
 *
 * The almanac's own coarse Kepler set, propagated with the receiver's broadcast formula
 * ([PgnssExtraFile.almanacPosition] — the same one the band will use), against **CODE's precise
 * orbit for the same satellite at the same instant**. That product is somebody else's arithmetic,
 * which is the point: a check that compares us with ourselves can only confirm our own assumptions,
 * and this project's history is that three graders were wrong before the file was.
 *
 * Measured at the START, MIDDLE and END of the forecast window and scored on the worst of the
 * three. An almanac has to survive the whole 72 hours the set claims, and its own error grows
 * across them exactly as the ephemeris's does.
 *
 * ## What is NOT covered, said out loud
 *
 * * **GLONASS** broadcasts its almanac in a different parameterisation entirely — ascending-node
 *   time and draconic period, not Keplerian elements — so [PgnssExtraFile.almanacPosition] does not
 *   apply to it and a number produced by pretending otherwise would be worse than none.
 * * **BeiDou** has its own, stricter gate already: every geostationary must sit within 5° of its
 *   published station ([PgnssExtraFile] `checkGeostationaries`), which is what caught the missing
 *   Earth-rotation term after seventeen days.
 *
 * That leaves GPS and Galileo — including the one that actually failed.
 */
object AlmanacCheck {

    /**
     * What a current almanac measures, with room to spare.
     *
     * A broadcast almanac is specified to a few kilometres at its own epoch and degrades to tens
     * over days; the good sets this project has measured come out under a hundred. 150 km is the
     * line between "ordinary" and "say something", not a specification.
     */
    const val GOOD_KM = 150.0

    /**
     * Where it stops being a warning and becomes the explanation of a failed fix.
     *
     * The measured failure was 2 227 km. A thousand is well clear of anything a healthy almanac
     * does and well under what broke the walk, so it separates the two cases without pretending to
     * know where between them the band actually gives up.
     */
    const val LOUD_KM = 1000.0

    /** The GPS week, in seconds. */
    private const val WEEK = 604800.0

    /** Galileo publishes sqrtA as an offset from this, and inclination as one from 56°. */
    private const val GALILEO_SQRT_A = 5440.588203
    private const val GALILEO_I0 = 56.0 * PI / 180.0

    /** One constellation's reading. Distances in kilometres, because that is the scale of the fault. */
    data class Reading(
        val system: String,
        val satellites: Int,
        val medianKm: Double,
        val worstKm: Double,
        val worstSat: String,
    ) {
        val loud: Boolean get() = worstKm > LOUD_KM
        val notable: Boolean get() = worstKm > GOOD_KM

        /** `galileo 61 km (worst E14 96 km, 24 sats)` — the median first, because the median is the set. */
        fun describe(): String =
            "%s %.0f km (worst %s %.0f km, %d sats)".format(system, medianKm, worstSat, worstKm, satellites)
    }

    /**
     * Measure both Keplerian almanacs against the precise orbits, over the whole window.
     *
     * [arcs] is CODE's SP3 keyed the way [Sp3.parse] keys it (`G01`, `E14`); [windowGps] are full
     * GPS seconds. A satellite the product does not cover across a sample is skipped rather than
     * guessed at — the product is the truth here, and half of it is not.
     */
    fun measure(
        gps: Map<Int, GpsAlmanacEntry>,
        galileo: Map<Int, GalileoAlmanacEntry>,
        arcs: Map<String, Sp3.Arc>,
        windowGps: LongArray,
    ): List<Reading> {
        if (windowGps.isEmpty()) return emptyList()
        val samples = sampleEpochs(windowGps)
        val out = ArrayList<Reading>(2)
        gpsReading(gps, arcs, samples)?.let(out::add)
        galileoReading(galileo, arcs, samples)?.let(out::add)
        return out
    }

    /** Start, middle and end — an almanac has to hold for every hour the set claims. */
    internal fun sampleEpochs(windowGps: LongArray): DoubleArray {
        val first = windowGps.first().toDouble()
        val last = windowGps.last().toDouble()
        return doubleArrayOf(first, (first + last) / 2.0, last)
    }

    /** YUMA is already radians, seconds-of-week and m^(1/2): nothing to convert. */
    internal fun elementsOf(a: GpsAlmanacEntry): KeplerElements = KeplerElements(
        sqrtA = a.sqrtA, e = a.e, i0 = a.i0, omega0 = a.omega0,
        omega = a.omega, m0 = a.m0, omegaDot = a.omegaDot,
    )

    /**
     * Galileo broadcasts OFFSETS in SEMICIRCLES, exactly as [PgnssExtraFile] writes them back out.
     *
     * Getting this wrong is not subtle — applying the semicircle factor twice, or forgetting the
     * 56° the inclination is measured from, throws a satellite tens of thousands of kilometres and
     * the reading would accuse a perfectly good almanac. It is the same conversion the file writer
     * performs in reverse, so the two can be held against each other.
     */
    internal fun elementsOf(a: GalileoAlmanacEntry): KeplerElements = KeplerElements(
        sqrtA = GALILEO_SQRT_A + a.dSqrtA,
        e = a.e,
        i0 = GALILEO_I0 + a.deltaI * PI,
        omega0 = a.omega0 * PI,
        omega = a.omega * PI,
        m0 = a.m0 * PI,
        omegaDot = a.omegaDot * PI,
    )

    private fun gpsReading(
        gps: Map<Int, GpsAlmanacEntry>,
        arcs: Map<String, Sp3.Arc>,
        samples: DoubleArray,
    ): Reading? {
        val per = LinkedHashMap<String, Double>()
        for ((prn, a) in gps) {
            if (a.health != 0) continue
            val arc = arcs["G%02d".format(prn)] ?: continue
            val worst = worstOver(elementsOf(a), a.week * WEEK + a.toa, a.toa, arc, samples) ?: continue
            per["G%02d".format(prn)] = worst
        }
        return reading("gps-yuma", per)
    }

    private fun galileoReading(
        galileo: Map<Int, GalileoAlmanacEntry>,
        arcs: Map<String, Sp3.Arc>,
        samples: DoubleArray,
    ): Reading? {
        val per = LinkedHashMap<String, Double>()
        for ((svid, a) in galileo) {
            if (a.health != 0) continue
            val arc = arcs["E%02d".format(svid)] ?: continue
            val worst = worstOver(elementsOf(a), a.week * WEEK + a.t0a, a.t0a, arc, samples) ?: continue
            per["E%02d".format(svid)] = worst
        }
        return reading("galileo", per)
    }

    /**
     * The worst separation over the sampled epochs, in kilometres, or null if the product cannot
     * answer for this satellite across the window.
     */
    private fun worstOver(
        el: KeplerElements,
        toaAbsGps: Double,
        toaSow: Double,
        arc: Sp3.Arc,
        samples: DoubleArray,
    ): Double? {
        if (!el.isFinite()) return null
        val truth = DoubleArray(3)
        var worst = 0.0
        for (t in samples) {
            if (!Sp3.spanned(arc.t, t)) return null
            Sp3.interpolatePosition(arc, t, truth)
            val p = PgnssExtraFile.almanacPosition(el, t - toaAbsGps, toaSow)
            val dx = p[0] - truth[0]
            val dy = p[1] - truth[1]
            val dz = p[2] - truth[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (!d.isFinite()) return null
            if (d > worst) worst = d
        }
        return worst / 1000.0
    }

    private fun reading(system: String, per: Map<String, Double>): Reading? {
        if (per.isEmpty()) return null
        val sorted = per.values.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val worst = per.maxByOrNull { it.value }!!
        return Reading(system, per.size, median, worst.value, worst.key)
    }

    /** One line for the build summary and the built-log, or blank when nothing could be measured. */
    fun summarise(readings: List<Reading>): String =
        if (readings.isEmpty()) "" else "almanac vs orbit: " + readings.joinToString(" · ") { it.describe() }

    /** The worst reading's own words, for the alert — or null when every constellation is ordinary. */
    fun complaint(readings: List<Reading>): String? {
        val bad = readings.filter { it.notable }.sortedByDescending { it.worstKm }
        if (bad.isEmpty()) return null
        return bad.joinToString("; ") {
            "%s puts %s %.0f km from where the orbit product does (median %.0f km)"
                .format(it.system, it.worstSat, it.worstKm, it.medianKm)
        }
    }
}
