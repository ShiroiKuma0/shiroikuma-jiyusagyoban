package com.opentasker.core.engine

import com.opentasker.core.contexts.ContextEvent
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps event-pulse identity stable while a profile matcher is rebuilt.
 *
 * Most event buses are non-replayed, so a sequence must outlive the matcher that observed it.
 * Push/share/boot events additionally have a short replay window; their stable metadata key lets
 * the replacement matcher ignore the replay while still delivering the event to each context
 * slot that needs it.
 */
internal class PulseEventContinuity(initialSequence: Long = 0L) {
    private val sequence = AtomicLong(initialSequence)
    private val replayed = LinkedHashMap<String, ReplayPulse>()

    fun currentSequence(): Long = sequence.get()

    @Synchronized
    fun observe(contextIndex: Int, event: ContextEvent): PulseObservation {
        val replayKey = replayKey(event)
        if (replayKey == null) {
            return PulseObservation(sequence.incrementAndGet(), duplicate = false)
        }

        val existing = replayed[replayKey]
        if (existing == null) {
            val observation = ReplayPulse(sequence.incrementAndGet())
            observation.contextIndexes += contextIndex
            replayed[replayKey] = observation
            trimReplayHistory()
            return PulseObservation(observation.sequence, duplicate = false)
        }

        val duplicate = !existing.contextIndexes.add(contextIndex)
        return PulseObservation(existing.sequence, duplicate)
    }

    private fun trimReplayHistory() {
        while (replayed.size > MAX_REPLAY_HISTORY) {
            replayed.remove(replayed.entries.first().key)
        }
    }

    private fun replayKey(event: ContextEvent): String? {
        val eventName = event.metadata["event"] ?: return null
        val stableId = event.metadata["eventId"] ?: event.metadata["observedAtEpochMs"] ?: return null
        return "$eventName\u0000$stableId"
    }

    private class ReplayPulse(val sequence: Long) {
        val contextIndexes = mutableSetOf<Int>()
    }

    private companion object {
        const val MAX_REPLAY_HISTORY = 256
    }
}

internal data class PulseObservation(
    val sequence: Long,
    val duplicate: Boolean,
)
