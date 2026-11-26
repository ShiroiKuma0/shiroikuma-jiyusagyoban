package com.sysadmindoc.ruledeck.ui.screens

import android.app.usage.UsageStatsManager
import com.sysadmindoc.ruledeck.ProductionSources
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.diagnostics.EngineHealthReader
import com.sysadmindoc.ruledeck.core.scheduling.AlarmSchedulePrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics words the reader's codes itself (A-438), so each code the reader names must have a
 * resource here too, or that reading shows up in English again.
 */
class DiagnosticsHealthTextTest {
    // Wider than any platform's PENDING_JOB_REASON_* or REASON_* range.
    private val codes = -1..256

    @Test
    fun everySignalTheReaderPublishesHasAName() {
        val reader = ProductionSources.read("com/sysadmindoc/ruledeck/core/diagnostics/EngineHealthReader.kt")
        val keys = Regex("""HealthSignal\(\s*"([a-z-]+)"""").findAll(reader).map { it.groupValues[1] }.toList()

        assertEquals("the reader's signals moved; this test reads them from its source", 9, keys.size)
        assertEquals(emptyList<String>(), keys.filter { healthSignalLabel(it) == null })
    }

    @Test
    fun everyPendingJobReasonTheReaderNamesHasAResource() {
        val named = codes.filter { EngineHealthReader.pendingJobReasonLabel(it) != "Constraint $it" }
        val mapped = codes.filter { pendingJobReasonRes(it) != null }

        assertEquals(15, named.size)
        assertEquals(named, mapped)
        assertEquals(named.size, mapped.mapNotNull(::pendingJobReasonRes).toSet().size)
    }

    @Test
    fun everyExitReasonTheReaderNamesIsMatchedBackFromItsStoredLabel() {
        val named = codes.filter { EngineHealthReader.applicationExitReasonLabel(it) != "Reason $it" }
        val resources = named.map { exitReasonRes(EngineHealthReader.applicationExitReasonLabel(it)) }

        assertEquals(16, named.size)
        assertTrue("unmatched: ${named.zip(resources).filter { it.second == null }}", resources.all { it != null })
        assertEquals("two exit reasons share a resource", named.size, resources.toSet().size)
        assertNull(exitReasonRes("Reason 99"))
    }

    @Test
    fun standbyBucketsAndAlarmPrecisionEachGetTheirOwnWords() {
        val buckets = listOf(
            UsageStatsManager.STANDBY_BUCKET_ACTIVE,
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET,
            UsageStatsManager.STANDBY_BUCKET_FREQUENT,
            UsageStatsManager.STANDBY_BUCKET_RARE,
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED,
        ).map(::standbyConsequenceRes)

        assertEquals(5, buckets.toSet().size)
        assertTrue(R.string.diagnostics_standby_unknown !in buckets)
        assertEquals(R.string.diagnostics_standby_unavailable, standbyConsequenceRes(null))
        assertEquals(R.string.diagnostics_standby_unknown, standbyConsequenceRes(99))
        assertNotEquals(exactAlarmRes(AlarmSchedulePrecision.Exact), exactAlarmRes(AlarmSchedulePrecision.InexactFallback))
        assertNotNull(exactAlarmRes(null))
    }
}
