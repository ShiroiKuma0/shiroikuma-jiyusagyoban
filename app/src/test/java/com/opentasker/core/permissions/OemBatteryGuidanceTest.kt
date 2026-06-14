package com.opentasker.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemBatteryGuidanceTest {

    @Test
    fun samsungIsMediumRiskWithSleepingAppsGuidance() {
        val g = OemBatteryGuidance.forDevice("samsung", "samsung")
        assertEquals(OemBatteryGuidance.RiskLevel.MEDIUM, g.riskLevel)
        assertTrue(g.needsExtraSteps)
        assertTrue(g.dontKillMyAppUrl.endsWith("/samsung"))
        assertTrue(g.steps.any { it.contains("sleep", ignoreCase = true) })
    }

    @Test
    fun xiaomiBrandsResolveToMiuiAutostart() {
        for (brand in listOf("Xiaomi", "Redmi", "POCO")) {
            val g = OemBatteryGuidance.forDevice(brand, brand)
            assertEquals("brand=$brand", OemBatteryGuidance.RiskLevel.HIGH, g.riskLevel)
            assertTrue(g.settingsTargets.any { it.className.contains("AutoStart") })
        }
    }

    @Test
    fun bbkFamilyIsSevereRisk() {
        for (brand in listOf("OPPO", "realme", "vivo", "iQOO")) {
            val g = OemBatteryGuidance.forDevice(brand, brand)
            assertEquals("brand=$brand", OemBatteryGuidance.RiskLevel.SEVERE, g.riskLevel)
            assertTrue(g.settingsTargets.isNotEmpty())
        }
    }

    @Test
    fun huaweiAndHonorShareEmuiGuidance() {
        val huawei = OemBatteryGuidance.forDevice("HUAWEI", "HUAWEI")
        val honor = OemBatteryGuidance.forDevice("HONOR", "HONOR")
        assertEquals(OemBatteryGuidance.RiskLevel.SEVERE, huawei.riskLevel)
        assertEquals(huawei.oemName, honor.oemName)
    }

    @Test
    fun pixelIsLowRiskAndNeedsNoExtraSteps() {
        val g = OemBatteryGuidance.forDevice("Google", "google")
        assertEquals(OemBatteryGuidance.RiskLevel.LOW, g.riskLevel)
        assertFalse(g.needsExtraSteps)
        assertTrue(g.settingsTargets.isEmpty())
    }

    @Test
    fun unknownOemFallsBackToGenericMediumGuidance() {
        val g = OemBatteryGuidance.forDevice("AcmePhone", "acme")
        assertEquals(OemBatteryGuidance.RiskLevel.MEDIUM, g.riskLevel)
        assertEquals("general", g.dontKillMyAppUrl.substringAfterLast('/'))
        // Unknown OEM name is preserved (capitalized) for the user.
        assertTrue(g.oemName.contains("Acme", ignoreCase = true))
    }

    @Test
    fun nullManufacturerDoesNotCrash() {
        val g = OemBatteryGuidance.forDevice(null, null)
        assertEquals(OemBatteryGuidance.RiskLevel.MEDIUM, g.riskLevel)
        assertTrue(g.steps.isNotEmpty())
    }
}
