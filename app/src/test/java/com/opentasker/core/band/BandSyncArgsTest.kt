package com.opentasker.core.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BandSyncArgsTest {

    private val now = LocalDateTime.of(2026, 8, 3, 14, 30, 0)

    @Test
    fun `defaults are the documented ones`() {
        val args = BandSyncArgs.parse(emptyMap()).getOrThrow()
        assertEquals(BandFrom.Auto, args.from)
        assertEquals("BAND_", args.prefix)
        assertEquals(180, args.timeoutSec)
        assertTrue("the archive is on unless switched off", args.backup)
        assertNull(args.address)
        assertEquals(BandStream.SYNC_ORDER, args.streams)
    }

    @Test
    fun `from accepts auto, a day count and an explicit instant`() {
        assertEquals(BandFrom.Auto, BandSyncArgs.parse(mapOf("from" to "auto")).getOrThrow().from)
        assertEquals(BandFrom.Days(7), BandSyncArgs.parse(mapOf("from" to "7")).getOrThrow().from)
        assertEquals(
            BandFrom.At(BandLocalTime(2026, 7, 28, 6, 15, 30)),
            BandSyncArgs.parse(mapOf("from" to "2026-07-28 06:15:30")).getOrThrow().from,
        )
        assertEquals(
            BandFrom.At(BandLocalTime(2026, 7, 28)),
            BandSyncArgs.parse(mapOf("from" to "2026-07-28")).getOrThrow().from,
        )
    }

    @Test
    fun `an unparseable from is rejected rather than silently defaulted`() {
        assertTrue(BandSyncArgs.parse(mapOf("from" to "last tuesday")).isFailure)
    }

    @Test
    fun `timeout is coerced, not rejected — a silly Profile value should still sync`() {
        assertEquals(15, BandSyncArgs.parse(mapOf("timeout_sec" to "1")).getOrThrow().timeoutSec)
        assertEquals(600, BandSyncArgs.parse(mapOf("timeout_sec" to "99999")).getOrThrow().timeoutSec)
    }

    @Test
    fun `auto re-requests the overlap, because overlap is free and a gap is not`() {
        val lastSuccess = LocalDateTime.of(2026, 8, 3, 12, 0, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val from = BandSyncArgs.resolve(BandFrom.Auto, lastSuccess, overlapMinutes = 30, now = now)
        assertEquals(BandLocalTime(2026, 8, 3, 11, 30, 0), from)
    }

    @Test
    fun `auto with no previous sync falls back to three days, from the start of that day`() {
        val from = BandSyncArgs.resolve(BandFrom.Auto, null, overlapMinutes = 30, now = now)
        assertEquals(BandLocalTime(2026, 7, 31, 0, 0, 0), from)
    }

    @Test
    fun `a stream list narrows, and an unrecognised one falls back to everything`() {
        assertEquals(
            listOf(BandStream.HEART_RATE, BandStream.SLEEP),
            BandSyncArgs.parse(mapOf("streams" to "hr, sleep")).getOrThrow().streams,
        )
        // a typo in the 01 task must not stop a sync dead
        assertEquals(
            BandStream.SYNC_ORDER,
            BandSyncArgs.parse(mapOf("streams" to "hearrate")).getOrThrow().streams,
        )
    }

    @Test
    fun `backup can be switched off explicitly`() {
        assertTrue(!BandSyncArgs.parse(mapOf("backup" to "false")).getOrThrow().backup)
        assertTrue(!BandSyncArgs.parse(mapOf("backup" to "0")).getOrThrow().backup)
        assertTrue(BandSyncArgs.parse(mapOf("backup" to "true")).getOrThrow().backup)
    }
}
