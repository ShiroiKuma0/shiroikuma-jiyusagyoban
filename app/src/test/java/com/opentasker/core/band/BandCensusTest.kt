package com.opentasker.core.band

import com.opentasker.core.storage.BandSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The census is the instrument that measures the band's ring buffer, so its arithmetic is the part
 * most worth pinning down — and it is pure, so it can be.
 *
 * **The golden values below are real.** They come from 白い熊's own archive
 * (`band_2026-08.jsonl`, ten syncs, 2026-07-31 → 2026-08-06). Two HRV windows really were lost, HR's
 * floor really did advance a day without costing anything, and a test that reproduces those exact
 * numbers is worth more than one built from invented ones — it fails if the detector's meaning
 * drifts, not merely if its formula changes.
 */
class BandCensusTest {

    // --- real timestamps, straight out of the archive -----------------------------------------
    // sync 4 (2026-08-03 20:44) and sync 5 (2026-08-05 08:11), the 35-hour gap that cost us HRV.
    private val hrvOldestS4 = 20260802224330L
    private val hrvNewestS4 = 20260803204330L
    private val hrvOldestS5 = 20260804100530L

    // sync 7 (2026-08-05 08:11) and sync 8 (2026-08-06 07:34): HR's floor moved 25 h, lost nothing.
    private val hrOldestS7 = 20260731174035L
    private val hrNewestS7 = 20260805081014L
    private val hrOldestS8 = 20260801185930L
    private val hrNewestS8 = 20260806073330L

    @Test
    fun `the lost window is the hole between what we banked and what the band can still give`() {
        // 2026-08-03 20:43:30 → 2026-08-04 10:05:30 is 13 h 22 m, and it is gone for good.
        val lost = BandCensus.lostWindowSec(hrvNewestS4, hrvOldestS5)
        assertEquals(13.37, lost / 3600.0, 0.01)
    }

    @Test
    fun `a floor that advances a whole day still costs nothing if we already hold the records`() {
        // HR's floor moved 2026-07-31 17:40 → 2026-08-01 18:59, better than 25 hours of eviction…
        val advanced = BandCensus.floorAdvancedSec(hrOldestS7, hrOldestS8)
        assertEquals(25.32, advanced / 3600.0, 0.01)
        // …but the new floor is still far behind what the previous sync had already banked, so the
        // band overwrote only records that are safely in our database. This distinction is the whole
        // point: movement is not loss.
        assertEquals(0L, BandCensus.lostWindowSec(hrNewestS7, hrOldestS8))
    }

    @Test
    fun `buffer depth is the headroom — how long a sync may be missed`() {
        // HR held 2026-08-01 18:59 → 2026-08-06 07:33 at 2048 records: about four and a half days.
        assertEquals(108.57, BandCensus.bufferDepthSec(hrOldestS8, hrNewestS8) / 3600.0, 0.01)
        // HRV, over the same period, held 22 hours — the binding constraint on 白い熊's band.
        assertEquals(22.0, BandCensus.bufferDepthSec(hrvOldestS4, hrvNewestS4) / 3600.0, 0.01)
    }

    @Test
    fun `an unreadable or missing stamp yields zero rather than a wild number`() {
        assertEquals(0L, BandCensus.bufferDepthSec(null, hrNewestS8))
        assertEquals(0L, BandCensus.lostWindowSec(hrNewestS7, null))
        assertEquals(0L, BandCensus.floorAdvancedSec(0L, hrOldestS8))
        // month 99 is not a date; it must not become a duration
        assertEquals(0L, BandCensus.bufferDepthSec(20269901000000L, hrNewestS8))
    }

    @Test
    fun `a floor that has not moved is proof nothing was evicted`() {
        assertEquals(0L, BandCensus.floorAdvancedSec(hrOldestS7, hrOldestS7))
        // …and a floor that somehow reads OLDER than before is not negative eviction, it is zero.
        assertEquals(0L, BandCensus.floorAdvancedSec(hrOldestS8, hrOldestS7))
    }

    @Test
    fun `the tightest stream is the one a warning has to be built on`() {
        val stats = mapOf(
            "hr" to BandStreamStat(bufferDepthSec = 400_000),   // ~111 h
            "hrv" to BandStreamStat(bufferDepthSec = 77_500),   // ~21.5 h
            "spo2" to BandStreamStat(bufferDepthSec = 508_000), // ~141 h
        )
        val tightest = BandCensus.tightest(stats, evicting = setOf("hr", "hrv"))!!
        assertEquals("hrv", tightest.stream)
        assertTrue("a stream seen evicting has its capacity pinned", tightest.measured)
    }

    @Test
    fun `a buffer that has never rolled is a lower bound, not an alarm`() {
        // A freshly reset band has shallow buffers merely because they are empty. Reporting that as
        // measured headroom would raise an emergency over a band that has lost nothing at all.
        val stats = mapOf(
            "hr" to BandStreamStat(bufferDepthSec = 3_600),
            "spo2" to BandStreamStat(bufferDepthSec = 7_200),
        )
        val tightest = BandCensus.tightest(stats, evicting = emptySet())!!
        assertEquals("hr", tightest.stream)
        assertFalse("nothing has rolled, so this is only 'at least'", tightest.measured)
    }

    @Test
    fun `an errored stream contributes nothing to the tightest reading`() {
        val stats = mapOf(
            "hr" to BandStreamStat(bufferDepthSec = 400_000),
            "hrv" to BandStreamStat(bufferDepthSec = 60, error = "timeout"),
        )
        assertEquals("hr", BandCensus.tightest(stats)!!.stream)
    }

    @Test
    fun `the series reports capacity as measured only once a buffer has been seen to roll`() {
        val syncs = listOf(
            BandSyncSummary(
                1, gapHours = 6.0,
                stats = mapOf(
                    "hr" to BandStreamStat(records = 1282, bufferDepthSec = 262_980),
                    "spo2" to BandStreamStat(records = 433, bufferDepthSec = 262_800),
                ),
            ),
            BandSyncSummary(
                2, gapHours = 35.0,
                stats = mapOf(
                    // HR saturated at 2048 and started evicting, without losing anything.
                    "hr" to BandStreamStat(
                        records = 2048, bufferDepthSec = 390_852,
                        floorAdvancedSec = 91_152, lostWindowSec = 0,
                    ),
                    "spo2" to BandStreamStat(records = 837, bufferDepthSec = 508_176),
                ),
            ),
        )
        val byStream = BandCensus.summarize(syncs).associateBy { it.stream }
        val hr = byStream.getValue("hr")
        assertTrue(hr.everEvicted)
        assertEquals("measured", hr.confidence)
        assertEquals(2048, hr.maxRecordsSeen)
        assertEquals(0L, hr.lostSec)

        val spo2 = byStream.getValue("spo2")
        assertFalse(spo2.everEvicted)
        assertEquals("at least", spo2.confidence)
    }

    @Test
    fun `a stream that lost data says so, above every other verdict`() {
        val syncs = listOf(
            BandSyncSummary(
                1, gapHours = 35.0,
                stats = mapOf(
                    "hrv" to BandStreamStat(
                        records = 3363, bufferDepthSec = 79_452,
                        floorAdvancedSec = 127_320, lostWindowSec = 48_120,
                    ),
                ),
            ),
        )
        val hrv = BandCensus.summarize(syncs).single()
        assertEquals("losing", hrv.confidence)
        assertEquals(13.37, hrv.lostSec / 3600.0, 0.01)
    }

    @Test
    fun `a stream that errored says nothing about capacity`() {
        val syncs = listOf(
            BandSyncSummary(
                1, gapHours = 40.0,
                stats = mapOf("hr" to BandStreamStat(records = 0, error = "timeout", bufferDepthSec = 0)),
            ),
        )
        val hr = BandCensus.summarize(syncs).single()
        assertEquals("unknown", hr.confidence)
        assertEquals(0, hr.maxRecordsSeen)
        assertFalse(hr.everEvicted)
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

    @Test
    fun `a census row written before the detector existed still decodes`() {
        // band_syncs is never pruned, so every historical row must survive the field change. The
        // retired expectedRecords/lostRecords are simply unknown keys now.
        val old = """{"frames":83,"pages":1,"records":2011,"inserted":684,"duplicates":1327,""" +
            """"oldestLocalTs":20260731174035,"newestLocalTs":20260805081014,""" +
            """"expectedRecords":1063,"lostRecords":379,"elapsedMs":2100,"end":"TERMINATOR"}"""
        val stat = com.opentasker.core.storage.StorageJson.decodeFromString<BandStreamStat>(old)
        assertEquals(2011, stat.records)
        assertEquals(20260731174035L, stat.oldestLocalTs)
        assertEquals(0L, stat.lostWindowSec)   // absent from the old row, defaulted, not invented
    }
}
