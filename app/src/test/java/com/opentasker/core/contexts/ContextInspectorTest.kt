package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextInspectorTest {
    @Test
    fun profileInspectionMatchesWhenAllContextsMatchLatestValues() {
        val profile = Profile(
            id = 1,
            name = "Work hours",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(ContextType.TIME, mapOf("start" to "09:00", "end" to "17:00")),
                ContextSpec(ContextType.DAY, mapOf("days" to "MON,WED,FRI")),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "time",
            label = "Time and day",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("time", true, mapOf("time" to "10:30", "day" to "MON")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertTrue(result.matching)
        assertEquals("All contexts currently match.", result.summary)
        assertEquals(listOf(true, true), result.contexts.map { it.effectiveMatched })
    }

    @Test
    fun profileInspectionExplainsMissingSourceEvents() {
        val profile = Profile(
            id = 2,
            name = "Foreground app",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example"))),
        )
        val source = ContextSourceSnapshot(
            key = "app",
            label = "Application",
            registered = true,
            setupReady = true,
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertFalse(result.matching)
        assertEquals("Waiting for the first Application event.", result.summary)
        assertEquals(ContextSourceStatus.Waiting, result.contexts.single().sourceStatus)
    }

    @Test
    fun profileInspectionAppliesInvertedContextReasoning() {
        val profile = Profile(
            id = 3,
            name = "Not charging",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(
                ContextSpec(
                    type = ContextType.STATE,
                    config = mapOf("key" to "charging", "value" to "true"),
                    invert = true,
                ),
            ),
        )
        val source = ContextSourceSnapshot(
            key = "state",
            label = "Device state",
            registered = true,
            lastObservation = ContextEventObservation(
                ContextEvent("state", true, mapOf("charging" to "false")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertTrue(result.matching)
        assertTrue(result.contexts.single().effectiveMatched)
        assertEquals(
            "Latest value does not satisfy the configuration, so the inverted context matches.",
            result.contexts.single().reason,
        )
    }

    @Test
    fun sourceSnapshotPrioritizesSetupAndRegistrationHealth() {
        val setupRequired = ContextSourceSnapshot(
            key = "app",
            label = "Application",
            registered = true,
            setupReady = false,
            setupDetail = "Usage access is missing.",
        )
        val missing = ContextSourceSnapshot(
            key = "location",
            label = "Location",
            registered = false,
            setupReady = false,
        )

        assertEquals(ContextSourceStatus.NeedsSetup, setupRequired.status)
        assertEquals(ContextSourceStatus.Missing, missing.status)
    }

    @Test
    fun setupRequiredSourceDoesNotCountAsMatchingProfile() {
        val profile = Profile(
            id = 4,
            name = "Blocked app",
            enabled = true,
            enterTaskId = 10,
            contexts = listOf(ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example"))),
        )
        val source = ContextSourceSnapshot(
            key = "app",
            label = "Application",
            registered = true,
            setupReady = false,
            setupDetail = "Usage access is missing.",
            lastObservation = ContextEventObservation(
                ContextEvent("app", true, mapOf("foreground" to "com.example")),
                observedAtMs = 1000,
            ),
        )

        val result = inspectProfiles(listOf(profile), listOf(source)).single()

        assertFalse(result.matching)
        assertFalse(result.contexts.single().effectiveMatched)
        assertEquals("Usage access is missing.", result.summary)
    }
}
