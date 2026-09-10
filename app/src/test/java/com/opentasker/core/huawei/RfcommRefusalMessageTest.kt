package com.opentasker.core.huawei

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed connection must say something a person can act on.
 *
 * `BluetoothSocket.connect()` throws the same sentence for every cause — *"read failed, socket
 * might closed or timeout, read ret: -1"* — and the panel printed it three times over, once per
 * rung. On 2026-09-10 that message appeared twice, hours apart, and each time the actual answer
 * (**HCI 0x0D, the band refusing**) was reachable only through `logcat` and a cable. The cure, once
 * found, was to restart the band.
 *
 * Source gates: the branches need a real adapter and a real bonded device.
 */
class RfcommRefusalMessageTest {

    private val src = ProductionSources.read("com/opentasker/core/huawei/HuaweiRfcommClient.kt")

    private val body = ProductionSources.block(
        "com/opentasker/core/huawei/HuaweiRfcommClient.kt",
        "private fun refusalMessage(",
        "/** False once the link is gone",
    )

    /** The two things the app CAN check are checked, and reported first. */
    @Test
    fun `the adapter and the bond are ruled out before anything is guessed`() {
        assertTrue("an adapter that is off must be named", body.contains("adapter.isEnabled"))
        assertTrue("Bluetooth is off on this phone" in body)
        assertTrue("an unpaired band must be named", body.contains("BluetoothDevice.BOND_BONDED"))
        assertTrue("not paired with this phone" in body)
    }

    /** And when both are fine, the likeliest cause and the remedy that worked. */
    @Test
    fun `the remedy is named`() {
        assertTrue("Restart the band" in body)
        assertTrue("the reason must be given, not just the cure", "stale session" in body)
        assertTrue("and the alternative kept honest", "out of range or asleep" in body)
    }

    /**
     * It must not claim to know the HCI status.
     *
     * The app cannot see it — there is no API, and reading the system log needs a system
     * permission. A message that asserted "the band refused with 0x0D" would be inventing a
     * finding out of a likelihood, which is the failure mode this whole message exists to end.
     */
    @Test
    fun `it does not claim a reason it cannot know`() {
        // `body` is already the bounded slice of the function — see ProductionSources.block, and
        // the guard that exists because `substringAfter` silently widens to the whole file.
        assertFalse("the app cannot see the HCI status", "0x0D" in body)
        assertFalse("nor its name", "Limited Resources" in body)
        assertTrue("it must be worded as a likelihood", "usual cause" in body)
    }

    /** The rung-by-rung detail survives: it distinguishes "all refused" from "one is wrong". */
    @Test
    fun `the per-rung detail is kept`() {
        assertTrue(body.contains("""RFCOMM refused: ${'$'}failures"""))
        assertTrue(src.contains("\"uuid\" to"))
        assertTrue(src.contains("\"uuid-insecure\" to"))
        assertTrue(src.contains("\"alt-uuid\" to"))
    }
}
