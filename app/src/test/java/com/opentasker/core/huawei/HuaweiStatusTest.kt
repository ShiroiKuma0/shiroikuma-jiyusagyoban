package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Status arithmetic. The rules pinned here are the ones where a plausible-looking shortcut would
 * quietly overstate what we know about the band.
 */
class HuaweiStatusTest {

    private val now = 1_787_000_000_000L
    private val hour = 3_600_000L

    private fun row(
        startedAt: Long = now,
        finishedAt: Long = now + 30_000,
        ok: Boolean = true,
        firmware: String? = "6.0.0.125",
        battery: Int? = 61,
        requestedTo: Long = now / 1000,
        oldestReturned: Long? = null,
        recordCount: Int = 10,
        recordsFetched: Int = 10,
    ) = HuaweiStatus.Companion.Row(
        startedAt, finishedAt, ok, firmware, battery, requestedTo,
        oldestReturned, recordCount, recordsFetched,
    )

    @Test
    fun `no syncs yet is not the same as a depth of zero`() {
        val s = HuaweiStatus.from(emptyList(), null, null)
        assertNull(s.lastSuccessAtMillis)
        assertNull("unmeasured depth must be null, never 0", s.observedDepthSec)
        assertNull(s.batteryPct)
        assertEquals(0, s.syncCount)
    }

    @Test
    fun `observed depth is the deepest a SUCCESSFUL sync ever answered from`() {
        val to = now / 1000
        val s = HuaweiStatus.from(
            listOf(
                row(requestedTo = to, oldestReturned = to - 4 * 3_600),
                row(requestedTo = to, oldestReturned = to - 20 * 3_600),
                row(requestedTo = to, oldestReturned = to - 9 * 3_600),
            ),
            oldestSampleSeconds = to - 20 * 3_600, newestSampleSeconds = to,
        )
        assertEquals(20 * 3_600L, s.observedDepthSec)
        assertEquals(20.0, s.observedDepthHours!!, 0.001)
    }

    @Test
    fun `a failed sync does not contribute to the depth floor`() {
        // It may have stopped early for its own reasons; treating it as the band's limit would
        // understate the buffer and, worse, present a guess as a measurement.
        val to = now / 1000
        val s = HuaweiStatus.from(
            listOf(
                row(ok = false, requestedTo = to, oldestReturned = to - 2 * 3_600),
                row(ok = true, requestedTo = to, oldestReturned = to - 11 * 3_600),
            ),
            null, null,
        )
        assertEquals(11 * 3_600L, s.observedDepthSec)
    }

    @Test
    fun `a sync that returned nothing leaves the depth unmeasured`() {
        val s = HuaweiStatus.from(listOf(row(oldestReturned = null)), null, null)
        assertNull(s.observedDepthSec)
    }

    @Test
    fun `a FAILED sync still contributes its battery reading`() {
        // It connected far enough to read the battery before the history fetch failed. Throwing
        // that away would show "battery unknown" when we plainly know it.
        val s = HuaweiStatus.from(
            listOf(row(ok = false, battery = 44, startedAt = now), row(ok = true, battery = 90, startedAt = now - hour)),
            null, null,
        )
        assertEquals(44, s.batteryPct)
        assertEquals(now, s.batteryAtMillis)
    }

    @Test
    fun `the battery figure always travels with its age`() {
        val s = HuaweiStatus.from(listOf(row(startedAt = now - 5 * hour)), null, null)
        assertEquals(5.0, s.batteryAgeHours(now)!!, 0.001)
    }

    @Test
    fun `last success skips failed syncs and ignores an unfinished row`() {
        val s = HuaweiStatus.from(
            listOf(
                row(ok = false, finishedAt = now),
                row(ok = true, finishedAt = 0L),           // lost mid-run: never a success time
                row(ok = true, finishedAt = now - 2 * hour),
            ),
            null, null,
        )
        assertEquals(now - 2 * hour, s.lastSuccessAtMillis)
        assertEquals(2.0, s.ageHours(now)!!, 0.001)
    }

    @Test
    fun `missing records come from the newest sync and are never negative`() {
        val s = HuaweiStatus.from(
            listOf(row(recordCount = 40, recordsFetched = 37), row(recordCount = 5, recordsFetched = 5)),
            null, null,
        )
        assertEquals(40, s.lastRecordCount)
        assertEquals(37, s.lastRecordsFetched)
        assertEquals(3, s.lastMissingCount)

        val odd = HuaweiStatus.from(listOf(row(recordCount = 2, recordsFetched = 5)), null, null)
        assertEquals(0, odd.lastMissingCount)
    }

    @Test
    fun `held span is what we accumulated, and is not the band's depth`() {
        val to = now / 1000
        val s = HuaweiStatus.from(
            listOf(row(requestedTo = to, oldestReturned = to - 6 * 3_600)),
            oldestSampleSeconds = to - 30 * 24 * 3_600, newestSampleSeconds = to,
        )
        assertEquals(30 * 24 * 3_600L, s.heldSpanSeconds)
        assertEquals(6 * 3_600L, s.observedDepthSec)
    }
}
