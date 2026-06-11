package com.opentasker.core.contexts

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

object BootContextEvents {
    const val EVENT_BOOT_COMPLETED = "boot_completed"
    internal const val PENDING_PULSE_REPLAY_MS = 30_000L

    private val bootEvents = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 8,
    )
    private val pendingBootPulse = AtomicReference<PendingBootPulse?>(null)

    val events: Flow<ContextEvent> = events { System.currentTimeMillis() }

    fun publishBootCompleted(nowMs: Long = System.currentTimeMillis()): Boolean {
        val event = buildEvent(nowMs)
        pendingBootPulse.set(PendingBootPulse(event, nowMs))
        return bootEvents.tryEmit(event)
    }

    fun buildEvent(nowMs: Long = System.currentTimeMillis()): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_BOOT_COMPLETED,
            "observedAtEpochMs" to nowMs.toString(),
        ),
    )

    internal fun events(nowMs: () -> Long): Flow<ContextEvent> = flow {
        pendingBootPulse.get()
            ?.takeIf { nowMs() - it.observedAtMs <= PENDING_PULSE_REPLAY_MS }
            ?.let { emit(it.event) }
        emitAll(bootEvents.asSharedFlow())
    }

    internal fun clearPendingForTests() {
        pendingBootPulse.set(null)
    }
}

private data class PendingBootPulse(
    val event: ContextEvent,
    val observedAtMs: Long,
)
