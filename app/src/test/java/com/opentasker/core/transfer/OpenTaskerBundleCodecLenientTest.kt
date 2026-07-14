package com.opentasker.core.transfer

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenTaskerBundleCodecLenientTest {
    @Test
    fun decodesHandEditedJsonWithCommentsAndTrailingCommas() {
        val hand = """
            // A hand-authored OpenTasker bundle
            {
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

    @Test
    fun stillRejectsUnknownKeys() {
        val bogus = """{ "appVersion": "0.2.75", "exportedAtEpochMs": 0, "bogusKey": 1 }"""
        assertThrows(SerializationException::class.java) {
            OpenTaskerBundleCodec.decode(bogus)
        }
    }
}
