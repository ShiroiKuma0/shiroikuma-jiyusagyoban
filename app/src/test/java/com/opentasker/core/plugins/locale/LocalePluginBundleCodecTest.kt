package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginBundleCodecTest {
    @Test
    fun decodesPrimitiveJsonObjectAsStringMap() {
        val values = LocalePluginBundleCodec.decodeStringBundle(
            """{"text":"hello","enabled":true,"count":3,"ratio":1.5}"""
        )

        assertEquals("hello", values["text"])
        assertEquals("true", values["enabled"])
        assertEquals("3", values["count"])
        assertEquals("1.5", values["ratio"])
    }

    @Test
    fun rejectsNestedBundleValues() {
        val error = runCatching {
            LocalePluginBundleCodec.decodeStringBundle("""{"nested":{"unsafe":"value"}}""")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException || error is IllegalArgumentException)
    }

    @Test
    fun rejectsInvalidPackageNames() {
        val error = runCatching {
            LocalePluginBundleCodec.validatePackageName("not a package")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsOversizedBundleJson() {
        val oversized = buildString {
            append("{\"payload\":\"")
            repeat(LocalePluginContract.MAX_BUNDLE_JSON_BYTES) { append('a') }
            append("\"}")
        }

        val error = runCatching {
            LocalePluginBundleCodec.decodeStringBundle(oversized)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
