package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every vector here is REAL bytes off 白い熊's band, captured 2026-08-02 at 15:23 local, and every
 * expected value was cross-checked against what the Hume app displayed for the same period.
 */
class BandRecordsTest {

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `heart rate records tile at stride 10`() {
        val frame = hex("550000260802152034 49 550100260802151530 4a")
        val out = BandRecords.parse(BandStream.HEART_RATE, frame)
        assertEquals(2, out.samples.size)
        assertEquals(BandSample(BandMetric.HEART_RATE, 20260802152034L, 73.0), out.samples[0])
        assertEquals(BandSample(BandMetric.HEART_RATE, 20260802151530L, 74.0), out.samples[1])
    }

    @Test
    fun `HRV record yields six metrics, and omits the zero fields`() {
        val frame = hex("560000260802151930 45 4f 00 4f 00 00")
        val out = BandRecords.parse(BandStream.HRV, frame)
        val byMetric = out.samples.associate { it.metric to it.value }
        assertEquals(69.0, byMetric[BandMetric.HRV])
        assertEquals(79.0, byMetric[BandMetric.VASCULAR])
        assertEquals(79.0, byMetric[BandMetric.STRESS])
        // HR, systolic and diastolic read 0 in this record — the band did not take them.
        assertTrue(BandMetric.HRV_HEART_RATE !in byMetric)
        assertTrue(BandMetric.SYSTOLIC !in byMetric)
        assertTrue(BandMetric.DIASTOLIC !in byMetric)
    }

    @Test
    fun `a fully populated HRV record keeps blood pressure`() {
        val frame = hex("560200260802151530 2c 2f 4a 2f 72 40")
        val byMetric = BandRecords.parse(BandStream.HRV, frame).samples.associate { it.metric to it.value }
        assertEquals(44.0, byMetric[BandMetric.HRV])
        assertEquals(47.0, byMetric[BandMetric.VASCULAR])
        assertEquals(74.0, byMetric[BandMetric.HRV_HEART_RATE])
        assertEquals(47.0, byMetric[BandMetric.STRESS])
        assertEquals(114.0, byMetric[BandMetric.SYSTOLIC])
        assertEquals(64.0, byMetric[BandMetric.DIASTOLIC])
    }

    @Test
    fun `SpO2 and temperature`() {
        val spo2 = BandRecords.parse(BandStream.SPO2, hex("660000260802152034 60")).samples.single()
        assertEquals(BandSample(BandMetric.SPO2, 20260802152034L, 96.0), spo2)

        val temp = BandRecords.parse(BandStream.TEMPERATURE, hex("650000260802145900 6c01")).samples.single()
        assertEquals(BandMetric.TEMPERATURE, temp.metric)
        assertEquals(20260802145900L, temp.localTs)
        assertEquals(36.4, temp.value, 0.001)
    }

    @Test
    fun `detail activity yields bucket totals plus per-minute steps that sum to the total`() {
        val frame = hex("5200002608021519314500e30105002400210000000000000000")
        val out = BandRecords.parse(BandStream.DETAIL, frame)
        val buckets = out.samples.filter { it.metric != BandMetric.STEPS_MINUTE }.associate { it.metric to it.value }
        assertEquals(69.0, buckets[BandMetric.STEPS_BUCKET])
        assertEquals(4.83, buckets[BandMetric.CALORIES_BUCKET]!!, 0.001)
        assertEquals(0.05, buckets[BandMetric.DISTANCE_BUCKET]!!, 0.001)

        val perMinute = out.samples.filter { it.metric == BandMetric.STEPS_MINUTE }
        assertEquals(2, perMinute.size)
        assertEquals(69.0, perMinute.sumOf { it.value }, 0.001)
        // forward from the record's timestamp: t+0 and t+2
        assertEquals(20260802151931L, perMinute[0].localTs)
        assertEquals(20260802152131L, perMinute[1].localTs)
    }

    /**
     * The per-minute counts run forward, not backward — the hand-off's one unconfirmed field.
     *
     * These three are real records off the band. The evidence is in the first: a record whose ONLY
     * non-zero count sits in slot 0. Across the 87-record capture, twenty records have exactly one
     * non-zero slot and it is slot 0 in every one; backward would scatter those over ten indices.
     * Forward also explains the arbitrary seconds in the timestamps — the band opens the bucket when
     * its first step lands, so slot 0 can never be empty.
     */
    @Test
    fun `per-minute counts run forward from the record timestamp, not backward`() {
        // 14:25:22, ten steps, and nothing else in the bucket.
        val lone = BandRecords.parse(BandStream.DETAIL, hex("5205002608021425220a00440000000a000000000000000000"))
            .samples.filter { it.metric == BandMetric.STEPS_MINUTE }
        assertEquals(1, lone.size)
        assertEquals(10.0, lone[0].value, 0.001)
        // Forward puts it at t+0. Backward would put it at t-9, i.e. 14:16.
        assertEquals(20260802142522L, lone[0].localTs)

        // 14:15:17, steps at t+0 and t+7 — the offsets are minutes, and the gap is preserved.
        val sparse = BandRecords.parse(BandStream.DETAIL, hex("5206002608021415172200c60003000c000000000000160000"))
            .samples.filter { it.metric == BandMetric.STEPS_MINUTE }
        assertEquals(listOf(20260802141517L, 20260802142217L), sparse.map { it.localTs })
        assertEquals(34.0, sparse.sumOf { it.value }, 0.001)

        // 09:50:29, a full walk: ten consecutive minutes, t+0 … t+9, summing to the bucket total.
        val full = BandRecords.parse(BandStream.DETAIL, hex("521400260802095029990401236e006c82786a6e7c6b806d87"))
        val minutes = full.samples.filter { it.metric == BandMetric.STEPS_MINUTE }
        assertEquals((0..9).map { 20260802095029L + it * 100L }, minutes.map { it.localTs })
        assertEquals(1177.0, minutes.sumOf { it.value }, 0.001)
        assertEquals(
            1177.0,
            full.samples.first { it.metric == BandMetric.STEPS_BUCKET }.value,
            0.001,
        )
    }

    @Test
    fun `daily totals tile at stride 27`() {
        val frame = hex(
            "5100260802 5b180000 660a0000 5f020000 94b20000 0000 18000000" +
                "5101260801 3c270000 f9110000 64030000 210b0100 0000 24000000",
        )
        val out = BandRecords.parse(BandStream.DAILY, frame)
        assertEquals(2, out.daily.size)
        assertEquals(20260802L, out.daily[0].localDate)
        assertEquals(6235L, out.daily[0].steps)
        assertEquals(6070.0, out.daily[0].distanceM, 0.001)   // 6.07 km
        assertEquals(457.16, out.daily[0].calories, 0.001)
        assertEquals(20260801L, out.daily[1].localDate)
        assertEquals(10044L, out.daily[1].steps)
    }

    @Test
    fun `a sleep frame is ONE segment, and stage codes are stored raw`() {
        // 22 stage bytes: three light, four awake, fifteen light — then zero padding to 130.
        val stages = "02".repeat(3) + "05".repeat(4) + "02".repeat(15)
        val frame = hex("530000 260802052857 16 " + stages + "00".repeat(130 - 10 - 22))
        val seg = BandRecords.parse(BandStream.SLEEP, frame).sleep.single()
        assertEquals(20260802052857L, seg.startLocalTs)
        assertEquals(22, seg.minutes)
        assertEquals(22, seg.stages.length)
        assertEquals(4, seg.awake)   // code 5, NOT re-coded to 3
        assertEquals(18, seg.light)
        assertEquals(0, seg.rem)
        assertEquals(0, seg.deep)
    }

    @Test
    fun `padding and sentinel slices are discarded, never invented`() {
        assertEquals(0, BandRecords.parse(BandStream.HEART_RATE, ByteArray(40)).recordCount)
        assertEquals(0, BandRecords.parse(BandStream.HEART_RATE, ByteArray(40) { 0xFF.toByte() }).recordCount)
        // a bare terminator carries nothing
        assertEquals(0, BandRecords.parse(BandStream.SLEEP, hex("53ff")).recordCount)
    }

    @Test
    fun `per-minute offsets roll over a month end instead of inventing day 32`() {
        assertEquals(20260901000500L, BandRecords.addMinutes(20260831235500L, 10))
    }

    /**
     * One BLE notification is one frame, packed with whole records up to the granted payload.
     *
     * This was an open assumption for months and is now measured. At the granted MTU of 247 the
     * payload is 244 bytes, and the records-per-notification observed on 白い熊's band lands exactly
     * on `floor(244 / stride)` for four independent strides at once — 24 for heart rate and SpO₂ at
     * stride 10, 22 for temperature at 11, 16 for HRV at 15, 9 for detail at 25 — while sleep, whose
     * 130-byte frames are the largest and the only ones that could plausibly fragment, came back at
     * exactly 255 records in 255 frames.
     *
     * Four strides cannot coincide with that arithmetic by chance, so the packing rule is real. The
     * assertion here is the *ceiling*: a frame may be short (the last one usually is), but it can
     * never carry more than the payload holds. If it ever does, notifications are being coalesced and
     * the frame-counted paging rule has lost its meaning.
     */
    @Test
    fun `a frame never carries more records than the granted payload can hold`() {
        val payload = 247 - 3
        // stride, one real record, and the metric emitted exactly once per record. HRV and detail
        // fan a single record out into several samples — and detail's per-minute samples carry
        // SHIFTED timestamps — so records have to be counted by an anchor metric, not by rows or by
        // distinct stamps.
        data class Case(val stride: Int, val golden: String, val anchor: String)
        val cases = mapOf(
            BandStream.HEART_RATE to Case(10, "550000260802152034 49", BandMetric.HEART_RATE),
            BandStream.SPO2 to Case(10, "660000260802152034 60", BandMetric.SPO2),
            BandStream.TEMPERATURE to Case(11, "650000260802145900 6c01", BandMetric.TEMPERATURE),
            BandStream.HRV to Case(15, "560000260802151930 45 4f 00 4f 00 00", BandMetric.HRV),
            BandStream.DETAIL to Case(
                25,
                "5200002608021519314500e30105002400210000000000000000",
                BandMetric.STEPS_BUCKET,
            ),
        )
        for ((stream, case) in cases) {
            // The golden lines are quoted to the byte from the capture, and detail's carries one
            // trailing pad byte past its stride; take exactly one record's worth.
            val one = hex(case.golden).copyOf(case.stride)
            val fit = payload / case.stride
            val frame = ByteArray(fit * case.stride) { one[it % case.stride] }
            val records = BandRecords.parse(stream, frame).samples.count { it.metric == case.anchor }
            assertTrue(
                "$stream: $records records in a $payload-byte payload exceeds " +
                    "floor($payload/${case.stride})=$fit",
                records <= fit,
            )
            // …and the frame really is packed full, which is what makes the ceiling meaningful.
            assertEquals("$stream should fill its payload", fit, records)
        }
    }

    /** The machine reports the extremes it saw, which is what makes the rule above self-monitoring. */
    @Test
    fun `the stream machine records the longest and shortest notification`() {
        val machine = BandStreamMachine(BandStream.HEART_RATE)
        machine.onFrame(ByteArray(240) { 0 })
        machine.onFrame(ByteArray(20) { 0 })
        machine.onFrame(ByteArray(100) { 0 })
        assertEquals(240, machine.maxFrameBytes)
        assertEquals(20, machine.minFrameBytes)
    }
}
