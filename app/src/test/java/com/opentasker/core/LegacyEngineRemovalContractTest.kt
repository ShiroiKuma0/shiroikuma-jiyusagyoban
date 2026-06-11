package com.opentasker.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyEngineRemovalContractTest {
    @Test
    fun appStartupDoesNotConstructLegacyEngineOrDatabase() {
        val source = existingRepoFile("src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt").readText()

        assertFalse("Startup must not construct the legacy engine", "AutomationEngine" in source)
        assertFalse("Startup must not open the legacy automation database", "AutomationDatabase" in source)
        assertFalse("Startup must not register shell execution", "ShellAction" in source)
    }

    @Test
    fun deadLegacyPackagesAndManifestReceiversAreAbsent() {
        assertMissing("src/main/java/com/opentasker/automation/core")
        assertMissing("src/main/java/com/opentasker/automation/data")
        assertMissing("src/main/java/com/opentasker/automation/action/impl/ShellAction.kt")

        val manifest = existingRepoFile("src/main/AndroidManifest.xml").readText()
        assertFalse("Manifest must not keep the dead battery receiver", "BatteryEventReceiver" in manifest)
        assertFalse("Manifest must not keep the legacy geofence receiver", "GeofenceEventReceiver" in manifest)
    }

    private fun existingRepoFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun assertMissing(path: String) {
        listOf(File(path), File("app/$path")).forEach { file ->
            assertFalse("${file.path} should be absent", file.exists())
        }
    }
}
