package com.opentasker.core.onse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batch file is the whole of what 白い熊 音声 reads, and the sentences are free text — quotes,
 * backslashes, 「」 and line breaks all arrive in it. A hand-built string would break on the first of
 * them, so it is built as JSON and read back here as JSON.
 */
class OnseRenderTest {

    @Test
    fun theBatchSurvivesEveryCharacterASentenceCanHold() {
        val awkward = "彼は「\"はい\"」と言った\\ 本当に\n二行目"
        val json = OnseRender.batchJson(
            listOf(
                OnseRender.Item("1", "私は白い熊です。", "/sdcard/x/001 私は白い熊です.ogg"),
                OnseRender.Item("2", awkward, "/sdcard/x/002.ogg"),
            ),
        )
        val items = Json.parseToJsonElement(json).jsonObject.getValue("items").jsonArray
        assertEquals(2, items.size)
        assertEquals("1", items[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("/sdcard/x/001 私は白い熊です.ogg", items[0].jsonObject.getValue("out_path").jsonPrimitive.content)
        assertEquals(awkward, items[1].jsonObject.getValue("text").jsonPrimitive.content)
    }

    /** The warning with the open-音声 button is for "could not start it", never for a sentence that failed. */
    @Test
    fun onlyAStartFailureRaisesTheOpenOnseWarning() {
        assertTrue(OnseWarning.needsAttention("ERROR:no-foreground-start"))
        assertTrue(OnseWarning.needsAttention("ERROR:音声 did not answer — not installed, frozen, or its start was blocked"))
        assertTrue(OnseWarning.needsAttention("ERROR:音声 answered the wake-up but nothing came back for the render in 90s"))
        assertFalse(OnseWarning.needsAttention("OK:2|1|3"))
        assertFalse(OnseWarning.needsAttention("ERROR:voice not installed: 31 (7.vvm)"))
        assertFalse(OnseWarning.needsAttention("ERROR:cancelled|1|0|3"))
    }
}
