package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a predicted set's own expiry out of its header.
 *
 * The age is REPORTED, never acted on — and that distinction was bought expensively. For one build
 * an expired file was dropped rather than served, on the reasoning that a stale orbit is worse than
 * none. The band disagreed: carrying Huawei's out-of-date BeiDou and QZSS the fix took about a
 * minute, and with those two removed and nothing else changed it went back to about three (白い熊,
 * 2026-08-29). So this reads the window in order to SHOW it, and the decision about what is worth
 * serving stays with the staging directory, where a person can see it.
 */
class HuaweiGnssExpiryTest {

    /** A header of 36 (GPS seconds, offset, length) triples, little-endian, two hours apart. */
    private fun header(firstBlockGpsSeconds: Long): ByteArray {
        val b = ByteArray(1008 + 16)
        for (i in 0 until 36) {
            val ts = firstBlockGpsSeconds + i * 7200
            for (k in 0 until 4) b[12 * i + k] = ((ts shr (8 * k)) and 0xFF).toByte()
        }
        return b
    }

    private fun gpsNow() =
        System.currentTimeMillis() / 1000 -
            HuaweiGnssAction.GPS_UNIX_EPOCH + HuaweiGnssAction.GPS_LEAP_SECONDS

    @Test
    fun `the last of the 36 stamps is what the file is good until`() {
        val first = gpsNow()
        val last = HuaweiGnssAction.lastBlockSeconds(header(first))
        assertEquals("35 steps of 7200 s past the first block", first + 35 * 7200, last)
    }

    @Test
    fun `a set built now is still about the future`() {
        val last = HuaweiGnssAction.lastBlockSeconds(header(gpsNow()))!!
        assertTrue("a fresh 72 h set has not expired", last > gpsNow())
    }

    /**
     * The case that actually shipped: a set whose window closed yesterday.
     *
     * 72 hours wide and started four days ago, so every block including the last is behind us.
     */
    @Test
    fun `a set whose window has closed reads as expired`() {
        val last = HuaweiGnssAction.lastBlockSeconds(header(gpsNow() - 4 * 86400))!!
        assertTrue("its last block is in the past", last < gpsNow())
    }

    /**
     * Anything that is not a header answers null rather than a number.
     *
     * `HW_PGNSS_EXTRA` is the static blob — almanacs, iono, channel tables — and is not a 36-block
     * epoch file at all, so a reader that confidently returned a date for it would expire the one
     * file that has no such window.
     */
    @Test
    fun `bytes that are not a header say so`() {
        assertNull(HuaweiGnssAction.lastBlockSeconds(ByteArray(64)))
        assertNull("all-zero stamps are not a date", HuaweiGnssAction.lastBlockSeconds(ByteArray(2048)))
    }
}
