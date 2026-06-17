package com.opentasker.core.contexts

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun balancedPolicyIncludesPassiveProvider() {
        val policy = LocationProviderRequestPolicy.balanced()
        val passive = policy.cadenceFor(LocationManager.PASSIVE_PROVIDER)
        assertTrue(passive.minTimeMs > 0)
        assertEquals(0f, passive.minDistanceMeters)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cadenceRejectsNonPositiveTime() {
        LocationProviderCadence(minTimeMs = 0L, minDistanceMeters = 100f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cadenceRejectsNegativeDistance() {
        LocationProviderCadence(minTimeMs = 60_000L, minDistanceMeters = -1f)
    }

    @Test
    fun registrationStateRemovesOldListenerBeforeReplacingProviders() {
        val removed = mutableListOf<LocationListener>()
        val state = LocationListenerRegistrationState { removed += it }
        val first = testListener()
        val second = testListener()

        state.replace(setOf(LocationManager.GPS_PROVIDER), first) { }
        state.replace(setOf(LocationManager.NETWORK_PROVIDER), second) {
            assertEquals(listOf(first), removed)
        }

        assertEquals(listOf(first), removed)
        assertFalse(state.isActiveFor(setOf(LocationManager.GPS_PROVIDER)))
        assertTrue(state.isActiveFor(setOf(LocationManager.NETWORK_PROVIDER)))
    }

    @Test
    fun registrationStateRemovesNewListenerWhenRegistrationFails() {
        val removed = mutableListOf<LocationListener>()
        val state = LocationListenerRegistrationState { removed += it }
        val listener = testListener()

        try {
            state.replace(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER), listener) {
                throw IllegalStateException("network provider rejected listener")
            }
            fail("Expected registration failure")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(listener), removed)
            assertFalse(state.isActiveFor(setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)))
        }
    }

    private fun testListener(): LocationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = Unit
        }
}
