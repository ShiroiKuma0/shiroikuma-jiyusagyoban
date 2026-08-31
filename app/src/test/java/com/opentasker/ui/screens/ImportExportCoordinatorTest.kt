package com.opentasker.ui.screens

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportExportCoordinatorTest {
    private fun scope() = CoroutineScope(Dispatchers.Default + Job())

    @Test
    fun cancellingMidDecodeNeverReachesTheWrite() = runBlocking {
        val coordinator = ImportExportCoordinator(scope())
        val reachedDecode = CompletableDeferred<Unit>()
        var wrote = false

        assertTrue(
            coordinator.launch { report ->
                report(TransferStage.Preflight)
                report(TransferStage.Decode)
                reachedDecode.complete(Unit)
                // Stands in for a large Tasker or MacroDroid backup still being decoded.
                delay(30_000)
                report(TransferStage.Write)
                wrote = true
            },
        )

        withTimeout(TIMEOUT_MS) { reachedDecode.await() }
        coordinator.cancel()

        // The lane has to return to idle, otherwise the review dialog stays stuck busy and the
        // user cannot dismiss it. That is the bug the finally block exists for.
        withTimeout(TIMEOUT_MS) { coordinator.busy.first { !it } }
        assertFalse("Cancelling during decode must not reach the write", wrote)
        assertEquals(null, coordinator.progress.value)
    }

    @Test
    fun aBusyLaneRefusesASecondStartAndTheFirstStillFinishes() = runBlocking {
        val coordinator = ImportExportCoordinator(scope())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var completions = 0

        assertTrue(
            coordinator.launch {
                started.complete(Unit)
                release.await()
                completions++
            },
        )
        withTimeout(TIMEOUT_MS) { started.await() }

        assertFalse("A second transfer must not start on a busy lane", coordinator.launch { completions++ })

        release.complete(Unit)
        withTimeout(TIMEOUT_MS) { coordinator.busy.first { !it } }
        assertEquals(1, completions)
    }

    @Test
    fun aFailedTransferReleasesTheLane() = runBlocking {
        val coordinator = ImportExportCoordinator(scope())

        coordinator.launch { throw IllegalStateException("decode failed") }

        withTimeout(TIMEOUT_MS) { coordinator.busy.first { !it } }
        assertEquals(null, coordinator.progress.value)
        assertTrue("The lane must accept work again after a failure", coordinator.launch { })
    }

    @Test
    fun theReportedStageIsVisibleWhileTheTransferRuns() = runBlocking {
        val coordinator = ImportExportCoordinator(scope())
        val atPlan = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coordinator.launch { report ->
            report(TransferStage.Plan)
            atPlan.complete(Unit)
            release.await()
        }

        withTimeout(TIMEOUT_MS) { atPlan.await() }
        assertEquals(TransferProgress(TransferStage.Plan), coordinator.progress.value)
        release.complete(Unit)
        withTimeout(TIMEOUT_MS) { coordinator.busy.first { !it } }
        assertEquals(null, coordinator.progress.value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
