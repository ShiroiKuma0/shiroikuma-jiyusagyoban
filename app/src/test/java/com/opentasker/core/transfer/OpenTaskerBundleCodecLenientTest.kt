package com.opentasker.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenTaskerBundleCodecLenientTest {
    @Test
    fun decodesHandEditedJsonWithCommentsAndTrailingCommas() {
        val hand = """
            // A hand-authored OpenTasker bundle
            {
                "schemaVersion": 5,
                "appVersion": "0.2.75",
                "exportedAtEpochMs": 0,
                "metadata": {
                    "name": "My export",
                },
            }
        """.trimIndent()

        val bundle = OpenTaskerBundleCodec.decode(hand)
        assertEquals("0.2.75", bundle.appVersion)
        assertEquals("My export", bundle.metadata.name)
    }

    /**
     * The fork sets `ignoreUnknownKeys = true` deliberately: a bundle written by a NEWER build must
     * still import into this one rather than failing wholesale on a key it has not learned yet. So an
     * unknown key is tolerated, not rejected — the inverse of upstream's strict policy.
     */
    @Test
    fun toleratesUnknownKeysForForwardCompatibility() {
        val newer = """{ "schemaVersion": 5, "appVersion": "0.2.75", "exportedAtEpochMs": 0, "bogusKey": 1 }"""

        val bundle = OpenTaskerBundleCodec.decode(newer)

        assertEquals("0.2.75", bundle.appVersion)
    }

    /** The one hard floor: an id-bearing pre-v5 backup is refused rather than half-read. */
    @Test
    fun rejectsPreV5IdBearingBackups() {
        val old = """{ "schemaVersion": 4, "appVersion": "0.2.60", "exportedAtEpochMs": 0 }"""

        assertThrows(IllegalArgumentException::class.java) {
            OpenTaskerBundleCodec.decode(old)
        }
    }
}
