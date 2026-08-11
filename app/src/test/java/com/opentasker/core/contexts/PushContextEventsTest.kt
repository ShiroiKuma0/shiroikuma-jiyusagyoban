package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushContextEventsTest {
    @After
    fun tearDown() {
        PushContextEvents.resetForTests()
    }

    @Test
    fun authenticatedDeliveryEmitsMatchableRedactedEvent() = runBlocking {
        val token = "install-token"
        val received = async {
            withTimeout(1_000L) {
                PushContextEvents.events.first { it.metadata["event"] == PushContextEvents.EVENT_PUSH }
            }
        }
        yield()
        val message = "hello from a private channel"
        val accepted = PushContextEvents.publishDelivery(
            PushDelivery(
                token = token,
                topic = "tasks",
                eventId = "evt-1",
                title = "Run task",
                message = message,
            ),
            expectedToken = token,
            nowMs = 10_000L,
        )

        val event = received.await()
        assertTrue(accepted)
        assertTrue(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, config = mapOf("event" to "push", "topic" to "tasks")),
                event,
            ),
        )
        assertEquals("tasks", event.metadata["topic"])
        assertEquals("evt-1", event.metadata["eventId"])
        assertEquals(message.toByteArray(StandardCharsets.UTF_8).size.toString(), event.metadata["payloadBytes"])
        assertFalse(event.metadata.containsKey("message"))
    }

    @Test
    fun wrongTokenAndOversizedMessageFailClosed() {
        val base = PushDelivery(token = "wrong", topic = "tasks", eventId = "evt-1")
        assertNull(PushContextEvents.parseDelivery(base, expectedToken = "right"))

        val oversized = base.copy(
            token = "right",
            message = "x".repeat(PushContextEvents.MAX_MESSAGE_BYTES + 1),
        )
        assertNull(PushContextEvents.parseDelivery(oversized, expectedToken = "right"))
    }

    @Test
    fun duplicateDeliveryIsSuppressedForAtLeastOnceRetry() {
        val delivery = PushDelivery(token = "token", topic = "tasks", eventId = "evt-1")
        assertTrue(PushContextEvents.publishDelivery(delivery, "token", nowMs = 1_000L))
        assertFalse(PushContextEvents.publishDelivery(delivery, "token", nowMs = 1_001L))
    }

    @Test
    fun unifiedPushNtfyJsonUsesStandardFieldsAndKeepsMessageRedacted() {
        val content = """
            {"id":"ntfy-1","time":1710000000,"event":"message","topic":"tasks","title":"Run task","message":"secret body"}
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val event = PushContextEvents.parseUnifiedPushMessage(content, nowMs = 12_000L)

        assertEquals("tasks", event?.metadata?.get("topic"))
        assertEquals("ntfy-1", event?.metadata?.get("eventId"))
        assertEquals("Run task", event?.metadata?.get("title"))
        assertEquals("secret body".toByteArray(StandardCharsets.UTF_8).size.toString(), event?.metadata?.get("payloadBytes"))
        assertFalse(event?.metadata?.containsKey("message") == true)
    }

    @Test
    fun unifiedPushPayloadSupportsLegacyAliasesAndRejectsBounds() {
        val aliased = """{"topic":"tasks","event_id":"evt-2","body":"hello"}"""
            .toByteArray(StandardCharsets.UTF_8)
        assertEquals("evt-2", PushContextEvents.parseUnifiedPushMessage(aliased)?.metadata?.get("eventId"))

        val oversized = ByteArray(PushContextEvents.MAX_UNIFIED_PUSH_MESSAGE_BYTES + 1)
        assertNull(PushContextEvents.parseUnifiedPushMessage(oversized))

        val missingId = """{"topic":"tasks","message":"hello"}""".toByteArray(StandardCharsets.UTF_8)
        assertNull(PushContextEvents.parseUnifiedPushMessage(missingId))
    }

    @Test
    fun ntfyBroadcastUsesDocumentedExtraNamesWithoutExposingMessage() {
        val event = PushContextEvents.parseDelivery(
            PushDelivery(
                token = "token",
                topic = "tasks",
                eventId = "ntfy-action-1",
                title = "Run task",
                message = "secret body",
                baseUrl = "https://ntfy.example.test",
                time = "1710000000",
                priority = "4",
            ),
            expectedToken = "token",
            nowMs = 20_000L,
        )

        assertEquals("ntfy-action-1", event?.metadata?.get(PushContextEvents.NTFY_EXTRA_ID))
        assertEquals("https://ntfy.example.test", event?.metadata?.get(PushContextEvents.NTFY_EXTRA_BASE_URL))
        assertEquals("1710000000", event?.metadata?.get(PushContextEvents.NTFY_EXTRA_TIME))
        assertEquals("4", event?.metadata?.get(PushContextEvents.NTFY_EXTRA_PRIORITY))
        assertFalse(event?.metadata?.containsKey(PushContextEvents.NTFY_EXTRA_MESSAGE) == true)
    }

    @Test
    fun ntfyBroadcastActionNameStillRequiresTheOpenTaskerToken() {
        val delivery = PushDelivery(token = "token", topic = "tasks", eventId = "button-1")

        assertTrue(PushContextEvents.parseDelivery(delivery, expectedToken = "token") != null)
        assertNull(PushContextEvents.parseDelivery(delivery, expectedToken = "other"))
    }
}
