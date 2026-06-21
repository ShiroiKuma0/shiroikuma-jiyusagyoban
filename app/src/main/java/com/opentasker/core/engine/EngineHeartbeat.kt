package com.opentasker.core.engine

import java.util.Collections

/**
 * Liveness beacon for the trigger engine. The per-minute `sun_tick` stamps [lastTickAt]; the per-minute
 * exact alarm ([com.opentasker.automation.receiver.TimeEventReceiver]) checks [isStale] and, if the tick
 * has gone quiet while the process is alive (the engine's coroutines died), asks the service to re-arm.
 * Each such recovery is recorded for the monitor view. Pure in-memory — survives only the process.
 */
object EngineHeartbeat {
    /** ~2.5 min: the tick is once a minute, so two missed ticks means the engine is dead, not just idle. */
    const val STALE_THRESHOLD_MS = 150_000L
    private const val MAX_EVENTS = 100

    @Volatile var engineStartedAt: Long = 0L; private set
    @Volatile var lastTickAt: Long = 0L; private set
    @Volatile var lastRearmAt: Long = 0L; private set

    private val events = Collections.synchronizedList(ArrayList<HeartbeatEvent>())

    /** Engine (re)started — the matchers are armed and the tick loop is (re)launched. */
    fun markEngineStart(now: Long = System.currentTimeMillis()) {
        engineStartedAt = now
        lastTickAt = now
        add(HeartbeatEvent(now, HeartbeatEvent.Kind.STARTED, 0L))
    }

    /** A `sun_tick` was emitted — the engine is alive this minute. */
    fun markTick(now: Long = System.currentTimeMillis()) {
        lastTickAt = now
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        lastTickAt > 0L && now - lastTickAt > STALE_THRESHOLD_MS

    /** The alarm found the engine stale and re-armed it. */
    fun markRearm(now: Long = System.currentTimeMillis()) {
        val staleMs = if (lastTickAt > 0L) now - lastTickAt else 0L
        lastRearmAt = now
        lastTickAt = now // reset so we don't immediately re-arm again
        add(HeartbeatEvent(now, HeartbeatEvent.Kind.REARMED, staleMs))
    }

    /** The alarm found the foreground service itself gone and restarted it (the +92 resurrect). */
    fun markResurrect(now: Long = System.currentTimeMillis()) {
        add(HeartbeatEvent(now, HeartbeatEvent.Kind.RESURRECTED, 0L))
    }

    fun events(): List<HeartbeatEvent> = synchronized(events) { events.toList() }

    private fun add(e: HeartbeatEvent) {
        synchronized(events) {
            events.add(0, e)
            while (events.size > MAX_EVENTS) events.removeAt(events.lastIndex)
        }
    }

    data class HeartbeatEvent(val atMs: Long, val kind: Kind, val staleMs: Long) {
        enum class Kind { STARTED, REARMED, RESURRECTED }
    }
}
