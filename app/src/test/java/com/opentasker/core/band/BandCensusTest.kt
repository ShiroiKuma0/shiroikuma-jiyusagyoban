package com.opentasker.core.band

import com.opentasker.core.storage.BandSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The census is the instrument that measures the band's ring buffer, so its arithmetic is the part
 * most worth pinning down — and it is pure, so it can be.
 */
class BandCensusTest {

    private fun stat(expected: Int, inserted: Int, records: Int = inserted, error: String? = null) =
        BandStreamStat(
            records = records,
            inserted = inserted,
            expectedRecords = expected,
            lostRecords = BandCensus.lostRecords(expected, inserted),
            error = error,
        )

    @Test
    fun `expected records follows the measured cadence`() {
        assertEquals(30, BandCensus.expectedRecords("hr", 3600))      // 120 s
        assertEquals(6, BandCensus.expectedRecords("spo2", 3600))     // 600 s
        assertEquals(2, BandCensus.expectedRecords("temp", 3600))     // 1800 s
    }

    @Test
    fun `a stream with no cadence expects nothing, rather than inventing loss`() {
        // daily and sleep are event-shaped, not sampled
        assertEquals(0, BandCensus.expectedRecords("daily", 86_400))
        assertEquals(0, BandCensus.expectedRecords("sleep", 86_400))
        assertEquals(0, BandCensus.lostRecords(0, 0))
    }

    @Test
    fun `a gap with no loss is a LOWER bound, a gap with loss is an UPPER bound`() {
        val syncs = listOf(
            BandSyncSummary(1, gapHours = 6.0, stats = mapOf("hr" to stat(expected = 180, inserted = 180))),
            BandSyncSummary(2, gapHours = 30.0, stats = mapOf("hr" to stat(expected = 900, inserted = 600))),
            BandSyncSummary(3, gapHours = 12.0, stats = mapOf("hr" to stat(expected = 360, inserted = 360))),
        )
        val hr = BandCensus.summarize(syncs).single { it.stream == "hr" }
        assertEquals(12.0, hr.lowerBoundHours!!, 0.001)   // the largest clean gap
        assertEquals(30.0, hr.upperBoundHours!!, 0.001)   // the smallest lossy gap
        assertEquals("bounded", hr.confidence)
    }

    @Test
    fun `a stream that errored says nothing about capacity`() {
        val syncs = listOf(
            BandSyncSummary(1, gapHours = 40.0, stats = mapOf("hr" to stat(1200, 0, records = 0, error = "timeout"))),
        )
        val hr = BandCensus.summarize(syncs).single()
        assertNull("a timed-out stream must not become a lower bound", hr.lowerBoundHours)
        assertNull(hr.upperBoundHours)
        assertEquals("unknown", hr.confidence)
    }

    @Test
    fun `one clean sync alone is only a lower bound`() {
        val syncs = listOf(
            BandSyncSummary(1, gapHours = 4.0, stats = mapOf("hrv" to stat(expected = 120, inserted = 120))),
        )
        assertEquals("at least", BandCensus.summarize(syncs).single().confidence)
    }

    @Test
    fun `JSONL round-trips a sample, and a torn line is skipped rather than fatal`() {
        val row = BandSampleEntity(BandMetric.HEART_RATE, 20260802152034L, 1785683000L, 73.0, 41)
        val line = BandJsonlCodec.encode(row)
        assertEquals("s", BandJsonlCodec.typeOf(line))

        val lines = sequenceOf(
            """{"t":"sync","id":41,"at":"x","zone":"Europe/Berlin","addr":"a","from":1,"src":"action"}""",
            line,
            """{"t":"s","m":"hr","ts":2026""",          // torn by a kill mid-write
            line,
        )
        val samples = BandJsonlCodec.decodeSamples(lines)
        assertEquals(2, samples.size)
        assertEquals(73.0, samples[0].v, 0.001)
        assertEquals(20260802152034L, samples[0].ts)
    }

    @Test
    fun `an unknown field from a newer build does not break an older reader`() {
        val line = """{"t":"s","m":"hr","ts":20260802152034,"e":1,"v":73.0,"sid":41,"future":"whatever"}"""
        assertTrue(BandJsonlCodec.decodeSamples(sequenceOf(line)).isNotEmpty())
    }
}
