package com.opentasker.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the external-trigger hardening: exported receivers that unparcel and re-dispatch trigger
 * intents must enforce their intent filters (blocking mismatched-action redirection), and debug
 * builds must enable StrictMode's unsafe-intent-launch detection.
 */
class ExportedReceiverHardeningTest {
    private val hardenedReceivers = setOf(
        "com.opentasker.core.external.AutomationTargetReceiver",
        "com.opentasker.core.plugins.locale.LocaleSettingFireReceiver",
        "com.opentasker.core.plugins.locale.LocaleConditionQueryReceiver",
    )

    private fun manifestRoot(): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
                    .first { it.exists() },
            )
            .documentElement

    @Test
    fun triggerReceiversEnforceTheirIntentFilters() {
        val receivers = manifestRoot().getElementsByTagName("receiver")
        val checked = mutableSetOf<String>()
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as Element
            val name = receiver.getAttribute("android:name")
            if (name !in hardenedReceivers) continue
            checked += name
            assertEquals(
                "Exported trigger receiver $name must enforce its intent filter",
                "enforceIntentFilter",
                receiver.getAttribute("android:intentMatchingFlags"),
            )
            assertEquals("Trigger receiver $name must remain exported", "true", receiver.getAttribute("android:exported"))
        }
        assertEquals("Every hardened receiver must be present in the manifest", hardenedReceivers, checked)
    }

    @Test
    fun debugBuildsDetectUnsafeIntentLaunch() {
        val source = listOf(
            File("src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt"),
            File("app/src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt"),
        ).first { it.exists() }.readText()

        assertTrue(
            "Debug StrictMode must enable detectUnsafeIntentLaunch()",
            source.contains("detectUnsafeIntentLaunch()"),
        )
    }
}
