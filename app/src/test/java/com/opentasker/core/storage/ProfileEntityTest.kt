package com.opentasker.core.storage

import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileEntityTest {
    @Test
    fun profileEntityRoundTripPreservesAutomationMode() {
        val profile = Profile(
            id = 7,
            name = "Queued profile",
            enterTaskId = 42,
            automationMode = AutomationMode.QUEUED,
        )

        assertEquals(AutomationMode.QUEUED, profile.toEntity().toDomain().automationMode)
    }

    @Test
    fun unknownAutomationModeFallsBackToSingle() {
        val entity = ProfileEntity(
            id = 1,
            name = "Legacy profile",
            enabled = true,
            enterTaskId = 2,
            exitTaskId = null,
            cooldownSec = 0,
            contextsJson = "[]",
            automationMode = "UNKNOWN",
        )

        assertEquals(AutomationMode.SINGLE, entity.toDomain().automationMode)
    }
}
