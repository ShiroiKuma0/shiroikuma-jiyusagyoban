package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a sync earns the error dialog — [HuaweiSyncTrouble.of], on 2026-11-10's own summaries. */
class HuaweiSyncTroubleTest {

    @Test
    fun `a clean sync says nothing`() {
        assertNull(
            HuaweiSyncTrouble.of(
                true,
                "1171 samples from 49/49 records · 89 sleep segments · 372 RR windows · 31 beat records",
                0L,
            ),
        )
    }

    @Test
    fun `a sync that failed outright is a failure with nothing listed as lost`() {
        val t = HuaweiSyncTrouble.of(false, "Broken pipe", 0L)!!
        assertTrue(t.failed)
        assertEquals(emptyList<String>(), t.lost)
    }

    @Test
    fun `a finished sync that lost parts to a cut link names them`() {
        // 09:08 that morning: reported as a success, and last night never arrived.
        val t = HuaweiSyncTrouble.of(
            true,
            "1114 samples from 49/49 records · sleep: Broken pipe · 372 RR windows · reconnect 25/29 " +
                "answered · dictionary: Broken pipe · ecg: Broken pipe · satellite other declined",
            0L,
        )!!
        assertEquals(false, t.failed)
        assertEquals(listOf("sleep", "dictionary", "ecg"), t.lost)
    }
}
