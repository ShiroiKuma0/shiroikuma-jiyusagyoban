package com.opentasker.core.contexts

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Android 8 to 12 refuse every CompanionDeviceManager call from an app that doesn't declare the
 * companion_device_setup feature, on any device that has the feature (Galaxy phones do, the stock
 * emulator doesn't), by throwing IllegalStateException. Setup and Settings list paired devices on
 * open, so without this declaration both crashed on a Galaxy S9 running Android 10 (issue #20).
 */
class CompanionFeatureManifestContractTest {
    @Test
    fun manifestDeclaresTheCompanionFeatureWithoutRequiringIt() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() })
            .documentElement
        val features = manifest.getElementsByTagName("uses-feature")
        val companion = (0 until features.length).map { features.item(it).attributes }
            .firstOrNull { it.getNamedItem("android:name")?.nodeValue == "android.software.companion_device_setup" }

        assertNotNull("uses-feature android.software.companion_device_setup is missing (issue #20)", companion)
        assertEquals(
            "a required feature would hide the app from devices without it",
            "false",
            companion!!.getNamedItem("android:required")?.nodeValue,
        )
    }
}
