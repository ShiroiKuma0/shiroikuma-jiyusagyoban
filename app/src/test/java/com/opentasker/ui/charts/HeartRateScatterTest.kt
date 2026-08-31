package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Heart rate's two populations, and the mark that keeps them apart.
 *
 * It was an hourly capsule, which made the chart read as though the band measures once an hour; it
 * measures 12–30 times. It is now a **curve through the periodic series with the spot readings as
 * hollow dots on top** (白い熊, 2026-08-09), because the two do not measure the same thing: asleep
 * and still they agree to 1 bpm, but with a hundred steps nearby the spot reading runs 22 bpm higher
 * and the periodic series does not move at all.
 *
 * These tests pin what makes that trustworthy: nothing is dropped on the way to the plot, the curve
 * is fitted through one population rather than the mixture, and the filter that would flag the
 * mixture stays off.
 */
class HeartRateScatterTest {

    private val spec = MetricSpecs.HEART_RATE

    /**
     * A day of heart rate as the band really delivers it.
     *
     * The fill rate matters and is not a detail: the periodic series is nominally every 120 s but
     * fills only 37–48 % of its slots, so the POOLED series really does carry ~300 s intervals, and
     * its 90th-percentile gap threshold really does sit above the ten-minute spot cadence. A fixture
     * with every slot filled would put the threshold at 360 s and call every spot-only stretch a
     * gap — which is the bug `mixedCadence` exists to prevent, reproduced in the test data instead
     * of in the code.
     *
     * So: two periodic samples per ten-minute block, and one spot reading, running higher as it does
     * whenever 白い熊 is moving. Eighteen readings an hour, inside the real 12–30.
     */
    private fun pooledDay(): Pair<List<ChartPoint>, Set<Long>> {
        val start = 1_785_000_000_000L
        val points = mutableListOf<ChartPoint>()
        val coincident = mutableSetOf<Long>()
        for (block in 0 until 60) {                       // ten hours of ten-minute blocks
            val blockStart = start + block * 600_000L
            coincident += blockStart
            points += ChartPoint(blockStart, 72.0 + (block % 3))
            points += ChartPoint(blockStart + 180_000L, 66.0)
            points += ChartPoint(blockStart + 300_000L, 67.0)
        }
        return points to coincident
    }

    private fun chunkOf(points: List<ChartPoint>) = ChartPipeline.qualifyAndSegment(
        points,
        spec.copy(hampelHalfWindow = 0),
        mixedCadence = spec.mixedCadence,
    )

    @Test
    fun `the mark is a line with spots, and the headline is still a range`() {
        assertEquals(RenderKind.LINE_WITH_SPOTS, spec.render)
        assertTrue("53–105 bpm must survive the change of mark", spec.headlineIsRange)
        assertTrue("the two populations must stay told apart", spec.splitPopulations)
    }

    /** Nothing is aggregated away: every reading is still in the chunk the plot draws from. */
    @Test
    fun `every reading reaches the plot`() {
        val (points, _) = pooledDay()
        val chunk = chunkOf(points)
        val drawn = chunk.segments.flatMap { it.points }
        assertEquals("180 readings in, 180 out", points.size, drawn.size)
        assertEquals(points.map { it.tMs }, drawn.map { it.tMs })
        assertEquals(0, chunk.rejectedPoints.size)
    }

    /**
     * The split itself: the curve is fitted through the SPOT readings alone.
     *
     * That way round on purpose — the curve carries the series that follows the heart during
     * activity, and the periodic series, which does not, is relegated to dots. Pooling them into one
     * line would draw a sawtooth that is an artefact of the interleaving, not a thing the heart did.
     */
    @Test
    fun `the curve carries the spot readings only, and the dots carry the periodic series`() {
        val (points, coincident) = pooledDay()
        val chunk = chunkOf(points)
        val curve = ChartQualify.curveSeries(chunk, coincident, spec)
        val onCurve = curve.segments.flatMap { it.points }

        assertEquals(60, onCurve.size)
        assertTrue("every point on the curve is a spot reading", onCurve.all { it.tMs in coincident })
        assertEquals(
            "and every reading is still accounted for between the two marks",
            points.size,
            onCurve.size + chunk.segments.flatMap { it.points }.count { it.tMs !in coincident },
        )
    }

    /**
     * The curve breaks where the SPOT series stops, not where the pooled series does.
     *
     * The pooled series is denser, so its threshold is tighter than the spot cadence deserves; using
     * it would let the curve run straight through an hour in which only periodic samples exist,
     * drawing a line across a stretch the spot series never measured.
     */
    @Test
    fun `the curve breaks over a hole that only periodic readings span`() {
        val (points, coincident) = pooledDay()
        val start = points.first().tMs
        // Drop the spot readings of an hour in the middle; keep its periodic samples.
        val holeFrom = start + 3 * 3_600_000L
        val holeTo = holeFrom + 3_600_000L
        val holed = points.filter { it.tMs !in holeFrom..holeTo || it.tMs !in coincident }

        val chunk = chunkOf(holed)
        val curve = ChartQualify.curveSeries(chunk, coincident, spec)

        assertEquals("the pooled series has no gap — the periodic samples cover it", 0, chunk.gaps.size)
        assertTrue("but the curve must not be drawn across it", curve.segments.size > 1)
    }

    /**
     * Why Hampel is off. It assumes ONE population and the chunk is pooled, so pointed at this
     * series it reads the interleaving as a sawtooth of outliers and spends its whole budget on
     * readings that are perfectly real — 102 of them on 白い熊's own data, against 2 with it off.
     */
    @Test
    fun `Hampel would condemn the interleaving itself, which is why it is disabled`() {
        // A resting stretch, where the effect bites hardest: the periodic series is pinned, so the
        // MAD sits on its 2.0 floor and the threshold is 3.5 × 2.0 = 7.0 — which the measured
        // +7.46 bpm separation clears on its own.
        val start = 1_785_000_000_000L
        val points = mutableListOf<ChartPoint>()
        val coincident = mutableSetOf<Long>()
        for (block in 0 until 40) {
            val blockStart = start + block * 600_000L
            coincident += blockStart
            points += ChartPoint(blockStart, 65.0 + 7.46)
            for (slot in 1..4) points += ChartPoint(blockStart + slot * 120_000L, 65.0)
        }

        val withFilter = ChartPipeline.qualifyAndSegment(points, spec, mixedCadence = spec.mixedCadence)
        val without = chunkOf(points)

        assertTrue(
            "the filter must be shown to reject real readings here — that is the reason it is off",
            withFilter.rejectedPoints.isNotEmpty(),
        )
        assertTrue(
            "and what it rejects is the second POPULATION, not any spike",
            withFilter.rejectedPoints.all { it.tMs in coincident },
        )
        assertEquals("with it off, nothing real is thrown away", 0, without.rejectedPoints.size)
    }

    /** A genuine sensor spike is still caught: the slew gate stays on, and it is not the filter. */
    @Test
    fun `the slew gate still catches a dropout spike, and it never reaches either mark`() {
        val (points, coincident) = pooledDay()
        val spiked = points.toMutableList()
        // Index 101 is a periodic sample 120 s after its neighbour, so the slew limit is the
        // undivided 40 bpm — a +55 jump is exactly the dropout spike the gate exists for.
        spiked[101] = ChartPoint(spiked[101].tMs, spiked[101].value + 55.0)
        val chunk = chunkOf(spiked)

        assertEquals(1, chunk.rejectedPoints.size)
        assertEquals(spiked[101].tMs, chunk.rejectedPoints.first().tMs)
        // Flagged, never deleted — it is reported at its REAL value for the ✕ mark.
        assertEquals(spiked[101].value, chunk.rejectedPoints.first().value, 0.001)

        // The spiked sample is a periodic one, so it belongs to the dots — and the dots are drawn
        // from the same retained points, so it is excluded there too.
        val retained = chunk.segments.flatMap { it.points }
        assertTrue(
            "a rejected reading must not reappear as a dot",
            retained.none { it.tMs == spiked[101].tMs },
        )
    }

    /**
     * The pooled chunk must keep its high-percentile gap threshold.
     *
     * Its median interval lands between the two cadences, which manufactured 67 spurious ten-minute
     * gaps out of 70 and tinted most of the chart. Changing the mark must not have changed that.
     */
    @Test
    fun `the pooled chunk does not manufacture gaps between its two cadences`() {
        val (points, _) = pooledDay()
        assertEquals("an unbroken ten hours has no gaps in it", 0, chunkOf(points).gaps.size)
    }
}
