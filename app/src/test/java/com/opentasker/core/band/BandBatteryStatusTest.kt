package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band's charge, and the fact that it is only ever as fresh as the last sync.
 *
 * The band answers a battery query while a sync is connected and at no other time, so the percentage
 * is a snapshot with a timestamp — not a live reading. Showing it without its age would let a figure
 * from yesterday read as current, which is worse than showing nothing.
 */
class BandBatteryStatusTest {

    private val hour = 3_600_000L
    private val now = 1_786_000_000_000L

    private fun status(pct: Int?, atMs: Long?) = BandStatus(
        lastSuccessAtMillis = atMs,
        headroom = null,
        lostSec = 0,
        lostStreams = emptyList(),
        batteryPct = pct,
        batteryAtMillis = atMs,
    )

    @Test
    fun `a charge carries how old it is`() {
        val s = status(76, now - 3 * hour)
        assertEquals(76, s.batteryPct)
        assertEquals(3.0, s.batteryAgeHours(now)!!, 1e-6)
    }

    @Test
    fun `no reading yet means no percentage and no age, not a zero`() {
        val s = status(null, null)
        assertNull("an unread charge must never render as 0 %", s.batteryPct)
        assertNull(s.batteryAgeHours(now))
    }

    /** A charge read seconds ago is effectively live; the UI says "just now" below one hour. */
    @Test
    fun `a fresh reading is under an hour old`() {
        assertTrue(status(88, now - 60_000L).batteryAgeHours(now)!! < 1.0)
    }

    @Test
    fun `a stale reading is honestly stale`() {
        assertEquals(30.0, status(41, now - 30 * hour).batteryAgeHours(now)!!, 1e-6)
    }

    /** The default is absent, so a BandStatus built without a battery never invents one. */
    @Test
    fun `battery defaults to absent`() {
        val s = BandStatus(lastSuccessAtMillis = now, headroom = null, lostSec = 0, lostStreams = emptyList())
        assertNull(s.batteryPct)
        assertNull(s.batteryAtMillis)
    }
}
