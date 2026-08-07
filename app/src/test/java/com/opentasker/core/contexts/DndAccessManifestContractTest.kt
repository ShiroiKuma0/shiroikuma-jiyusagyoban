package com.opentasker.core.contexts

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Modes access / Do Not Disturb access settings page only lists apps that declare
 * ACCESS_NOTIFICATION_POLICY. Without the declaration the Setup row sends the user to a
 * page that can never grant anything and isNotificationPolicyAccessGranted() can never
 * become true (issue #4).
 */
class DndAccessManifestContractTest {
    @Test
    fun manifestDeclaresNotificationPolicyAccessPermission() {
        val manifest = loadMainManifest()
        val permissions = manifest.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length)
            .asSequence()
            .mapNotNull { permissions.item(it).attributes.getNamedItem("android:name")?.nodeValue }

        assertTrue(
            "manifest must declare android.permission.ACCESS_NOTIFICATION_POLICY so the app " +
                "appears on the Do Not Disturb / Modes access settings page",
            "android.permission.ACCESS_NOTIFICATION_POLICY" in declared,
        )
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
