package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.math.sin

class LttbTest {

    private fun wave(n: Int, stepMs: Long = 120_000L): List<ChartPoint> =
        (0 until n).map { ChartPoint(it * stepMs, 60.0 + 20.0 * sin(it / 7.0)) }

    @Test
    fun `every surviving point is a real sample — nothing is averaged into existence`() {
        val input = wave(2000)
        val real = input.toHashSet()
        val out = Lttb.decimate(input, target = 200, spanMs = 2000L * 120_000L)
        assertTrue("decimation must not invent points", out.all { it in real })
        assertTrue("it must actually reduce the series", out.size < input.size)
    }

    @Test
    fun `the global minimum and maximum survive`() {
        val input = wave(3000).toMutableList()
        input[1234] = ChartPoint(input[1234].tMs, 199.0)
        input[2345] = ChartPoint(input[2345].tMs, 26.0)
        val out = Lttb.decimate(input, target = 300, spanMs = 3000L * 120_000L)
        assertTrue("the peak must survive — LTTB preserves the envelope", out.any { it.value == 199.0 })
        assertTrue("the trough must survive", out.any { it.value == 26.0 })
    }

    @Test
    fun `it is the identity when the series already fits`() {
        val input = wave(100)
        assertSame(input, Lttb.decimate(input, target = 200, spanMs = 100L * 120_000L))
        assertSame(input, Lttb.decimate(input, target = 100, spanMs = 100L * 120_000L))
    }

    @Test
    fun `the first and last samples are always kept`() {
        val input = wave(5000)
        val out = Lttb.decimate(input, target = 128, spanMs = 5000L * 120_000L)
        assertEquals(input.first(), out.first())
        assertEquals(input.last(), out.last())
    }

    /**
     * Absolute-time anchoring. With index-partitioned buckets, dropping one sample off the front
     * re-partitions every bucket and the whole selection shifts, so points visibly crawl while
     * panning. Anchored to absolute time, the interior selection is stable.
     */
    @Test
    fun `the selection is stable when the window shifts by one sample`() {
        val input = wave(2000)
        val span = 2000L * 120_000L
        val a = Lttb.decimate(input, target = 200, spanMs = span).map { it.tMs }.toSet()
        val b = Lttb.decimate(input.drop(1), target = 200, spanMs = span).map { it.tMs }.toSet()
        val shared = (a intersect b).size
        val overlap = shared.toDouble() / minOf(a.size, b.size)
        assertTrue("selection crawled: only ${(overlap * 100).toInt()}% shared", overlap > 0.9)
    }

    @Test
    fun `the target is one point per two pixels, clamped`() {
        assertEquals(540, Lttb.targetFor(1080f))
        assertEquals(64, Lttb.targetFor(10f))
        assertEquals(2048, Lttb.targetFor(99_999f))
    }
}

class ChartSegmentsTest {

    private fun qualified(
        values: List<Double>,
        rejected: List<Boolean> = List(values.size) { false },
        stepMs: Long = 120_000L,
    ) = QualifiedSeries(
        points = values.mapIndexed { i, v -> ChartPoint(i * stepMs, v) },
        rejected = rejected,
        noReading = 0,
        outOfRange = 0,
    )

    @Test
    fun `a continuous series is one segment with no gaps`() {
        val (segments, gaps) = ChartSegments.split(qualified(List(20) { 60.0 }), gapThresholdMs = 360_000L)
        assertEquals(1, segments.size)
        assertEquals(0, gaps.size)
        assertEquals(20, segments[0].points.size)
    }

    @Test
    fun `silence longer than the threshold breaks the path and reports the gap`() {
        val points = listOf(
            ChartPoint(0L, 60.0),
            ChartPoint(120_000L, 61.0),
            ChartPoint(3_600_000L, 62.0),
            ChartPoint(3_720_000L, 63.0),
        )
        val (segments, gaps) = ChartSegments.split(
            QualifiedSeries(points, List(4) { false }, 0, 0),
            gapThresholdMs = 360_000L,
        )
        assertEquals(2, segments.size)
        assertEquals(1, gaps.size)
        assertEquals(120_000L..3_600_000L, gaps[0])
    }

    @Test
    fun `one rejected sample is bridged — noise worth interpolating across`() {
        val rejected = List(10) { it == 4 }
        val (segments, gaps) = ChartSegments.split(
            qualified(List(10) { 60.0 }, rejected),
            gapThresholdMs = 360_000L,
        )
        assertEquals("a lone rejection does not break the line", 1, segments.size)
        assertEquals(9, segments[0].points.size)
        assertEquals(0, gaps.size)
    }

    @Test
    fun `three consecutive rejections become a gap — the sensor was wrong, nothing to bridge`() {
        val rejected = List(12) { it in 4..6 }
        val (segments, gaps) = ChartSegments.split(
            qualified(List(12) { 60.0 }, rejected),
            gapThresholdMs = 360_000L,
        )
        assertEquals(2, segments.size)
        assertEquals(1, gaps.size)
    }
}

/**
 * The gap threshold is measured from the data, not taken from the nominal cadence.
 *
 * The periodic heart-rate series is documented as 120 s but its real median interval on 白い熊's band
 * is 240 s — it skips slots. Believing the nominal figure declared 231 of 848 intervals to be gaps
 * and shredded the chart into 235 fragments.
 */
class ChartGapThresholdTest {

    private val hr = MetricSpecs.HEART_RATE

    private fun at(vararg offsetsSec: Long): List<ChartPoint> =
        offsetsSec.map { ChartPoint(it * 1000L, 60.0) }

    @Test
    fun `a series that really samples every 240 s gets a 720 s threshold`() {
        val points = (0 until 40).map { ChartPoint(it * 240_000L, 60.0) }
        assertEquals(720_000L, ChartPipeline.gapThresholdMs(points, hr, multiplier = 3))
    }

    @Test
    fun `a series faster than nominal never tightens below the documented cadence`() {
        val points = (0 until 40).map { ChartPoint(it * 20_000L, 60.0) }
        assertEquals(
            "a burst must not make the threshold stricter than the metric is documented to be",
            360_000L,
            ChartPipeline.gapThresholdMs(points, hr, multiplier = 3),
        )
    }

    @Test
    fun `too few samples to measure falls back to the nominal cadence`() {
        assertEquals(360_000L, ChartPipeline.gapThresholdMs(at(0, 240, 480), hr, multiplier = 3))
        assertEquals(360_000L, ChartPipeline.gapThresholdMs(emptyList(), hr, multiplier = 3))
    }

    @Test
    fun `the real cadence stops the heart-rate line being shredded`() {
        // The observed shape: mostly 120 s and 240 s steps, with regular 600 s ones mixed in.
        var t = 0L
        val points = mutableListOf<ChartPoint>()
        repeat(60) { i ->
            points += ChartPoint(t, 60.0)
            t += when (i % 5) { 0 -> 600_000L; 1, 2 -> 240_000L; else -> 120_000L }
        }
        val chunk = ChartPipeline.qualifyAndSegment(points, hr)
        assertTrue("the 600 s steps must not each count as a gap: ${chunk.gaps.size}", chunk.gaps.size <= 1)
    }
}

class ChartTicksTest {

    private val prague: ZoneId = ZoneId.of("Europe/Prague")

    @Test
    fun `the ladder adapts to the span, exactly as asked for`() {
        assertEquals(TickScale.MINUTE, ChartTicks.scaleFor(20 * 60_000L))
        assertEquals(TickScale.HOUR, ChartTicks.scaleFor(24 * 3_600_000L))
        assertEquals(TickScale.DAY, ChartTicks.scaleFor(5 * 24 * 3_600_000L))
        assertEquals(TickScale.WEEK, ChartTicks.scaleFor(30L * 24 * 3_600_000L))
        assertEquals(TickScale.MONTH, ChartTicks.scaleFor(200L * 24 * 3_600_000L))
    }

    @Test
    fun `hour ticks land on the hour and midnight is major`() {
        // 2026-08-03 00:00 Prague .. +24 h
        val start = java.time.ZonedDateTime.of(2026, 8, 3, 0, 0, 0, 0, prague).toInstant().toEpochMilli()
        val ticks = ChartTicks.forSpan(start, start + 24 * 3_600_000L, prague)
        assertTrue(ticks.isNotEmpty())
        for (t in ticks) {
            val z = java.time.Instant.ofEpochMilli(t.tMs).atZone(prague)
            assertEquals("every hour tick must sit exactly on the hour", 0, z.minute)
        }
        assertTrue("midnight must be a major boundary", ticks.first { it.major }.let {
            java.time.Instant.ofEpochMilli(it.tMs).atZone(prague).hour == 0
        })
    }

    /**
     * Stepping by fixed millis drifts by an hour across a DST transition and the labels stop landing
     * on the hour. 白い熊 is in Europe/Prague, so this happens twice a year.
     */
    @Test
    fun `ticks stay on the hour across a DST transition`() {
        // 2026-10-25 is the European autumn fall-back; 03:00 CEST becomes 02:00 CET.
        val start = java.time.ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, prague).toInstant().toEpochMilli()
        val ticks = ChartTicks.forSpan(start, start + 30 * 3_600_000L, prague)
        for (t in ticks) {
            val z = java.time.Instant.ofEpochMilli(t.tMs).atZone(prague)
            assertEquals("DST drift: tick at ${z}", 0, z.minute)
        }
    }

    @Test
    fun `an empty or inverted span produces nothing rather than looping`() {
        assertTrue(ChartTicks.forSpan(1000L, 1000L, prague).isEmpty())
        assertTrue(ChartTicks.forSpan(2000L, 1000L, prague).isEmpty())
    }
}
