package com.sysadmindoc.ruledeck.core.contexts

import com.opentasker.core.contexts.CompanionPresenceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android binds CompanionPresenceService only for an association the app asked it to watch, so a
 * `companion_presence` profile never fired. This pins how each Android version is asked.
 */
class CompanionPresenceObservationTest {

    // Before Android 13 an association is its Bluetooth address, which list() uses as id, label and address.
    private val legacy = CompanionAssociation("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF")
    private val current = CompanionAssociation("7", "Watch", "aa:bb:cc:dd:ee:ff")

    @Test
    fun androidElevenAndOlderHaveNothingToAsk() {
        assertNull(presenceObservation(26, legacy))
        assertNull(presenceObservation(30, legacy))
    }

    @Test
    fun androidTwelveToFifteenWatchTheBluetoothAddress() {
        assertEquals(PresenceObservation.ByAddress("AA:BB:CC:DD:EE:FF"), presenceObservation(31, legacy))
        assertEquals(PresenceObservation.ByAddress("AA:BB:CC:DD:EE:FF"), presenceObservation(32, legacy))
        assertEquals(PresenceObservation.ByAddress("aa:bb:cc:dd:ee:ff"), presenceObservation(33, current))
        assertEquals(PresenceObservation.ByAddress("aa:bb:cc:dd:ee:ff"), presenceObservation(35, current))
        // An association Android gave no address for can't be watched there.
        assertNull(presenceObservation(34, current.copy(address = null)))
        assertNull(presenceObservation(34, current.copy(address = "")))
    }

    @Test
    fun androidSixteenAndUpWatchTheAssociationId() {
        assertEquals(PresenceObservation.ByAssociationId(7), presenceObservation(36, current))
        assertEquals(PresenceObservation.ByAssociationId(7), presenceObservation(37, current))
        assertEquals(PresenceObservation.ByAssociationId(7), presenceObservation(36, current.copy(address = null)))
        assertNull(presenceObservation(36, legacy))
    }

    @Test
    fun manifestAsksToObserveCompanionPresence() {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() })
            .documentElement
        val permissions = manifest.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).mapNotNull { permissions.item(it).attributes.getNamedItem("android:name")?.nodeValue }
        assertTrue(declared.toString(), "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE" in declared)
    }
}
