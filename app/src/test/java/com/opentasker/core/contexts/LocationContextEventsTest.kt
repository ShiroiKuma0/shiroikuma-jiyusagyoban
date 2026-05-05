package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationContextEventsTest {
    @Test
    fun locationEventMapsSampleMetadataForGeofenceMatching() {
        val event = LocationContextEvents.location(
            PlatformLocationSample(
                latitude = 40.7581,
                longitude = -73.9856,
                accuracyMeters = 18.5,
                observedAtEpochMs = 1_777_000_000L,
                provider = "gps",
            ),
        )

        assertEquals("location", event.type)
        assertTrue(event.matched)
        assertEquals("40.7581", event.metadata["latitude"])
        assertEquals("-73.9856", event.metadata["longitude"])
        assertEquals("18.5", event.metadata["accuracyMeters"])
        assertEquals("1777000000", event.metadata["observedAtEpochMs"])
        assertEquals("gps", event.metadata["provider"])
    }

    @Test
    fun setupBlockedEventFailsClosedWithReasonMetadata() {
        val event = LocationContextEvents.setupBlocked(
            reason = "missing_location_permission",
            detail = "Foreground location permission is required.",
        )

        assertEquals("location", event.type)
        assertFalse(event.matched)
        assertEquals("missing_location_permission", event.metadata["setup"])
        assertEquals("Foreground location permission is required.", event.metadata["reason"])
    }
}
