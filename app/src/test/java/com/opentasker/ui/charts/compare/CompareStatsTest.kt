package com.opentasker.ui.charts.compare

import com.opentasker.ui.charts.compare.CompareData.Cell
import com.opentasker.ui.charts.compare.CompareData.Grain
import com.opentasker.ui.charts.compare.CompareData.Join
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What may be said about two disagreeing bands, and what may not. */
class CompareStatsTest {

    private val t0 = 1_787_400_000_000L

    private fun join(
        deltas: List<Pair<Double, Double>>,
        grain: Grain = Grain.MINUTE,
        humeOnly: Int = 0,
        huaweiOnly: Int = 0,
    ): Join {
        val cells = deltas.mapIndexed { i, (a, b) -> Cell(t0 + i * 60_000L, a, b) } +
            (0 until humeOnly).map { Cell(t0 + 900_000L + it * 60_000L, null, 60.0) } +
            (0 until huaweiOnly).map { Cell(t0 + 1_800_000L + it * 60_000L, 60.0, null) }
        return Join(
            grain = grain,
            cells = cells,
            huaweiSamples = deltas.size + huaweiOnly,
            humeSamples = deltas.size + humeOnly,
            both = deltas.size,
            huaweiOnly = huaweiOnly,
            humeOnly = humeOnly,
        )
    }

    @Test
    fun `one dropout does not move the median, which is the whole reason it is a median`() {
        // Four pairs agreeing within a beat, and one where a band dropped out entirely. A mean would
        // be dragged to about +12; the median must not notice.
        val d = CompareStats.delta(
            join(listOf(70.0 to 70.0, 71.0 to 70.0, 72.0 to 71.0, 70.0 to 71.0, 130.0 to 70.0)),
            threshold = 5.0,
        )
        assertEquals(5, d.pairs)
        assertEquals(1.0, d.median!!, 1e-9)
        assertTrue("a mean would be far higher than the median here", d.median!! < 5.0)
    }

    @Test
    fun `the agreement figure never travels without its threshold`() {
        val d = CompareStats.delta(join(listOf(70.0 to 70.0, 80.0 to 70.0)), threshold = 5.0)
        assertEquals(1, d.within)
        assertTrue("the threshold must be in the same string", d.agreement.contains("±5"))
        assertTrue(d.agreement.contains("1/2"))
    }

    @Test
    fun `no pairs yields no median rather than zero`() {
        val d = CompareStats.delta(join(emptyList(), humeOnly = 3), threshold = 5.0)
        assertEquals(0, d.pairs)
        // Zero would read as "the bands agreed perfectly", which is the opposite of the truth.
        assertNull(d.median)
        assertEquals("—", d.agreement)
    }

    @Test
    fun `the quartiles bracket the median and describe the spread`() {
        val d = CompareStats.delta(
            join((0..9).map { (70.0 + it) to 70.0 }),
            threshold = 100.0,
        )
        assertTrue(d.q1!! <= d.median!!)
        assertTrue(d.median!! <= d.q3!!)
        assertTrue("an asymmetric spread must not be summarised as one number", d.q3!! > d.q1!!)
    }

    @Test
    fun `the footer reconciles, and keeps its lines separate`() {
        val j = join(listOf(70.0 to 71.0, 72.0 to 71.0), humeOnly = 4, huaweiOnly = 7)
        val lines = CompareStats.footer(
            j, CompareStats.delta(j, 5.0), unit = "bpm", scale = "40–180 bpm", offsetSeconds = null,
        )
        assertEquals("four lines, never merged", 4, lines.size)
        assertTrue(lines[0].startsWith("Band 11"))
        assertTrue(lines[1].startsWith("Hume"))
        // Line three counts PAIRS and must reconcile with the two above it.
        assertTrue(lines[2].contains("2 both"))
        assertTrue(lines[2].contains("7 Band 11 only"))
        assertTrue(lines[2].contains("4 Hume only"))
        assertEquals(j.humeSamples, j.both + j.humeOnly)
        assertEquals(j.huaweiSamples, j.both + j.huaweiOnly)
    }

    @Test
    fun `the shared scale is stated, because a reader cannot check it by eye`() {
        val j = join(listOf(70.0 to 71.0))
        val lines = CompareStats.footer(j, CompareStats.delta(j, 5.0), "bpm", "40–180 bpm", null)
        assertTrue(lines[3].contains("40–180 bpm"))
        assertTrue(lines[3].contains("same scale"))
    }

    @Test
    fun `an unmeasured offset says so rather than printing zero as a fact`() {
        val j = join(listOf(70.0 to 71.0))
        val lines = CompareStats.footer(j, CompareStats.delta(j, 5.0), "bpm", "40–180", null)
        assertTrue(lines[3].contains("未測定"))
    }

    @Test
    fun `a measured offset is reported as measured, never as applied`() {
        val j = join(listOf(70.0 to 71.0))
        val lines = CompareStats.footer(j, CompareStats.delta(j, 5.0), "bpm", "40–180", 120L)
        assertTrue(lines[3].contains("120 s"))
        // The distinction the whole screen rests on: nothing was shifted to make the bands agree.
        assertTrue(lines[3].contains("not applied"))
    }

    @Test
    fun `no clock offset is claimed from minute-grain data`() {
        // Measuring skew on paired instants would be circular: the pairing already used a tolerance.
        assertNull(CompareStats.clockOffsetSeconds(join(listOf(70.0 to 71.0))))
    }

    @Test
    fun `no clock offset is claimed from too few moving bins`() {
        val j = join(listOf(10.0 to 12.0, 0.0 to 0.0), grain = Grain.TEN_MINUTES)
        assertNull("an offset from two coincidences is a coincidence", CompareStats.clockOffsetSeconds(j))
    }
}
