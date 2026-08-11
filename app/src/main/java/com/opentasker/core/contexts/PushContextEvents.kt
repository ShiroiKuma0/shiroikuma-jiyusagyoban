package com.opentasker.core.contexts

import android.content.Intent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The app-owned boundary for a de-googled push distributor.
 *
 * The official UnifiedPush service and the legacy explicit-broadcast receiver publish through this
 * boundary. The legacy receiver authenticates the per-install token before this bus sees its
 * payload; the official connector authenticates and decrypts its bytes message first. Message
 * content is intentionally not copied into [ContextEvent.metadata]; event filters can match the
 * topic, title, event ID, and payload size without putting a remote message in logs.
 */
object PushContextEvents {
    const val ACTION_PUSH_EVENT = "com.opentasker.action.PUSH_EVENT"
    const val EVENT_PUSH = "push"
    const val EXTRA_TOKEN = "com.opentasker.extra.PUSH_TOKEN"
    const val EXTRA_TOPIC = "com.opentasker.extra.PUSH_TOPIC"
    const val EXTRA_EVENT_ID = "com.opentasker.extra.PUSH_EVENT_ID"
    const val EXTRA_TITLE = "com.opentasker.extra.PUSH_TITLE"
    const val EXTRA_MESSAGE = "com.opentasker.extra.PUSH_MESSAGE"

    const val MAX_TOPIC_CHARS = 160
    const val MAX_EVENT_ID_CHARS = 128
    const val MAX_TITLE_CHARS = 160
    const val MAX_MESSAGE_BYTES = 8 * 1024
    /** AND_3.1.0 bounds the encrypted UnifiedPush bytes message at 4096 bytes. */
    const val MAX_UNIFIED_PUSH_MESSAGE_BYTES = 4 * 1024
    const val PENDING_PULSE_REPLAY_MS = 30_000L

    private val pushEvents = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    private val pendingPulse = AtomicReference<PendingPushPulse?>(null)
    private val seenEventIds = ConcurrentHashMap<String, Long>()

    /** A flow with a short replay window for a distributor delivery racing engine startup. */
    val events: Flow<ContextEvent> = flow {
        val now = System.currentTimeMillis()
        pendingPulse.get()
            ?.takeIf { now - it.observedAtMs <= PENDING_PULSE_REPLAY_MS }
            ?.let { emit(it.event) }
        emitAll(pushEvents.asSharedFlow())
    }

    /**
     * Parses, authenticates, redacts, and publishes one distributor delivery.
     *
     * Delivery is at-least-once: the distributor may retry a broadcast, but a repeated
     * topic/event-id pair is suppressed for [PENDING_PULSE_REPLAY_MS]. No network retry occurs in
     * this receiver; retry responsibility stays with the distributor/bridge.
     */
    fun publishFromIntent(
        intent: Intent,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (intent.action != ACTION_PUSH_EVENT) return false
        return publishDelivery(
            PushDelivery(
                token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                topic = intent.getStringExtra(EXTRA_TOPIC).orEmpty(),
                eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
            ),
            expectedToken,
            nowMs,
        )
    }

    fun publishDelivery(
        delivery: PushDelivery,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val event = parseDelivery(delivery, expectedToken, nowMs) ?: return false
        return publishEvent(event, nowMs)
    }

    /**
     * Parses the standard ntfy/UnifiedPush JSON bytes message after the connector has decrypted
     * it. The connector has already authenticated the distributor and acknowledged the message;
     * this method only applies OpenTasker's payload contract and event safeguards.
     */
    fun publishUnifiedPushMessage(
        content: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val event = parseUnifiedPushMessage(content, nowMs) ?: return false
        return publishEvent(event, nowMs)
    }

    fun parseUnifiedPushMessage(
        content: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent? {
        if (content.isEmpty() || content.size > MAX_UNIFIED_PUSH_MESSAGE_BYTES) return null
        val root = runCatching {
            Json.parseToJsonElement(String(content, StandardCharsets.UTF_8))
        }.getOrNull() as? JsonObject ?: return null
        val topic = root.stringValue("topic") ?: return null
        val eventId = root.stringValue("id", "event_id", "eventId") ?: return null
        val title = root.stringValue("title").orEmpty()
        val message = root.stringValue("message", "body").orEmpty()
        return parseFields(topic, eventId, title, message, nowMs)
    }

    private fun publishEvent(event: ContextEvent, nowMs: Long): Boolean {
        val dedupeKey = "${event.metadata["topic"]}\u0000${event.metadata["eventId"]}"
        pruneSeen(nowMs)
        if (seenEventIds.putIfAbsent(dedupeKey, nowMs) != null) return false

        pendingPulse.set(PendingPushPulse(event, nowMs))
        return pushEvents.tryEmit(event)
    }

    fun parseIntent(
        intent: Intent,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent? = if (intent.action == ACTION_PUSH_EVENT) {
        parseDelivery(
            PushDelivery(
                token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                topic = intent.getStringExtra(EXTRA_TOPIC).orEmpty(),
                eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
            ),
            expectedToken,
            nowMs,
        )
    } else {
        null
    }

    fun parseDelivery(
        delivery: PushDelivery,
        expectedToken: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent? {
        if (expectedToken.isBlank() || !constantTimeEquals(delivery.token, expectedToken)) return null
        return parseFields(delivery.topic, delivery.eventId, delivery.title, delivery.message, nowMs)
    }

    private fun parseFields(
        rawTopic: String,
        rawEventId: String,
        rawTitle: String,
        message: String,
        nowMs: Long,
    ): ContextEvent? {
        val topic = rawTopic.trim()
        val eventId = rawEventId.trim()
        val title = sanitizeText(rawTitle, MAX_TITLE_CHARS)
        if (topic.isBlank() || topic.length > MAX_TOPIC_CHARS) return null
        if (eventId.isBlank() || eventId.length > MAX_EVENT_ID_CHARS) return null
        if (message.toByteArray(StandardCharsets.UTF_8).size > MAX_MESSAGE_BYTES) return null

        return buildEvent(
            topic = topic,
            eventId = eventId,
            title = title,
            messageBytes = message.toByteArray(StandardCharsets.UTF_8).size,
            nowMs = nowMs,
        )
    }

    private fun JsonObject.stringValue(vararg names: String): String? =
        names.asSequence()
            .mapNotNull { name -> (this[name] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull()

    fun buildEvent(
        topic: String,
        eventId: String,
        title: String = "",
        messageBytes: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", EVENT_PUSH)
            put("topic", topic)
            put("eventId", eventId)
            if (title.isNotBlank()) put("title", title)
            put("payloadBytes", messageBytes.toString())
            put("observedAtEpochMs", nowMs.toString())
        },
    )

    internal fun resetForTests() {
        pendingPulse.set(null)
        seenEventIds.clear()
    }

    private fun pruneSeen(nowMs: Long) {
        seenEventIds.entries.removeIf { nowMs - it.value > PENDING_PULSE_REPLAY_MS }
        if (seenEventIds.size > MAX_SEEN_EVENT_IDS) {
            seenEventIds.entries
                .sortedBy { it.value }
                .take(seenEventIds.size - MAX_SEEN_EVENT_IDS)
                .forEach { seenEventIds.remove(it.key, it.value) }
        }
    }

    private fun sanitizeText(value: String?, maxChars: Int): String =
        value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(maxChars)
            .orEmpty()

    internal fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(StandardCharsets.UTF_8),
            right.toByteArray(StandardCharsets.UTF_8),
        )

    private const val MAX_SEEN_EVENT_IDS = 512
}

data class PushDelivery(
    val token: String,
    val topic: String,
    val eventId: String,
    val title: String = "",
    val message: String = "",
)

private data class PendingPushPulse(
    val event: ContextEvent,
    val observedAtMs: Long,
)
