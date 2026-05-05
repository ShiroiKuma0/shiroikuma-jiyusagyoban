package com.opentasker.core.contexts

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationProviderRequestPolicyTest {
    @Test
    fun balancedPolicyRequestsGpsLessOftenThanNetwork() {
        val policy = LocationProviderRequestPolicy.balanced()

        val gps = policy.cadenceFor(LocationManager.GPS_PROVIDER)
        val network = policy.cadenceFor(LocationManager.NETWORK_PROVIDER)

        assertTrue(gps.minTimeMs > network.minTimeMs)
        assertTrue(gps.minDistanceMeters < network.minDistanceMeters)
        assertEquals("gps:180000ms/100m", gps.toMetadata(LocationManager.GPS_PROVIDER))
        assertEquals("network:90000ms/150m", network.toMetadata(LocationManager.NETWORK_PROVIDER))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cadenceRejectsNonPositiveTime() {
        LocationProviderCadence(minTimeMs = 0L, minDistanceMeters = 100f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cadenceRejectsNegativeDistance() {
        LocationProviderCadence(minTimeMs = 60_000L, minDistanceMeters = -1f)
    }
}
