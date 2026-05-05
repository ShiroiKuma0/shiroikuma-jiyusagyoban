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

    @Test
    fun parsesConditionResultCodes() {
        val satisfied = LocalePluginConditionResultParser.parse(
            LocalePluginContract.RESULT_CONDITION_SATISFIED,
            "com.example.plugin",
        )
        val unsatisfied = LocalePluginConditionResultParser.parse(
            LocalePluginContract.RESULT_CONDITION_UNSATISFIED,
            "com.example.plugin",
        )
        val unknown = LocalePluginConditionResultParser.parse(
            LocalePluginContract.RESULT_CONDITION_UNKNOWN,
            "com.example.plugin",
        )
        val unrecognized = LocalePluginConditionResultParser.parse(999, "com.example.plugin")

        assertEquals(LocalePluginConditionState.Satisfied, satisfied.state)
        assertEquals(LocalePluginConditionState.Unsatisfied, unsatisfied.state)
        assertEquals(LocalePluginConditionState.Unknown, unknown.state)
        assertEquals(LocalePluginConditionState.Unknown, unrecognized.state)
        assertTrue(satisfied.satisfied)
        assertTrue(unrecognized.message.contains("unrecognized result code"))
    }

    @Test
    fun conditionStateCacheFallsBackToLastKnownStateForUnknown() {
        val cache = LocalePluginConditionStateCache()
        val key = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "work"))

        cache.resolve(
            key,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_SATISFIED,
                "com.example.plugin",
            ),
        )
        val resolved = cache.resolve(
            key,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_UNKNOWN,
                "com.example.plugin",
            ),
        )

        assertEquals(LocalePluginConditionState.Satisfied, resolved.state)
        assertTrue(resolved.message.contains("last known satisfied"))
    }

    @Test
    fun conditionStateCacheTreatsUnknownWithoutHistoryAsUnsatisfied() {
        val cache = LocalePluginConditionStateCache()
        val key = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "work"))

        val resolved = cache.resolve(
            key,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_UNKNOWN,
                "com.example.plugin",
            ),
        )

        assertEquals(LocalePluginConditionState.Unsatisfied, resolved.state)
        assertTrue(resolved.message.contains("No last known result"))
    }

    @Test
    fun conditionStateCacheScopesHistoryByBundle() {
        val cache = LocalePluginConditionStateCache()
        val work = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "work"))
        val home = LocalePluginConditionCacheKey("com.example.plugin", mapOf("mode" to "home"))

        cache.resolve(
            work,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_SATISFIED,
                "com.example.plugin",
            ),
        )
        val resolved = cache.resolve(
            home,
            LocalePluginConditionResultParser.parse(
                LocalePluginContract.RESULT_CONDITION_UNKNOWN,
                "com.example.plugin",
            ),
        )

        assertEquals(LocalePluginConditionState.Unsatisfied, resolved.state)
    }
}
