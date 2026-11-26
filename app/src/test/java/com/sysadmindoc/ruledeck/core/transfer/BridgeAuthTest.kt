package com.sysadmindoc.ruledeck.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone and the PC must build the same signed text, byte for byte, or every command is refused.
 * The vector below was made on the PC the way `scripts/bridge.py` does it (openssl, a throwaway
 * P-256 key), so this test is the agreement between the two sides — not Kotlin checking Kotlin.
 */
class BridgeAuthTest {

    private val key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUghLsZft1K6m9od/qamtvoSwr5FCmq9IL/aEECkitUpzMOVPT7K6M224RWBOo/TCy9k96MPLgVeyJ/clRe7NdA=="
    private val signature = "MEUCIGN4ArlzfQroGM68jjzqVBTcIx64HcK6SAkF9evI1riOAiEAiuWkzjbUfxZtNyY6L3x7MA+tU0TT7f9p0PxUFYp1Qj0="
    private val e = BridgeAuth.EXTRA_PREFIX
    private val extras = mapOf(
        e + "PROTOCOL" to "1",
        e + "TASK" to "携帯 ⇨ 起動 -- [71][71]",
        e + "COUNTER" to "1795000000000",
        e + "PROJECT" to "a%b\nc",
    )
    private val action = "shiroikuma.jiyusagyoban.action.RUN_TASK"
    private val id = "0123456789abcdef"

    @Test
    fun canonicalTextIsSortedAndEscaped() {
        assertEquals(
            "jsb-bridge-1\n$id\n$action\n" +
                "${e}COUNTER=1795000000000\n${e}PROJECT=a%25b%0Ac\n${e}PROTOCOL=1\n${e}TASK=携帯 ⇨ 起動 -- [71][71]\n",
            BridgeAuth.canonical(id, action, extras),
        )
    }

    @Test
    fun aSignatureMadeOnThePcVerifies() {
        assertTrue(BridgeAuth.verify(key, BridgeAuth.canonical(id, action, extras), signature))
    }

    @Test
    fun anyChangeBreaksTheSignature() {
        // Another phone, another action, another task, a replayed counter moved forward.
        assertFalse(BridgeAuth.verify(key, BridgeAuth.canonical("fedcba9876543210", action, extras), signature))
        assertFalse(BridgeAuth.verify(key, BridgeAuth.canonical(id, action.replace("RUN_TASK", "IMPORT_BUNDLE"), extras), signature))
        assertFalse(BridgeAuth.verify(key, BridgeAuth.canonical(id, action, extras + (e + "TASK" to "other")), signature))
        assertFalse(BridgeAuth.verify(key, BridgeAuth.canonical(id, action, extras + (e + "COUNTER" to "1795000000001")), signature))
        assertFalse(BridgeAuth.verify(key, BridgeAuth.canonical(id, action, extras + (e + "SHA256" to "00")), signature))
    }

    @Test
    fun garbageIsRefusedNotThrown() {
        assertFalse(BridgeAuth.verify(key, "x", "not base64 !"))
        assertFalse(BridgeAuth.verify("AAAA", "x", signature))
    }

    @Test
    fun fingerprintMatchesThePcs() {
        assertEquals("58e5 f1b5 14b0 4d6b", BridgeAuth.fingerprint(key))
    }
}
