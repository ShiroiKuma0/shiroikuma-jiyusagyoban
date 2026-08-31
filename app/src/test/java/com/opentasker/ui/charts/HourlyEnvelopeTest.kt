package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capsules and the pooled gap threshold.
 *
 * The numbers here are 白い熊's own: the 2026-08-04 hour-by-hour heart rate that Hume's `H` tab drew,
 * and the interval mixture that made the pooled series manufacture 67 gaps out of 70.
 */
class HourlyEnvelopeTest {

    private val h = HourlyEnvelope.HOUR_MS

    private fun at(hour: Int, minute: Int, v: Double) =
        ChartPoint(hour * h + minute * 60_000L, v)

    @Test
    fun `a bucket holds real extremes, never a mean`() {
        val points = listOf(at(0, 5, 61.0), at(0, 20, 85.0), at(0, 50, 70.0))
        val b = HourlyEnvelope.bucket(points).single()
        assertEquals(61.0, b.lo, 1e-9)
        assertEquals(85.0, b.hi, 1e-9)
        assertEquals(3, b.n)
        // 72.0 is the mean of those three and must appear nowhere.
        assertTrue(b.lo != 72.0 && b.hi != 72.0)
    }

    @Test
    fun `buckets are anchored to absolute time, not to array index`() {
        // Shifting the window by one sample must not re-partition anything: the same reading has to
        // land in the same hour whichever neighbours it arrives with, or the capsules crawl while
        // panning — the same reason LTTB anchors its buckets.
        val all = listOf(at(0, 10, 60.0), at(0, 50, 70.0), at(1, 10, 80.0), at(1, 50, 90.0))
        val full = HourlyEnvelope.bucket(all)
        val shifted = HourlyEnvelope.bucket(all.drop(1))
        assertEquals(h, full[1].startMs)
        assertEquals(h, shifted.first { it.startMs == h }.startMs)
        assertEquals(full[1].lo, shifted.first { it.startMs == h }.lo, 1e-9)
        assertEquals(full[1].hi, shifted.first { it.startMs == h }.hi, 1e-9)
    }

    @Test
    fun `an hour with no reading is omitted, not drawn at zero`() {
        val points = listOf(at(0, 30, 60.0), at(2, 30, 80.0))   // hour 1 is empty
        val buckets = HourlyEnvelope.bucket(points)
        assertEquals(2, buckets.size)
        assertEquals(listOf(0L, 2 * h), buckets.map { it.startMs })
    }

    @Test
    fun `a single reading gives a zero-span capsule rather than being dropped`() {
        val b = HourlyEnvelope.bucket(listOf(at(0, 30, 66.0))).single()
        assertEquals(0.0, b.span, 1e-9)
        assertEquals(1, b.n)
    }

    @Test
    fun `Hume's own 2026-08-04 capsules reproduce from our samples`() {
        // Their H tab reported the 12 PM hour as roughly 62-84 and the whole 08:00-15:00 window as
        // 58-91. These are the readings we hold for the 12 PM hour that day.
        val noon = listOf(
            at(12, 0, 76.0), at(12, 3, 60.0), at(12, 5, 61.0),
            at(12, 7, 60.0), at(12, 9, 61.0), at(12, 10, 85.0),
        )
        val b = HourlyEnvelope.bucket(noon).single()
        assertEquals(60.0, b.lo, 1e-9)
        assertEquals(85.0, b.hi, 1e-9)
    }

    @Test
    fun `dumbbells pair two series on one axis, hour by hour`() {
        val sys = listOf(at(0, 10, 121.0), at(0, 40, 126.0), at(1, 10, 118.0))
        val dia = listOf(at(0, 10, 70.0), at(0, 40, 76.0))
        val d = HourlyEnvelope.dumbbells(sys, dia)
        assertEquals(2, d.size)
        assertEquals(121.0, d[0].upper!!.lo, 1e-9)
        assertEquals(126.0, d[0].upper!!.hi, 1e-9)
        assertEquals(70.0, d[0].lower!!.lo, 1e-9)
        // The second hour has systolic only; the dumbbell keeps one end rather than being dropped.
        assertEquals(null, d[1].lower)
        assertEquals(118.0, d[1].upper!!.lo, 1e-9)
    }

    // --- the pooled gap threshold ------------------------------------------------------------

    private val hrSpec = MetricSpecs.HEART_RATE

    /** A pooled heart-rate series: a 120 s periodic cadence with a 600 s reading interleaved. */
    private fun pooledSeries(): List<ChartPoint> {
        val out = ArrayList<ChartPoint>()
        var t = 0L
        repeat(120) {
            out += ChartPoint(t, 65.0)
            t += if (it % 5 == 4) 600_000L else 120_000L
        }
        return out
    }

    @Test
    fun `the median threshold shreds a mixed-cadence series — this is the trap`() {
        val pooled = pooledSeries()
        val median = ChartPipeline.gapThresholdMs(pooled, hrSpec, multiplier = 3, mixedCadence = false)
        // The 600 s spacings are perfectly ordinary, yet they fall OUTSIDE a median-derived threshold.
        assertTrue(
            "expected the median threshold to sit below the 600 s mode, got ${median}ms",
            median < 600_000L,
        )
    }

    @Test
    fun `a high percentile survives the mixture and stops inventing gaps`() {
        val pooled = pooledSeries()
        val p90 = ChartPipeline.gapThresholdMs(pooled, hrSpec, multiplier = 3, mixedCadence = true)
        assertTrue(
            "the pooled threshold must clear the 600 s mode, got ${p90}ms",
            p90 >= 600_000L,
        )
        val gaps = pooled.zipWithNext().count { (a, b) -> b.tMs - a.tMs > p90 }
        assertEquals("a regular mixed-cadence series has no gaps at all", 0, gaps)
    }

    @Test
    fun `a real gap is still a gap once the threshold is fixed`() {
        // Two normal blocks with a genuine hour-long hole between them, like 2026-08-04 12:10→13:10.
        // Widening the threshold must not blind it to a hole the band really left.
        val first = pooledSeries()
        val resumeAt = first.last().tMs + 3_600_000L
        val second = pooledSeries().map { ChartPoint(it.tMs + resumeAt, it.value) }
        val series = first + second
        val thr = ChartPipeline.gapThresholdMs(series, hrSpec, multiplier = 3, mixedCadence = true)
        val gaps = series.zipWithNext().filter { (a, b) -> b.tMs - a.tMs > thr }
        assertEquals("exactly the one real hole, and nothing else", 1, gaps.size)
        assertEquals(3_600_000L, gaps.single().let { (a, b) -> b.tMs - a.tMs })
    }

    @Test
    fun `a single-cadence series is unaffected by the mixed-cadence flag`() {
        val even = (0 until 60).map { ChartPoint(it * 240_000L, 65.0) }
        assertEquals(
            ChartPipeline.gapThresholdMs(even, hrSpec, 3, mixedCadence = false),
            ChartPipeline.gapThresholdMs(even, hrSpec, 3, mixedCadence = true),
        )
    }
}
