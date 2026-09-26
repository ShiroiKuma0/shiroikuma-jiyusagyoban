package com.opentasker.ui

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Setup's location row asked for fine location on its own, which Android 12+ ignores unless coarse
 * is requested in the same dialog, so the button silently did nothing on current phones. It also
 * counted approximate location as ready, while a Wi-Fi name and a small geofence both need precise,
 * so the row turned green and the Inspector still said "allow precise location" (issue #17 review).
 */
class SetupLocationRequestContractTest {
    @Test
    fun theLocationRowAsksForPreciseLocationTheWayAndroidAccepts() {
        val row = ProductionSources.block(
            "com/opentasker/ui/screens/SetupRows.kt",
            "R.string.setup_foreground_location_title",
            "requirements = setOf(SetupRequirement.FOREGROUND_LOCATION)",
        )

        assertTrue(
            "ready must mean precise location",
            "granted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)," in row,
        )
        assertTrue(
            "coarse must be requested in the same dialog as fine",
            "requestedWith = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)" in row,
        )
        assertTrue(
            "approximate-only access explains what is missing",
            "R.string.setup_foreground_location_body_approximate" in row,
        )
        assertFalse("hasAnyLocationPermission" in row)
    }

    @Test
    fun theSetupScreenLaunchesEveryRequestedPermissionTogether() {
        val screen = ProductionSources.read("com/opentasker/ui/screens/PermissionOnboardingScreen.kt")

        assertTrue(screen.contains("ActivityResultContracts.RequestMultiplePermissions()"))
        assertTrue(screen.contains("permissionLauncher.launch((listOf(action.permission) + action.requestedWith).toTypedArray())"))
        assertTrue("the primary permission still decides the outcome", screen.contains("val granted = results[permission] == true"))
    }
}
