package com.opentasker.core.huawei.pgnss

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The almanac, measured in kilometres instead of dated in days.
 *
 * Every figure here comes from files somebody else published — CODE's precise orbits for
 * 2026-08-30, the Navcen YUMA of the 28th and ESA's GSSC XML of the same day. That is deliberate:
 * an almanac check graded against our own ephemeris can only confirm our own conversions, and the
 * conversions are exactly what can be wrong. Applying Galileo's semicircle factor twice, or
 * forgetting the 56° its inclination is measured from, throws a satellite tens of thousands of
 * kilometres — and the reading would then accuse a perfectly good almanac of the fault.
 */
class AlmanacCheckTest {

    private fun res(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("pgnss/$name")) { name }
            .bufferedReader().readText()

    private val arcs = Sp3.parse(res("e2e-code.sp3"))
    private val t0 = arcs.values.first().t.first()
    private val yuma = Almanac.parseYuma(res("e2e-yuma.alm"), t0)
    private val gssc = Almanac.parseGssc(res("e2e-galileo.xml"))

    /** A 72-hour window inside the product's five days, offset the way a real build's is. */
    private val window = longArrayOf((t0 + 7200).toLong(), (t0 + 7200 + 72 * 3600).toLong())

    /**
     * THE ANCHOR: a healthy almanac, two days old, measures in the TENS of kilometres.
     *
     * The stale Galileo almanac that cost 白い熊 the fix on 2026-09-25 measured 2 227 km. These are
     * the other end of that scale, and the gap between them is what makes the reading worth
     * printing — if a good almanac and a ruinous one came out within a factor of two of each other,
     * the number would be decoration.
     */
    @Test
    fun `a current almanac sits tens of kilometres from the precise orbit, not thousands`() {
        val readings = AlmanacCheck.measure(yuma, gssc, arcs, window)
        assertEquals("both Keplerian constellations must be measured", 2, readings.size)
        for (r in readings) {
            assertTrue("${r.system}: nothing was measured", r.satellites > 0)
            assertTrue(
                "${r.system} came out at ${r.worstKm} km — a current almanac does not do that, " +
                    "so the conversion is wrong before the almanac is",
                r.worstKm < AlmanacCheck.GOOD_KM,
            )
        }
        val gps = readings.single { it.system == "gps-yuma" }
        val galileo = readings.single { it.system == "galileo" }
        // The fixtures carry two satellites each; the figures are recorded so a change in the
        // arithmetic shows up as a changed number rather than as a still-passing range.
        assertEquals(2, gps.satellites)
        assertEquals(2, galileo.satellites)
        assertEquals(2.0, gps.worstKm, 1.0)
        assertEquals(52.0, galileo.worstKm, 5.0)
        assertNull("nothing here is worth complaining about", AlmanacCheck.complaint(readings))
    }

    /**
     * AND THE CONVERSION IS WHAT THE ANCHOR PROTECTS.
     *
     * Galileo publishes offsets in semicircles. Drop the semicircle factor and the check would
     * report a fault that is ours; keep this test and that mistake cannot pass as an almanac
     * problem. Both wrong forms are tried, because they fail in different directions.
     */
    @Test
    fun `Galileo's semicircles and its 56 degree reference both matter, by thousands of km`() {
        val a = gssc.getValue(3)
        val right = AlmanacCheck.elementsOf(a)
        val noSemicircles = right.copy(
            omega0 = a.omega0, omega = a.omega, m0 = a.m0, omegaDot = a.omegaDot,
        )
        val noReference = right.copy(i0 = a.deltaI * PI)
        val arc = arcs.getValue("E03")
        val t = window.first().toDouble()
        val toaAbs = a.week * 604800.0 + a.t0a
        val truth = DoubleArray(3)
        Sp3.interpolatePosition(arc, t, truth)

        fun kmFrom(el: KeplerElements): Double {
            val p = PgnssExtraFile.almanacPosition(el, t - toaAbs, a.t0a)
            var s = 0.0
            for (k in 0..2) s += (p[k] - truth[k]) * (p[k] - truth[k])
            return Math.sqrt(s) / 1000.0
        }

        assertTrue("the correct conversion must be the close one", kmFrom(right) < AlmanacCheck.GOOD_KM)
        assertTrue(
            "semicircles left unconverted must be unmistakable, not merely worse",
            kmFrom(noSemicircles) > 1000.0,
        )
        assertTrue(
            "an inclination measured from zero instead of 56 degrees likewise",
            kmFrom(noReference) > 1000.0,
        )
    }

    /** The thresholds have to sit between what was measured good and what was measured ruinous. */
    @Test
    fun `the bounds lie between a healthy almanac and the one that cost the fix`() {
        assertEquals(150.0, AlmanacCheck.GOOD_KM, 0.0)
        assertEquals(1000.0, AlmanacCheck.LOUD_KM, 0.0)
        // 52 km was measured above from published files; 2 227 km was measured on 2026-09-25.
        assertTrue("a healthy almanac must pass quietly", 52.0 < AlmanacCheck.GOOD_KM)
        assertTrue("the failure must be LOUD, not merely notable", 2227.0 > AlmanacCheck.LOUD_KM)
        assertTrue(AlmanacCheck.GOOD_KM < AlmanacCheck.LOUD_KM)
    }

    /** Both flags follow from the distance, and the loud one implies the notable one. */
    @Test
    fun `a reading says which of the two lines it has crossed`() {
        fun at(km: Double) = AlmanacCheck.Reading("galileo", 24, km / 2, km, "E14")
        assertTrue(!at(40.0).notable && !at(40.0).loud)
        assertTrue(at(300.0).notable && !at(300.0).loud)
        assertTrue(at(2227.0).notable && at(2227.0).loud)
        assertNotNull(AlmanacCheck.complaint(listOf(at(300.0))))
        assertTrue("2227 km" in AlmanacCheck.complaint(listOf(at(2227.0)))!!)
    }

    /**
     * A SATELLITE THE PRODUCT CANNOT ANSWER FOR IS SKIPPED, NEVER GUESSED AT.
     *
     * The almanac carries thirty-odd satellites and the fixture's orbit product carries two. If an
     * unmatched satellite scored zero the reading would improve as the evidence thinned, which is
     * the wrong direction for every instrument in this project.
     */
    @Test
    fun `only satellites the orbit product covers are counted`() {
        val readings = AlmanacCheck.measure(yuma, gssc, arcs, window)
        assertTrue("the almanac is far bigger than the product", yuma.size > 20 && gssc.size > 20)
        assertEquals(2, readings.single { it.system == "gps-yuma" }.satellites)
        assertEquals(2, readings.single { it.system == "galileo" }.satellites)
        // …and with no product at all there is no reading, rather than a reassuring zero.
        assertTrue(AlmanacCheck.measure(yuma, gssc, emptyMap(), window).isEmpty())
        assertEquals("", AlmanacCheck.summarise(emptyList()))
    }

    /** The window is sampled at both ends and the middle: an almanac must hold for all 72 hours. */
    @Test
    fun `the whole window is sampled, not just its start`() {
        val samples = AlmanacCheck.sampleEpochs(longArrayOf(1000L, 2000L))
        assertEquals(3, samples.size)
        assertEquals(1000.0, samples[0], 0.0)
        assertEquals(1500.0, samples[1], 0.0)
        assertEquals(2000.0, samples[2], 0.0)
    }

    /** It has to reach the panel and the built-log, or it is a number in a log nobody reads. */
    @Test
    fun `the distance is published beside the age, and written into the built-log`() {
        val action = com.opentasker.ProductionSources.read(
            "com/opentasker/core/actions/HuaweiPgnssAction.kt",
        )
        assertTrue(
            "the panel line pairs the age with the distance",
            "if (km == null) \"\$k \${v}d\" else \"\$k \${v}d/%.0f km\".format(km)" in action,
        )
        assertTrue(
            "and so does the one line kept per build",
            "append(\" · almanac \")" in action,
        )
        assertTrue(
            "a CURRENT almanac that places badly must still raise something",
            "AlmanacCheck.complaint(result.almanacReadings)" in action,
        )
        val build = com.opentasker.ProductionSources.read(
            "com/opentasker/core/huawei/pgnss/PredictedSet.kt",
        )
        assertTrue(
            "measured where both halves are in hand",
            "AlmanacCheck.measure(yuma, gssc, plan.sats, plan.stamps)" in build,
        )
    }
}
