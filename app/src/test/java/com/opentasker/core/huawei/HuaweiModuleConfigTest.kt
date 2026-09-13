package com.opentasker.core.huawei

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The module-feature write, pinned to the bytes Huawei Health actually sent.
 *
 * These two frames were recovered by decrypting a Bluetooth capture while each switch was toggled on
 * a real Band 11 Pro — the same method that produced the fitness switches. They are the only
 * evidence that exists for this command, so the test asserts them literally rather than asserting a
 * shape the builder could satisfy while being wrong:
 *
 * ```
 * 84 0E 05 04 35 A9 7C E8 06 01 01 07 03 01 01 01     apnea ON
 * 84 0E 05 04 35 A9 7C E9 06 01 01 07 03 01 01 00     arrhythmia, flag 0
 * ```
 *
 * A capture is the whole specification here. There is no documentation to fall back on and no way to
 * tell a band that ignored a malformed config from one that took it — it answers `100000` either
 * way — so a builder that drifts from these bytes would look like a working setting forever.
 */
class HuaweiModuleConfigTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `apnea on reproduces the captured frame exactly`() {
        assertArrayEquals(
            hex("84 0E 05 04 35 A9 7C E8 06 01 01 07 03 01 01 01"),
            HuaweiCommands.moduleConfig(0x35A97CE8, on = true),
        )
    }

    @Test
    fun `arrhythmia off reproduces the captured frame exactly`() {
        assertArrayEquals(
            hex("84 0E 05 04 35 A9 7C E9 06 01 01 07 03 01 01 00"),
            HuaweiCommands.moduleConfig(HuaweiCommands.MODULE_ARRHYTHMIA, on = false),
        )
    }

    /**
     * The flag is the LAST byte of tag 7 and nothing else changes with it.
     *
     * Worth its own test because tag 7's value is `01 01 <flag>` — three bytes, two of them
     * constant — and a builder that wrote the flag into the wrong one of the three would still
     * produce a 16-byte frame of the right shape.
     */
    @Test
    fun `only the final byte differs between on and off`() {
        val on = HuaweiCommands.moduleConfig(HuaweiCommands.MODULE_EMOTION, on = true)
        val off = HuaweiCommands.moduleConfig(HuaweiCommands.MODULE_EMOTION, on = false)
        assertEquals(on.size, off.size)
        assertArrayEquals(on.dropLast(1).toByteArray(), off.dropLast(1).toByteArray())
        assertEquals(1.toByte(), on.last())
        assertEquals(0.toByte(), off.last())
    }

    /** The config id is big-endian, and all four bytes of it travel. */
    @Test
    fun `config id is carried big-endian in tag 5`() {
        val f = HuaweiCommands.moduleConfig(0x01020304, on = true)
        assertArrayEquals(hex("05 04 01 02 03 04"), f.copyOfRange(2, 8))
    }

    /**
     * Apnea is two ids and they are not the same one twice.
     *
     * Health never sent one without the other; the pair is what was observed to work. A copy-paste
     * that made both entries the same id would turn the pair into a single write and be invisible.
     */
    @Test
    fun `apnea names two distinct ids`() {
        assertEquals(2, HuaweiCommands.MODULE_APNEA.size)
        assertNotEquals(HuaweiCommands.MODULE_APNEA[0], HuaweiCommands.MODULE_APNEA[1])
        assertEquals(0x35A97CE7, HuaweiCommands.MODULE_APNEA[0])
        assertEquals(0x35A97CE8, HuaweiCommands.MODULE_APNEA[1])
    }

    /**
     * A module config is NOT a fitness toggle, and the two must never be interchangeable.
     *
     * The failure this guards is silent: the band ACKs a module config posted to `0x07` with its
     * ordinary success code and changes nothing, exactly as it ACKs a locale it has no pack for.
     */
    @Test
    fun `module config does not resemble a fitness toggle`() {
        assertNotEquals(
            HuaweiCommands.fitnessToggle(true).toList(),
            HuaweiCommands.moduleConfig(HuaweiCommands.MODULE_EMOTION, on = true).toList(),
        )
    }
}
