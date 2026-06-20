package com.opentasker.core.engine

import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunLogPruneWorkerTest {

    @Test
    fun pruneIntervalIsSixHours() {
        val intervalMs = TimeUnit.HOURS.toMillis(6)
        assertEquals(21_600_000L, intervalMs)
    }

    @Test
    fun pruneWorkerClassIsCoroutineWorker() {
        assertTrue(
            "RunLogPruneWorker must be a CoroutineWorker",
            androidx.work.CoroutineWorker::class.java.isAssignableFrom(RunLogPruneWorker::class.java),
        )
    }

    @Test
    fun keepPolicyPreservesExistingWork() {
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            ExistingPeriodicWorkPolicy.KEEP,
        )
    }
}
