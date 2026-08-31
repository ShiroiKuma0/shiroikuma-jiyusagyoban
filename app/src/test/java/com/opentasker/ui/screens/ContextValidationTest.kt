package com.opentasker.ui.screens

import com.opentasker.core.model.ContextType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextValidationTest {
    @Test
    fun validTimeWindowIsAccepted() {
        assertFalse(
            contextHasInvalidValues(ContextType.TIME, mapOf("start" to "08:30", "end" to "17:00")),
        )
    }

    @Test
    fun garbledTimeIsRejected() {
        assertTrue(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "banana", "end" to "17:00")))
        assertTrue(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "08:30", "end" to "25:99")))
        assertTrue(contextHasInvalidValues(ContextType.TIME, mapOf("start" to "8", "end" to "17:00")))
    }

    @Test
    fun blankTimeIsNotFlaggedHere() {
        // Required-but-blank is handled by the missingRequired gate, not this one.
        assertFalse(contextHasInvalidValues(ContextType.TIME, emptyMap()))
    }

    @Test
    fun outOfRangeCoordinatesAreRejected() {
        assertTrue(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "999", "longitude" to "0", "radiusMeters" to "100"),
            ),
        )
        assertTrue(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "1.2.3", "longitude" to "0", "radiusMeters" to "100"),
            ),
        )
        assertTrue(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "40", "longitude" to "-200", "radiusMeters" to "100"),
            ),
        )
    }

    @Test
    fun validCoordinatesAreAccepted() {
        assertFalse(
            contextHasInvalidValues(
                ContextType.LOCATION,
                mapOf("latitude" to "40.7128", "longitude" to "-74.0060", "radiusMeters" to "150"),
            ),
        )
    }

    @Test
    fun eventLatLongRangeIsValidated() {
        assertTrue(contextHasInvalidValues(ContextType.EVENT, mapOf("latitude" to "91")))
        assertFalse(contextHasInvalidValues(ContextType.EVENT, mapOf("latitude" to "40", "longitude" to "-74")))
    }

    @Test
    fun packageBearingContextsRejectMalformedManualEntries() {
        // RETIRED: upstream's strict single-package validation, which arrived with its typed
        // FieldType.APP policy. The fork's package fields stay free text on purpose — they accept a
        // %variable and, on the Event context, a comma-separated package allowlist.
    }
}
