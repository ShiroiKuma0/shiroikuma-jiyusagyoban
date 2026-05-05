package com.opentasker.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDwellStateTrackerTest {
    private val config = mapOf(
        "latitude" to "40.7580",
        "longitude" to "-73.9855",
        "radiusMeters" to "150",
        "dwellSeconds" to "60",
    )

    @Test
    fun firstInsideSamplePersistsObservedTimeAsInsideSince() {
        val update = LocationDwellStateTracker.apply(
            config = config,
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "observedAtEpochMs" to "100000",
            ),
            existingInsideSinceEpochMs = null,
            nowEpochMs = 100_500L,
        )

        assertEquals("100000", update.metadata["insideSinceEpochMs"])
        assertEquals("inside", update.metadata["dwellState"])
        assertEquals(LocationDwellPersistence.Persist(100_000L), update.persistence)
    }

    @Test
    fun laterInsideSampleKeepsPersistedInsideSinceForDwell() {
        val update = LocationDwellStateTracker.apply(
            config = config,
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "observedAtEpochMs" to "160000",
            ),
            existingInsideSinceEpochMs = 100_000L,
            nowEpochMs = 160_000L,
        )

        assertEquals("100000", update.metadata["insideSinceEpochMs"])
        assertEquals(LocationDwellPersistence.Persist(100_000L), update.persistence)
    }

    @Test
    fun outsideSampleClearsPersistedInsideState() {
        val update = LocationDwellStateTracker.apply(
            config = config,
            metadata = mapOf(
                "latitude" to "40.7700",
                "longitude" to "-73.9700",
                "observedAtEpochMs" to "200000",
                "insideSinceEpochMs" to "100000",
            ),
            existingInsideSinceEpochMs = 100_000L,
            nowEpochMs = 200_000L,
        )

        assertFalse("inside-since metadata should be removed outside the radius", "insideSinceEpochMs" in update.metadata)
        assertEquals("outside", update.metadata["dwellState"])
        assertEquals(LocationDwellPersistence.Clear, update.persistence)
    }

    @Test
    fun lowAccuracySampleKeepsExistingStateButDoesNotPersistNewState() {
        val update = LocationDwellStateTracker.apply(
            config = config + ("maxAccuracyMeters" to "25"),
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "accuracyMeters" to "75",
                "observedAtEpochMs" to "150000",
            ),
            existingInsideSinceEpochMs = 100_000L,
            nowEpochMs = 150_000L,
        )

        assertEquals("100000", update.metadata["insideSinceEpochMs"])
        assertEquals("accuracy_blocked", update.metadata["dwellState"])
        assertEquals(LocationDwellPersistence.Keep, update.persistence)
    }

    @Test
    fun storageKeyChangesWhenGeofenceConfigChanges() {
        val first = LocationDwellStateKey.from(7, 1, config)
        val sameDifferentOrder = LocationDwellStateKey.from(7, 1, config.toList().reversed().toMap())
        val changedRadius = LocationDwellStateKey.from(7, 1, config + ("radiusMeters" to "250"))
        val profilePrefix = LocationDwellStateKey.profilePrefix(7)

        assertEquals(first, sameDifferentOrder)
        assertEquals("profile:7:", profilePrefix)
        assertTrue(first.storageKey.startsWith("${profilePrefix}context:1:"))
        assertTrue(first.storageKey.length > "profile:7:context:1:".length)
        assertFalse(first == changedRadius)
    }
}
