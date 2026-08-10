package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldExecutionTest {
    @Test
    fun payloadRedactsSensitiveTriggerFieldsAndPreservesSafeFields() {
        val task = Task(id = 7, name = "Receive event", actions = listOf(ActionSpec(type = "log")))
        val envelope = ExecutionEnvelope.create(task, "External intent", executionId = "held-1")

        val encoded = HeldExecutionPayloadCodec.encode(
            task = task,
            envelope = envelope,
            metadata = listOf("Authorization: Bearer top-secret"),
            initialVariables = mapOf(
                "eventType" to "push",
                "API_TOKEN" to "super-secret-token",
            ),
        )
        val decoded = HeldExecutionPayloadCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(encoded.length <= HeldExecutionPayloadCodec.MAX_PAYLOAD_CHARS)
        assertFalse(encoded.contains("top-secret"))
        assertFalse(encoded.contains("super-secret-token"))
        assertEquals("push", decoded?.initialVariables?.get("eventType"))
        assertEquals(HeldExecutionPayloadCodec.REDACTED_VALUE, decoded?.initialVariables?.get("API_TOKEN"))
    }

    @Test
    fun payloadDecoderFailsClosedForMalformedOrOversizedData() {
        assertNull(HeldExecutionPayloadCodec.decode("not-json"))
        assertNull(HeldExecutionPayloadCodec.decode("x".repeat(HeldExecutionPayloadCodec.MAX_PAYLOAD_CHARS + 1)))
    }

    @Test
    fun heldLedgerStateIsTerminal() {
        assertTrue(ExecutionLedgerState.HELD.isTerminal)
        assertFalse(ExecutionLedgerState.ACCEPTED.isTerminal)
    }
}
