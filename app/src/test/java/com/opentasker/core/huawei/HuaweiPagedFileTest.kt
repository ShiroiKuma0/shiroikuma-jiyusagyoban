package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locating the page index the band stamps into every 976th byte. */
class HuaweiPagedFileTest {

    private fun stamped(size: Int): ByteArray = ByteArray(size).also { out ->
        var at = 0
        var index = 0
        while (at < out.size) {
            out[at] = (index and 0xFF).toByte()
            at += HuaweiPagedFile.PAGE
            index++
        }
    }

    @Test
    fun `a stamp is found only where the byte agrees with its own page number`() {
        val b = stamped(4000)
        assertTrue(HuaweiPagedFile.stampedAt(b, 976))
        assertTrue(HuaweiPagedFile.stampedAt(b, 3904))
        // Not a boundary at all.
        assertFalse(HuaweiPagedFile.stampedAt(b, 977))

        // A boundary whose byte does not match is one the stamp never reached — a file that needed
        // more than one transfer window loses the alignment partway through, and "repairing" an
        // unstamped byte would be inventing damage.
        b[1952] = 99
        assertFalse(HuaweiPagedFile.stampedAt(b, 1952))
        assertEquals(-1, HuaweiPagedFile.stampIn(b, 1950, 8))
    }

    @Test
    fun `a record is told which of its bytes the stamp took`() {
        val b = stamped(4000)
        // The record starting three bytes before a boundary carries it as its fourth byte.
        assertEquals(3, HuaweiPagedFile.stampIn(b, 973, 15))
        assertEquals(0, HuaweiPagedFile.stampIn(b, 976, 15))
        assertEquals(-1, HuaweiPagedFile.stampIn(b, 977, 15))
        // Pages are 976 apart and records are 8 to 19 long, so no record can hold two.
        assertEquals(14, HuaweiPagedFile.stampIn(b, 962, 15))
    }

    @Test
    fun `the audit says whether a whole file is stamped or only part of it`() {
        val whole = stamped(10_000)
        assertEquals(10, HuaweiPagedFile.audit(whole).boundaries)
        assertTrue(HuaweiPagedFile.audit(whole).whole)

        // What a multi-window transfer looks like: stamped up to a point, then not.
        val partial = whole.copyOf()
        for (k in 6..10) partial[k * HuaweiPagedFile.PAGE] = 0x42
        val audit = HuaweiPagedFile.audit(partial)
        assertEquals(5, audit.stamped)
        assertFalse(audit.whole)
    }
}
