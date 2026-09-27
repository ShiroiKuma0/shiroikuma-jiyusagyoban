package com.opentasker.ui

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup's location row asked for fine location on its own, which Android 12+ ignores unless coarse
 * is requested in the same dialog, so the button silently did nothing on current phones. It also
 * counted approximate location as ready, while a WiFi name and a small geofence both need precise,
 * so the row turned green and the Inspector still said "allow precise location" (upstream #17).
 *
 * Upstream's version of this gate reads `SetupRows.kt`, the row catalogue it split out of the Setup
 * screen. This fork rewrote that screen and keeps its rows inline, so the same assertions are
 * pointed at `PermissionOnboardingScreen.kt`, where the fork's location row actually lives.
 */
class SetupLocationRequestContractTest {
    @Test
    fun theLocationRowAsksForPreciseLocationTheWayAndroidAccepts() {
        val row = ProductionSources.block(
            "com/opentasker/ui/screens/PermissionOnboardingScreen.kt",
            "title = \"Foreground location\"",
            "title = \"Nearby WiFi devices\"",
        )

        assertTrue(
            "ready must mean precise location",
            "granted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)," in row,
        )
        assertTrue(
            "coarse must be requested in the same dialog as fine",
            "requestedWith = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)," in row,
        )
        assertTrue(
            "approximate-only access explains what is missing",
            "LocationPolicyDisclosures.foregroundSetupBodyApproximate" in row,
        )
        assertFalse("hasAnyLocationPermission" in row)
    }

    @Test
    fun theInspectorCallsLocationReadyOnWhatSetupCallsReady() {
        val inspector = ProductionSources.read("com/opentasker/ui/screens/ContextInspectorScreen.kt")

        assertTrue(
            "the Inspector marked approximate location ready while Setup's row said it was missing",
            "ready = LocationPolicyDisclosures.sourceReady(precise = precise, providerEnabled = providerEnabled)," in inspector,
        )
    }

    /**
     * RETIRED upstream half: `val granted = results[permission] == true`. Upstream's callback
     * resolves one pending permission and reports on it; the fork's Setup screen refreshes every row
     * from the system on each result and on every ON_RESUME, so it keeps no pending permission to
     * report on. What still has to hold is that the companion permission reaches the same dialog.
     */
    @Test
    fun theSetupScreenLaunchesEveryRequestedPermissionTogether() {
        val screen = ProductionSources.read("com/opentasker/ui/screens/PermissionOnboardingScreen.kt")

        assertTrue(screen.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue(
            screen.contains(
                "permissionLauncher.launch((listOf(action.permission) + action.requestedWith).toTypedArray())",
            ),
        )
    }
}
