package com.opentasker.core.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
    /** The deadline this reservation armed, or 0 when it armed none. */
    val deadlineMs: Long = 0,
    /** The deadline it replaced, which [CooldownReservations.release] puts back. */
    val previousDeadlineMs: Long = 0,
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
            return CooldownReservation(accepted = true, deadlineMs = next, previousDeadlineMs = deadline)
        }
        return CooldownReservation(accepted = true)
    }

    /**
     * Puts back the deadline [reservation] replaced, for a run that never started. Does nothing
     * once a later reservation or a [clear] has replaced it, so it can't undo someone else's window.
     */
    fun release(profileId: Long, reservation: CooldownReservation) = synchronized(deadlines) {
        if (reservation.deadlineMs == 0L || deadlines[profileId] != reservation.deadlineMs) return@synchronized
        if (reservation.previousDeadlineMs > 0L) {
            deadlines[profileId] = reservation.previousDeadlineMs
        } else {
            deadlines.remove(profileId)
        }
        persist(profileId, reservation.previousDeadlineMs)
    }
}

/**
 * The cooldown one dispatch armed. The dispatch reserves it before admission runs, so a run that
 * admission refuses (open circuit, burst or active limit) or that is canceled before it gets that
 * far gives it back: nothing ran, and the next trigger shouldn't read "on cooldown".
 */
class CooldownClaim(
    private val reservations: CooldownReservations,
    private val profileId: Long,
    private val reservation: CooldownReservation,
) {
    private val settled = AtomicBoolean(false)

    /** The run got past admission, so the cooldown stands. */
    fun admitted() = settled.set(true)

    /** Gives the cooldown back unless [admitted] came first. Later calls do nothing. */
    fun releaseUnlessAdmitted() {
        if (settled.compareAndSet(false, true)) reservations.release(profileId, reservation)
    }
}

/**
 * Gives [claim]'s cooldown back when this job ends without an admitted run. A job canceled before
 * it starts never runs its body, so only its completion can do that.
 */
fun Job.givingBack(claim: CooldownClaim?): Job = apply {
    if (claim != null) invokeOnCompletion { claim.releaseUnlessAdmitted() }
}
