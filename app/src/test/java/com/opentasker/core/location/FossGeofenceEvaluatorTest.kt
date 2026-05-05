package com.opentasker.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FossGeofenceEvaluatorTest {
    @Test
    fun evaluatesDistanceAgainstRadius() {
        val spec = FossGeofenceSpec(
            center = GeoPoint(40.7580, -73.9855),
            radiusMeters = 150.0,
        )

        val inside = FossGeofenceEvaluator.evaluate(spec, LocationSample(GeoPoint(40.7581, -73.9856)))
        val outside = FossGeofenceEvaluator.evaluate(spec, LocationSample(GeoPoint(40.7600, -73.9800)))

        assertTrue(inside.matches)
        assertFalse(outside.matches)
        assertEquals(GeofenceBlockReason.OutsideRadius, outside.blockReason)
    }

    @Test
    fun rejectsLowAccuracyWhenMaxAccuracyIsConfigured() {
        val spec = FossGeofenceSpec(
            center = GeoPoint(40.7580, -73.9855),
            radiusMeters = 150.0,
            maxAccuracyMeters = 50.0,
        )

        val inaccurate = FossGeofenceEvaluator.evaluate(
            spec,
            LocationSample(GeoPoint(40.7581, -73.9856), accuracyMeters = 75.0),
        )
        val accurate = FossGeofenceEvaluator.evaluate(
            spec,
            LocationSample(GeoPoint(40.7581, -73.9856), accuracyMeters = 25.0),
        )

        assertFalse(inaccurate.matches)
        assertEquals(GeofenceBlockReason.AccuracyTooLow, inaccurate.blockReason)
        assertTrue(accurate.matches)
    }

    @Test
    fun dwellRequiresInsideDurationMetadata() {
        val spec = FossGeofenceSpec(
            center = GeoPoint(40.7580, -73.9855),
            radiusMeters = 150.0,
            dwellMillis = 300_000L,
        )

        val tooSoon = FossGeofenceEvaluator.evaluate(
            spec,
            LocationSample(
                point = GeoPoint(40.7581, -73.9856),
                observedAtEpochMs = 1_000_000L,
                insideSinceEpochMs = 800_001L,
            ),
        )
        val satisfied = FossGeofenceEvaluator.evaluate(
            spec,
            LocationSample(
                point = GeoPoint(40.7581, -73.9856),
                observedAtEpochMs = 1_000_000L,
                insideSinceEpochMs = 700_000L,
            ),
        )

        assertFalse(tooSoon.matches)
        assertEquals(GeofenceBlockReason.DwellNotSatisfied, tooSoon.blockReason)
        assertTrue(satisfied.matches)
    }

    @Test
    fun mapEvaluatorParsesAliasesAndDwellSeconds() {
        val result = FossGeofenceEvaluator.evaluate(
            config = mapOf(
                "lat" to "40.7580",
                "lng" to "-73.9855",
                "radius" to "150",
                "maxAccuracy" to "30",
                "dwellSeconds" to "60",
            ),
            metadata = mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "accuracyMeters" to "20",
                "timestampEpochMs" to "120000",
                "enteredAtEpochMs" to "0",
            ),
        )

        assertTrue(result?.matches == true)
    }
}
