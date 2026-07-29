package com.opentasker.core.engine

data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
)

/**
 * Per-profile cooldown deadlines with atomic check-and-reserve.
 *
 * The check and the write have to happen under one lock: two contexts matching the same profile in
 * the same instant would otherwise both read an expired deadline and both start a run, which is
 * exactly the re-trigger storm the cooldown exists to prevent.
 *
 * [now] and [persist] are injectable so the reservation rules can be tested without a running
 * service or SharedPreferences.
 */
class CooldownReservations(
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (profileId: Long, deadlineMs: Long) -> Unit = { _, _ -> },
) {
    private val deadlines = mutableMapOf<Long, Long>()

    /** Restores persisted deadlines after a process restart. */
    fun seed(persisted: Map<Long, Long>) = synchronized(deadlines) {
        deadlines.putAll(persisted)
    }

    fun clear() = synchronized(deadlines) { deadlines.clear() }

    fun snapshot(): Map<Long, Long> = synchronized(deadlines) { deadlines.toMap() }

    /**
     * Accepts and arms the next cooldown window in one step, or rejects with the time left.
     * A profile with no configured cooldown is always accepted and never armed.
     */
    fun reserve(profileId: Long, cooldownSec: Int): CooldownReservation = synchronized(deadlines) {
        val current = now()
        val deadline = deadlines[profileId] ?: 0L
        if (current < deadline) {
            return CooldownReservation(accepted = false, remainingMs = deadline - current)
        }
        if (cooldownSec > 0) {
            val next = current + cooldownSec * 1000L
            deadlines[profileId] = next
            persist(profileId, next)
        }
        return CooldownReservation(accepted = true)
    }
}
