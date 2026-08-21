package com.opentasker.core.actions

import com.opentasker.ProductionSources
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanActionTest {
    private val source: String by lazy {
        ProductionSources.read("com/opentasker/core/actions/WifiScanAction.kt")
    }

    @Test
    fun theActionIsRegisteredAndDeclaresItsSetup() {
        assertNotNull("wifi.scan must be in the catalog", ActionCatalog.get("wifi.scan"))
        assertEquals(
            "Scanning needs permissions the user grants, so it is not simply Supported",
            CapabilityLevel.RequiresSetup,
            ActionCapabilityRegistry.get("wifi.scan").level,
        )
    }

    @Test
    fun theResultListIsCountCapped() {
        assertEquals(20, WifiScanAction.DEFAULT_RESULTS)
        assertEquals(64, WifiScanAction.MAX_RESULTS)
        assertTrue("The default must sit inside the cap", WifiScanAction.DEFAULT_RESULTS <= WifiScanAction.MAX_RESULTS)
        assertTrue(
            "A caller-supplied limit must be clamped, not trusted",
            "coerceIn(1, MAX_RESULTS)" in source,
        )
        assertTrue("Each field must be length-bounded too", "take(MAX_FIELD_CHARS)" in source)
    }

    @Test
    fun bssidsAreTreatedAsSensitive() {
        assertTrue(
            "A BSSID identifies a physical access point and must be masked where variables are shown",
            "setArray(\"\${varName}_bssid\", bssids, sensitive = true)" in source,
        )
        assertTrue(
            "The run log must not carry the list of networks a device can see",
            "access point(s), new scan requested" in source,
        )
        listOf("ssids", "bssids").forEach { field ->
            assertTrue(
                "The log line must not interpolate $field",
                "\$$field" !in source.substringAfter("ctx.logger("),
            )
        }
    }

    @Test
    fun itFailsClosedRatherThanReportingAnEmptyScan() {
        listOf(
            "before it can read scan results",
            "could not reach the Wi-Fi service",
            "needs Wi-Fi to be on",
            "could not read results",
        ).forEach { message ->
            assertTrue("A refusal must be a Failure carrying '$message'", message in source)
        }
        assertTrue("Missing permissions must be reported by name", "missingPermissions(ctx.app)" in source)
        assertTrue("Nearby Wi-Fi devices is required from Android 13", "NEARBY_WIFI_DEVICES" in source)
    }

    @Test
    fun throttlingIsReportedRatherThanHidden() {
        assertTrue(
            "The caller must be able to tell whether a fresh scan was even accepted",
            "_scan_requested" in source,
        )
        assertTrue(
            "The caller must be able to tell how old the cached scan is",
            "_age_ms" in source && "newestAgeMillis(" in source,
        )
        assertTrue(
            "A refused startScan must not fail the action, because the cache is still usable",
            "getOrDefault(false)" in source,
        )

        val capability = ActionCapabilityRegistry.get("wifi.scan").reason
        assertTrue("The capability text must say results may be cached", "cached" in capability)
    }
}
