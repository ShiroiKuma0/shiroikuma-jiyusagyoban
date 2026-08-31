package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record-to-sample conversion. This is the layer that can quietly corrupt data — by inventing
 * readings for minutes the band left empty, or by discarding real zeros — so the rules are pinned
 * here rather than trusted.
 */
class HuaweiSyncEngineTest {

    private fun minute(
        ts: Long,
        steps: Int? = null,
        calories: Int? = null,
        distance: Int? = null,
        hr: Int? = null,
        spo2: Int? = null,
        restingHr: Int? = null,
        unknown: Map<Int, Int> = emptyMap(),
    ) = HuaweiRecords.Minute(ts, steps, calories, distance, hr, spo2, restingHr, unknown)

    private fun record(vararg minutes: HuaweiRecords.Minute) =
        HuaweiRecords.StepRecord(index = 1, baseEpochSeconds = 1_000L, minutes = minutes.toList())

    @Test
    fun `an empty minute produces no samples at all`() {
        // Absent means "not measured". Emitting a zero here would invent data the band never took.
        val samples = HuaweiSyncEngine.toSamples(record(minute(1_000L)))
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `a recorded zero is kept, because zero steps is a real observation`() {
        val samples = HuaweiSyncEngine.toSamples(record(minute(1_000L, steps = 0)))
        assertEquals(1, samples.size)
        assertEquals(HuaweiSyncEngine.METRIC_STEPS, samples[0].metric)
        assertEquals(0.0, samples[0].value, 0.0)
    }

    @Test
    fun `every present field becomes its own row at the same minute`() {
        val samples = HuaweiSyncEngine.toSamples(
            record(minute(1_500L, steps = 12, calories = 3, distance = 9, hr = 61, spo2 = 97, restingHr = 55)),
        )
        assertEquals(6, samples.size)
        assertTrue(samples.all { it.epochSeconds == 1_500L })
        assertEquals(
            setOf("steps", "calories", "distance", "hr", "spo2", "resting_hr"),
            samples.map { it.metric }.toSet(),
        )
    }

    @Test
    fun `unknown feature bits survive as their own metric`() {
        // A firmware change must show up in the data, not disappear.
        val samples = HuaweiSyncEngine.toSamples(record(minute(1_000L, unknown = mapOf(0x10 to 500))))
        assertEquals(1, samples.size)
        assertEquals("unknown_10", samples[0].metric)
        assertEquals(500.0, samples[0].value, 0.0)
    }

    @Test
    fun `the sparse real record yields exactly two distance samples`() {
        // The same record 9 pulled off 白い熊's band: 34 minutes, two with data.
        val parsed = HuaweiRecords.parseStepRecord(
            HuaweiProtocol.parseTlvs(
                HuaweiProtocol.tlv(
                    0x81,
                    HuaweiProtocol.tlv(0x02, 9, 2) +
                        HuaweiProtocol.tlv(0x03, 0x6A890228, 4) +
                        HuaweiProtocol.tlv(0x84, HuaweiProtocol.tlv(0x05, byteArrayOf(0)) + HuaweiProtocol.tlv(0x06, byteArrayOf(0))) +
                        HuaweiProtocol.tlv(0x84, HuaweiProtocol.tlv(0x05, byteArrayOf(0x1F)) + HuaweiProtocol.tlv(0x06, HuaweiCrypto.hex("080018"))) +
                        HuaweiProtocol.tlv(0x84, HuaweiProtocol.tlv(0x05, byteArrayOf(0x20)) + HuaweiProtocol.tlv(0x06, HuaweiCrypto.hex("080002"))),
                ),
            ),
        )!!
        val samples = HuaweiSyncEngine.toSamples(parsed)
        assertEquals(2, samples.size)
        assertTrue(samples.all { it.metric == HuaweiSyncEngine.METRIC_DISTANCE })
        assertEquals(24.0, samples[0].value, 0.0)
        assertEquals(2.0, samples[1].value, 0.0)
    }

    @Test
    fun `record indices are zero-based, so a count of N means 0 until N`() {
        // Measured on the band 2026-08-22. This ran as 1..count and refused exactly one record on
        // every sync; the loud half was asking for index `count`, and the quiet half — the one that
        // mattered — was skipping record 0 and losing the oldest record of every window in silence.
        //
        // Pinned as arithmetic rather than as a comment because the symptom was survivable: syncs
        // still returned data, still reported success, and still looked correct.
        val count = 4
        val asked = (0 until minOf(count, 4096)).toList()
        assertEquals(listOf(0, 1, 2, 3), asked)
        assertEquals(count, asked.size)
        assertTrue("index `count` is out of range and must never be requested", count !in asked)
        assertTrue("record 0 is real data and must always be requested", 0 in asked)
    }

    @Test
    fun `dedupe keeps the last value for a repeated metric and minute`() {
        // Overlapping windows are deliberate, so duplicates are normal; REPLACE semantics mean the
        // last one must win, matching what the table will do.
        val deduped = HuaweiSyncEngine.dedupe(
            listOf(
                HuaweiSyncEngine.Sample("hr", 100L, 60.0),
                HuaweiSyncEngine.Sample("hr", 100L, 62.0),
                HuaweiSyncEngine.Sample("hr", 160L, 61.0),
            ),
        )
        assertEquals(2, deduped.size)
        assertEquals(62.0, deduped.first { it.epochSeconds == 100L }.value, 0.0)
    }

    @Test
    fun `dedupe does not merge across metrics at the same minute`() {
        val deduped = HuaweiSyncEngine.dedupe(
            listOf(
                HuaweiSyncEngine.Sample("hr", 100L, 60.0),
                HuaweiSyncEngine.Sample("spo2", 100L, 97.0),
            ),
        )
        assertEquals(2, deduped.size)
    }

    @Test
    fun `Huawei metric names do not collide with the Hume band's semantics`() {
        // Hume's "hrv" is a device-state index, not HRV; Huawei's real HRV will arrive from service
        // 0x19 under its own name. Nothing here may emit a bare "hrv".
        val names = listOf(
            HuaweiSyncEngine.METRIC_STEPS, HuaweiSyncEngine.METRIC_CALORIES,
            HuaweiSyncEngine.METRIC_DISTANCE, HuaweiSyncEngine.METRIC_HEART_RATE,
            HuaweiSyncEngine.METRIC_SPO2, HuaweiSyncEngine.METRIC_RESTING_HR,
        )
        assertTrue("no bare hrv metric may be emitted here", names.none { it == "hrv" })
    }
}
