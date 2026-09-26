package com.opentasker.automation.network

import com.opentasker.automation.MonitorLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WiFiNetworkMonitorTest {
    @Test
    fun readableSsidRemovesPlatformQuotes() {
        assertEquals("OfficeWiFi", WiFiNetworkMonitor.readableSsid("\"OfficeWiFi\""))
    }

    @Test
    fun readableSsidTreatsTheWithheldPlaceholderAsNoName() {
        // Android 12+ puts this in every WifiInfo whose name it withholds. Reading it as a name
        // is what latched the state to Unknown (issue #17).
        assertNull(WiFiNetworkMonitor.readableSsid("<unknown ssid>"))
        assertNull(WiFiNetworkMonitor.readableSsid("  "))
        assertNull(WiFiNetworkMonitor.readableSsid(null))
    }

    @Test
    fun aRedactedCapabilityUpdateKeepsTheNameAlreadyKnownForThatNetwork() {
        val tracker = WifiConnectionTracker<String>()

        tracker.onAvailable("wlan-1")
        assertEquals(WifiSnapshot(connected = true, ssid = "Home"), tracker.onCapabilities("wlan-1", "Home"))

        // Signal-strength and validation updates keep coming, some with the name withheld.
        assertEquals(WifiSnapshot(connected = true, ssid = "Home"), tracker.onCapabilities("wlan-1", null))
        assertEquals(WifiSnapshot(connected = true, ssid = "Home"), tracker.onCapabilities("wlan-1", null))
    }

    @Test
    fun losingTheOnlyWifiNetworkReportsDisconnectedWhateverTheDefaultNetworkSays() {
        // The old monitor re-read activeNetwork at onLost; while that still named the departing
        // network the disconnect was suppressed and never retried. The tracker only knows the
        // network the callback named.
        val tracker = WifiConnectionTracker<String>()
        tracker.onAvailable("wlan-1")
        tracker.onCapabilities("wlan-1", "Home")

        assertEquals(WifiSnapshot(connected = false, ssid = null), tracker.onLost("wlan-1"))
    }

    @Test
    fun reconnectingStartsNamelessAndPicksUpTheNameFromTheNextUpdate() {
        val tracker = WifiConnectionTracker<String>()
        tracker.onAvailable("wlan-1")
        tracker.onCapabilities("wlan-1", "Home")
        tracker.onLost("wlan-1")

        tracker.onAvailable("wlan-2")
        assertEquals(WifiSnapshot(connected = true, ssid = null), tracker.onCapabilities("wlan-2", null))
        assertEquals(WifiSnapshot(connected = true, ssid = "Home"), tracker.onCapabilities("wlan-2", "Home"))
    }

    @Test
    fun theNameFollowsTheNewerNetworkAcrossAnOverlappingHandover() {
        val tracker = WifiConnectionTracker<String>()
        tracker.onAvailable("wlan-1")
        tracker.onCapabilities("wlan-1", "Home")
        tracker.onAvailable("wlan-2")
        assertEquals(WifiSnapshot(connected = true, ssid = "Cafe"), tracker.onCapabilities("wlan-2", "Cafe"))

        // A late update for the older network must not pull the name back.
        assertEquals(WifiSnapshot(connected = true, ssid = "Cafe"), tracker.onCapabilities("wlan-1", "Home"))
        assertEquals(WifiSnapshot(connected = true, ssid = "Cafe"), tracker.onLost("wlan-1"))
        assertEquals(WifiSnapshot(connected = false, ssid = null), tracker.onLost("wlan-2"))
    }

    @Test
    fun theDisconnectedSeedNeverOverridesACallbackThatAlreadyArrived() {
        val fresh = WifiConnectionTracker<String>()
        assertEquals(WifiSnapshot(connected = false, ssid = null), fresh.seedDisconnected())

        val raced = WifiConnectionTracker<String>()
        raced.onAvailable("wlan-1")
        raced.onCapabilities("wlan-1", "Home")
        assertNull(raced.seedDisconnected())
        assertEquals(WifiSnapshot(connected = true, ssid = "Home"), raced.snapshot())
    }

    @Test
    fun resetForgetsNetworksFromThePreviousRegistration() {
        val tracker = WifiConnectionTracker<String>()
        tracker.onAvailable("wlan-1")
        tracker.onCapabilities("wlan-1", "Home")

        tracker.reset()

        assertEquals(WifiSnapshot(connected = false, ssid = null), tracker.snapshot())
        assertEquals(WifiSnapshot(connected = false, ssid = null), tracker.seedDisconnected())
    }

    /**
     * The callback cannot be driven on the JVM (Network and NetworkCapabilities are framework
     * stubs), so the wiring the tracker tests rely on is pinned here: dropping the location flag or
     * the publish in onLost would otherwise leave every behavioural test green.
     */
    @Test
    fun theCallbackAsksForLocationInfoAndPublishesEveryTransitionThroughTheTracker() {
        val source = com.opentasker.ProductionSources.read("com/opentasker/automation/network/WiFiNetworkMonitor.kt")

        assertTrue(
            "API 31+ callbacks must ask for location info or every SSID arrives redacted",
            "WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)" in source,
        )
        assertTrue("synchronized(stateLock) { publish(tracker.onLost(network)) }" in source)
        assertTrue("synchronized(stateLock) { publish(tracker.onCapabilities(network, ssid)) }" in source)
        assertFalse(
            "state must come from the networks the callback named, never the default network",
            // A property access, so the KDoc that explains why may still name it.
            ".activeNetwork" in source,
        )
        // A withheld name is re-read when Location changes or the user returns to the app, and
        // both triggers are removed again on stop.
        assertTrue("IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)" in source)
        assertTrue("AppVisibilityTracker.addForegroundListener(foregroundListener)" in source)
        assertTrue("AppVisibilityTracker.removeForegroundListener(foregroundListener)" in source)
        assertTrue("appContext.unregisterReceiver(locationModeReceiver)" in source)
    }

    @Test
    fun failedCallbackRegistrationCanRetryAfterPermissionOrPlatformRecovery() {
        val lifecycle = MonitorLifecycle()
        var attempt = 0

        assertEquals(false, lifecycle.start { attempt++; attempt > 1 })
        assertTrue(lifecycle.start { attempt++; attempt > 1 })
        assertEquals(2, attempt)
    }
}
