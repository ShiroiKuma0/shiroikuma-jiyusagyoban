package com.opentasker.core.location

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocationForegroundServiceContractTest {
    @Test
    fun manifestDeclaresLocationForegroundServiceContract() {
        val manifest = loadMainManifest()
        val permissions = manifest.getElementsByTagName("uses-permission")
        val permissionNames = (0 until permissions.length)
            .asSequence()
            .mapNotNull { permissions.item(it).attributes.getNamedItem("android:name")?.nodeValue }
            .toSet()

        assertTrue("manifest must request foreground service location permission", "android.permission.FOREGROUND_SERVICE_LOCATION" in permissionNames)
        assertTrue("manifest must request coarse location for approximate fixes", "android.permission.ACCESS_COARSE_LOCATION" in permissionNames)
        assertTrue("manifest must request fine location for precise geofence matching", "android.permission.ACCESS_FINE_LOCATION" in permissionNames)

        val services = manifest.getElementsByTagName("service")
        val automationService = (0 until services.length)
            .asSequence()
            .map { services.item(it) }
            .first { it.attributes.getNamedItem("android:name")?.nodeValue == "com.opentasker.core.engine.AutomationService" }
        val serviceTypes = automationService.attributes.getNamedItem("android:foregroundServiceType")?.nodeValue.orEmpty()

        assertTrue("automation foreground service must retain specialUse type", "specialUse" in serviceTypes.split("|"))
        assertTrue("automation foreground service must declare location type", "location" in serviceTypes.split("|"))
    }

    private fun loadMainManifest() =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(
                    File("src/main/AndroidManifest.xml"),
                    File("app/src/main/AndroidManifest.xml"),
                ).first { it.exists() },
            )
            .documentElement
}
