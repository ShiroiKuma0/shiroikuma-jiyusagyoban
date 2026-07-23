package com.opentasker.core.diagnostics

import android.app.usage.UsageStatsManager
import android.content.pm.ServiceInfo
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineHealthReaderTest {
    @Test
    fun rareAndRestrictedBucketsAreThrottled() {
        assertTrue(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE))
        assertTrue(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED))
    }

    @Test
    fun activeWorkingSetAndFrequentBucketsAreNotThrottled() {
        assertFalse(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertFalse(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET))
        assertFalse(EngineHealthReader.isThrottledStandbyBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT))
    }

    @Test
    fun foregroundServiceTypesAreHumanReadable() {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        assertEquals("special use, location", EngineHealthReader.foregroundServiceTypeLabel(types))
        assertEquals("None recorded", EngineHealthReader.foregroundServiceTypeLabel(0))
    }

    @Test
    fun workerStopReasonsAreHumanReadable() {
        assertEquals("Timed out", EngineHealthReader.workerStopReasonLabel(WorkInfo.STOP_REASON_TIMEOUT))
        assertEquals("Reason 9876", EngineHealthReader.workerStopReasonLabel(9876))
    }
}
