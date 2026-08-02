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

/**
 * The app-owned boundary for a de-googled push distributor.
 *
 * A UnifiedPush distributor can forward its delivery callback as an explicit broadcast using this
 * contract. The receiver authenticates the per-install token before this bus sees the payload.
 * Message content is intentionally not copied into [ContextEvent.metadata]; event filters can
 * match the topic, title, event ID, and payload size without putting a remote message in logs.
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
        val topic = delivery.topic.trim()
        val eventId = delivery.eventId.trim()
        val title = sanitizeText(delivery.title, MAX_TITLE_CHARS)
        val message = delivery.message
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
