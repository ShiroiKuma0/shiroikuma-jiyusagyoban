package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionAdmissionControllerTest {
    private class Clock(var nowMs: Long = 1_000L) {
        fun now() = nowMs
    }

    private fun limits(
        globalMaxActive: Int = 2,
        perProfileMaxActive: Int = 1,
        globalBurstLimit: Int = 6,
        perProfileBurstLimit: Int = 2,
        burstWindowMs: Long = 100L,
        circuitTripCount: Int = 2,
        circuitOpenMs: Long = 1_000L,
    ) = ExecutionAdmissionLimits(
        globalMaxActive = globalMaxActive,
        perProfileMaxActive = perProfileMaxActive,
        globalBurstLimit = globalBurstLimit,
        perProfileBurstLimit = perProfileBurstLimit,
        burstWindowMs = burstWindowMs,
        circuitTripCount = circuitTripCount,
        circuitOpenMs = circuitOpenMs,
    )

    @Test
    fun globalAndPerProfileActiveCapsApplyToAllProfiles() {
        val controller = ExecutionAdmissionController(limits())
        val first = controller.tryAcquire(profileId = 7L)
        assertTrue(first.accepted)
        assertFalse(controller.tryAcquire(profileId = 7L).accepted)

        val otherProfile = controller.tryAcquire(profileId = 8L)
        assertTrue(otherProfile.accepted)
        assertFalse(controller.tryAcquire(profileId = null).accepted)

        assertNotNull(first.lease)
        first.lease?.release()
        otherProfile.lease?.release()
        assertEquals(0, controller.snapshot().activeGlobal)
    }

    @Test
    fun leaseReleaseIsIdempotent() {
        val controller = ExecutionAdmissionController(limits())
        val lease = controller.tryAcquire(profileId = 7L).lease
        assertNotNull(lease)
        lease?.release()
        lease?.release()
        assertEquals(0, controller.snapshot().activeGlobal)
        assertEquals(emptyMap<Long, Int>(), controller.snapshot().activeByProfile)
    }

    @Test
    fun repeatedPerProfileBurstsOpenAndPersistTheCircuit() {
        val clock = Clock()
        val store = InMemoryExecutionCircuitStore()
        val controller = ExecutionAdmissionController(limits(), clock::now, store)

        repeat(2) {
            val result = controller.tryAcquire(profileId = 7L)
            assertTrue(result.accepted)
            result.lease?.release()
        }
        val firstStrike = controller.tryAcquire(profileId = 7L)
        assertFalse(firstStrike.accepted)
        assertFalse(firstStrike.circuitOpened)

        val opened = controller.tryAcquire(profileId = 7L)
        assertFalse(opened.accepted)
        assertTrue(opened.circuitOpened)
        assertTrue(store.load(7L).openUntilMs > clock.now())

        val stillOpen = controller.tryAcquire(profileId = 7L)
        assertFalse(stillOpen.accepted)
        assertTrue(stillOpen.reason.orEmpty().contains("circuit"))
    }

    @Test
    fun anExpiredCircuitResetsStrikesAndAllowsAStableRun() {
        val clock = Clock()
        val controller = ExecutionAdmissionController(
            limits(burstWindowMs = 50L),
            clock::now,
            InMemoryExecutionCircuitStore(),
        )
        repeat(2) {
            controller.tryAcquire(7L).lease?.release()
        }
        controller.tryAcquire(7L)
        controller.tryAcquire(7L)
        clock.nowMs += 1_001L

        val recovered = controller.tryAcquire(7L)
        assertTrue(recovered.accepted)
        recovered.lease?.release()
        assertEquals(0, controller.snapshot().openCircuits.size)
    }

    @Test
    fun burstWindowPrunesWithoutAffectingIndependentProfiles() {
        val clock = Clock()
        val controller = ExecutionAdmissionController(
            limits(perProfileBurstLimit = 1, circuitTripCount = 3),
            clock::now,
            InMemoryExecutionCircuitStore(),
        )
        controller.tryAcquire(7L).lease?.release()
        assertFalse(controller.tryAcquire(7L).accepted)
        assertTrue(controller.tryAcquire(8L).accepted)
        clock.nowMs += 101L
        val afterWindow = controller.tryAcquire(7L)
        assertTrue(afterWindow.accepted)
        afterWindow.lease?.release()
    }

    @Test
    fun previewDoesNotReserveLeaseOrRecordBurst() {
        val controller = ExecutionAdmissionController(limits())
        val before = controller.snapshot()

        val preview = controller.preview(profileId = 7L)

        assertTrue(preview.accepted)
        assertEquals(before, controller.snapshot())
        assertEquals(null, preview.lease)
    }

    @Test
    fun previewReportsAnActiveProfileLimitWithoutChangingIt() {
        val controller = ExecutionAdmissionController(limits())
        val lease = controller.tryAcquire(profileId = 7L).lease
        assertNotNull(lease)

        val preview = controller.preview(profileId = 7L)

        assertFalse(preview.accepted)
        assertTrue(preview.reason.orEmpty().contains("Profile execution limit"))
        lease?.release()
    }

    @Test
    fun profileOverridesApplyToActiveAndBurstBudgetsAndExposeCounts() {
        val clock = Clock()
        val controller = ExecutionAdmissionController(
            limits(perProfileMaxActive = 2, perProfileBurstLimit = 6, circuitTripCount = 10),
            clock::now,
            InMemoryExecutionCircuitStore(),
        )
        val override = ExecutionAdmissionProfileLimits(maxActive = 1, burstLimit = 2)
        val first = controller.tryAcquire(profileId = 7L, profileLimits = override)
        assertTrue(first.accepted)

        val activeRejected = controller.tryAcquire(profileId = 7L, profileLimits = override)
        assertFalse(activeRejected.accepted)
        assertEquals(ExecutionAdmissionRejectionKind.PROFILE_ACTIVE, activeRejected.rejection?.kind)
        assertEquals(1, activeRejected.rejection?.counts?.activeProfile)
        assertTrue(activeRejected.reason.orEmpty().contains("active global=1/2"))
        first.lease?.release()

        repeat(1) {
            val admitted = controller.tryAcquire(profileId = 7L, profileLimits = override)
            assertTrue(admitted.accepted)
            admitted.lease?.release()
        }
        val burstRejected = controller.tryAcquire(profileId = 7L, profileLimits = override)
        assertFalse(burstRejected.accepted)
        assertEquals(ExecutionAdmissionRejectionKind.PROFILE_BURST, burstRejected.rejection?.kind)
        assertEquals(2, burstRejected.rejection?.counts?.profileBurst)
        assertTrue(burstRejected.reason.orEmpty().contains("profile=2/2"))
    }

    @Test
    fun circuitSnapshotIncludesTripReasonAndRemainingState() {
        val clock = Clock()
        val controller = ExecutionAdmissionController(
            limits(perProfileBurstLimit = 1, circuitTripCount = 2),
            clock::now,
            InMemoryExecutionCircuitStore(),
        )
        val first = controller.tryAcquire(profileId = 7L)
        assertTrue(first.accepted)
        first.lease?.release()
        controller.tryAcquire(profileId = 7L)
        val opened = controller.tryAcquire(profileId = 7L)

        assertTrue(opened.circuitOpened)
        assertEquals(ExecutionAdmissionRejectionKind.PROFILE_BURST, opened.rejection?.kind)
        val state = controller.snapshot().circuits[7L]
        assertNotNull(state)
        assertTrue(state!!.openUntilMs > clock.now())
        assertTrue(state.lastReason.orEmpty().contains("per-profile"))
        assertEquals(2, state.strikeCount)
    }
}
