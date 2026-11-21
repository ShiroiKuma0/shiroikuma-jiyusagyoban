package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A paired phone with its screen off usually advertises nothing, so a scan never sees it (#25).
 * Paging it, a connection attempt to its paired address, does: a phone in range answers and the
 * link that comes up is a sighting.
 */
class BluetoothNearPagingTest {
    private val partner = BondedBluetoothDevice(name = "Sam's Phone", address = "aa:bb:cc:dd:ee:01", pageable = true)
    private val watch = BondedBluetoothDevice(name = "Band", address = "AA:BB:CC:DD:EE:02", pageable = false)
    private val car = BondedBluetoothDevice(name = "Car", address = "AA:BB:CC:DD:EE:03", pageable = true)

    @Test
    fun aPairedTargetIsPagedByTheAddressItWasPairedUnder() {
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01"),
            bluetoothNearPageTargets(setOf("sam's phone"), listOf(partner, watch, car), connected = emptySet()),
        )
        assertEquals(
            listOf("AA:BB:CC:DD:EE:01"),
            bluetoothNearPageTargets(setOf(" AA:BB:CC:DD:EE:01 "), listOf(partner, car), connected = emptySet()),
        )
    }

    @Test
    fun onlyPairedDevicesAProfileWaitsForArePaged() {
        assertEquals(emptyList<String>(), bluetoothNearPageTargets(setOf("Kitchen Speaker"), listOf(partner, car), emptySet()))
        // An address nobody paired can't be paged: Android would try a stranger.
        assertEquals(emptyList<String>(), bluetoothNearPageTargets(setOf("11:22:33:44:55:66"), listOf(partner), emptySet()))
        assertEquals(emptyList<String>(), bluetoothNearPageTargets(setOf("", "  "), listOf(partner), emptySet()))
    }

    @Test
    fun aDeviceThatOnlySpeaksLowEnergyOrIsAlreadyLinkedIsLeftAlone() {
        assertEquals(emptyList<String>(), bluetoothNearPageTargets(setOf("Band"), listOf(watch), emptySet()))
        assertEquals(
            emptyList<String>(),
            bluetoothNearPageTargets(setOf("Sam's Phone"), listOf(partner), connected = setOf("AA:BB:CC:DD:EE:01")),
        )
    }

    @Test
    fun pagesPerCycleStayBounded() {
        val many = (10..30).map { BondedBluetoothDevice("Phone", "AA:BB:CC:DD:EE:$it", pageable = true) }
        assertEquals(3, bluetoothNearPageTargets(setOf("Phone"), many, emptySet()).size)
        assertEquals(1, bluetoothNearPageTargets(setOf("Phone", "phone"), many.take(1), emptySet()).size)
    }

    @Test
    fun pagingRunsEveryOtherCycleAndLessOftenOverAudio() {
        assertFalse(bluetoothNearPagesBondedTargets(cycle = 1, audioConnected = false))
        assertTrue(bluetoothNearPagesBondedTargets(cycle = 2, audioConnected = false))
        assertTrue(bluetoothNearPagesBondedTargets(cycle = 4, audioConnected = false))
        assertTrue(bluetoothNearPagesBondedTargets(cycle = 0, audioConnected = true))
        assertFalse(bluetoothNearPagesBondedTargets(cycle = 2, audioConnected = true))
        assertTrue(bluetoothNearPagesBondedTargets(cycle = 3, audioConnected = true))
    }

    @Test
    fun aPageNeverStartsInTheCycleThatRunsDiscovery() {
        (0..30).forEach { cycle ->
            listOf(false, true).forEach { audio ->
                if (bluetoothNearRunsClassicDiscovery(cycle, audio)) {
                    assertFalse("cycle=$cycle audio=$audio", bluetoothNearPagesBondedTargets(cycle, audio))
                }
            }
        }
    }

    @Test
    fun aPhoneThatAnswersEveryPageNeverDropsOffTheList() {
        listOf(false, true).forEach { audio ->
            val tracker = NearbyBluetoothTracker()
            var firstSeen: Int? = null
            (0..40).forEach { cycle ->
                val now = cycle * BT_NEAR_CYCLE_MS
                if (bluetoothNearPagesBondedTargets(cycle, audio)) {
                    tracker.sighted("Sam's Phone", partner.address, now)
                    if (firstSeen == null) firstSeen = cycle
                }
                tracker.forgetStale(now + BT_NEAR_CYCLE_MS - 1)
                // Cycle 0 runs discovery, which a phone that doesn't advertise never answers.
                if (firstSeen != null) {
                    assertEquals("audio=$audio cycle=$cycle", "Sam's Phone\tAA:BB:CC:DD:EE:01", tracker.stateValue())
                }
            }
            assertTrue("audio=$audio first page at cycle $firstSeen", (firstSeen ?: Int.MAX_VALUE) <= 3)
        }
    }
}
