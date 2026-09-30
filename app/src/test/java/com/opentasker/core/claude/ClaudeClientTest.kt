package com.opentasker.core.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire format, checked as JSON — the shapes the Messages API takes and gives back. */
class ClaudeClientTest {

    @Test
    fun theBodyCarriesModelEffortSchemaAndTheFallback() {
        val schema = ClaudeClient.objectSchema(mapOf("ja" to Json.parseToJsonElement("""{"type":"string"}""")))
        val body = Json.parseToJsonElement(
            ClaudeClient.body(
                ClaudeClient.Request(apiKey = "k", prompt = "「\"引用\"」\n二行", system = "sys", schema = schema, effort = "high"),
            ),
        ).jsonObject
        assertEquals("claude-opus-5-5", body.getValue("model").jsonPrimitive.content)
        assertEquals("sys", body.getValue("system").jsonPrimitive.content)
        assertEquals("「\"引用\"」\n二行", body.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content)
        val cfg = body.getValue("output_config").jsonObject
        assertEquals("high", cfg.getValue("effort").jsonPrimitive.content)
        assertEquals("json_schema", cfg.getValue("format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), cfg.getValue("format").jsonObject.getValue("schema").jsonObject.getValue("additionalProperties"))
        assertEquals("default", body.getValue("fallbacks").jsonPrimitive.content)
        // No thinking field: Claude Opus 5.5 thinks always, and `disabled` would be a 400.
        assertFalse("thinking" in body)
    }

    @Test
    fun noFallbackMeansNoFallbackField() {
        val body = Json.parseToJsonElement(ClaudeClient.body(ClaudeClient.Request(apiKey = "k", prompt = "p", fallbacks = false))).jsonObject
        assertFalse("fallbacks" in body)
    }

    @Test
    fun theTextIsEveryTextBlockAndThinkingIsIgnored() {
        val reply = ClaudeClient.parse(
            """{"model":"claude-opus-5-5","stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":34},
               "content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"a\":"},{"type":"text","text":"1}"}]}""",
        )
        assertEquals("{\"a\":1}", reply.text)
        assertTrue(reply.complete)
        assertEquals(12L, reply.inputTokens)
        assertNull(reply.refusal)
    }

    @Test
    fun aRefusalIsNotComplete() {
        val reply = ClaudeClient.parse(
            """{"model":"m","stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"x"},"content":[]}""",
        )
        assertFalse(reply.complete)
        assertEquals("cyber: x", reply.refusal)
    }

    @Test
    fun anErrorBodyGivesTheApisOwnReason() {
        assertEquals(
            "HTTP 401 — authentication_error: invalid x-api-key",
            ClaudeClient.errorMessage(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        assertEquals("HTTP 502 — Bad Gateway", ClaudeClient.errorMessage(502, "Bad Gateway"))
    }

    @Test
    fun aStreamIsFoldedIntoTheSameReplyAndReportsAsItGoes() = kotlinx.coroutines.runBlocking {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"model":"claude-opus-5-5","usage":{"input_tokens":40}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Deciding the register."}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"{\"a\":"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"1}"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()
        val seen = ArrayList<ClaudeClient.Live>()
        val reply = ClaudeClient.readStream(okio.Buffer().writeUtf8(sse)) { seen += it }
        assertEquals("{\"a\":1}", reply.text)
        assertTrue(reply.complete)
        assertEquals(40L, reply.inputTokens)
        assertEquals(9L, reply.outputTokens)
        assertTrue(seen.any { it.phase == "thinking" })
        assertEquals("Deciding the register.", seen.last().thinking)
    }

    @Test
    fun aStreamThatStopsShortIsAnError() {
        val sse = "data: {\"type\":\"message_start\",\"message\":{\"model\":\"m\"}}\n"
        val e = runCatching { kotlinx.coroutines.runBlocking { ClaudeClient.readStream(okio.Buffer().writeUtf8(sse)) {} } }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("ended before"))
    }

    @Test
    fun streamingAsksForTheThinkingSummary() {
        val body = Json.parseToJsonElement(
            ClaudeClient.body(ClaudeClient.Request(apiKey = "k", prompt = "p", thinkingSummary = true), stream = true),
        ).jsonObject
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
        assertEquals("summarized", body.getValue("thinking").jsonObject.getValue("display").jsonPrimitive.content)
    }
}
