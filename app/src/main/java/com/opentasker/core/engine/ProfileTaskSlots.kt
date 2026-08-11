package com.opentasker.core.engine

import kotlinx.coroutines.Job

/**
 * Owns the per-profile execution slots and the lock that makes deciding and storing one step.
 *
 * [TaskDispatchPolicy] reads whether a slot is busy; the caller then writes the job it started.
 * Those two halves must not interleave. Today the matcher coroutine is the only producer, so no
 * live race exists — but the invariant was previously only a comment, and a second dispatch path
 * (an external run, a quick-settings tile, a widget) reaching the same slot would start SINGLE
 * twice and, in RESTART, leave the losing job running but untracked: [releaseIfCurrent] clears a
 * slot only when the finishing job is still the one recorded there, so nothing would ever clean it
 * up.
 *
 * The map's own monitor is the lock, so cleanup from a finishing job cannot slip between a
 * decision and its store either.
 */
internal class ProfileTaskSlots {

    private val jobs = mutableMapOf<Long, Job>()

    /**
     * Runs [block] with exclusive access to every slot.
     *
     * Callers may nest the queue lock inside this block but must never take this lock while
     * holding the queue lock — that ordering is what keeps the queue consumer deadlock-free.
     */
    fun <T> exclusively(block: (Access) -> T): T = synchronized(jobs) { block(Access()) }

    /** Clears [slot] only when [job] is still the job recorded there. */
    fun releaseIfCurrent(slot: Long, job: Job?) {
        synchronized(jobs) {
            if (jobs[slot] == job) {
                jobs.remove(slot)
            }
        }
    }

    /** Snapshot for shutdown, taken under the lock so teardown cannot miss a just-stored job. */
    fun snapshot(): List<Job> = synchronized(jobs) { jobs.values.toList() }

    fun clear() {
        synchronized(jobs) { jobs.clear() }
    }

    /** Slot operations that are only reachable while the lock is held. */
    inner class Access internal constructor() {
        fun isActive(slot: Long): Boolean = jobs[slot]?.isActive == true

        fun cancel(slot: Long) {
            jobs[slot]?.cancel()
        }

        fun store(slot: Long, job: Job) {
            jobs[slot] = job
        }

        /** Same identity check as [ProfileTaskSlots.releaseIfCurrent], for callers already inside the lock. */
        fun releaseIfCurrent(slot: Long, job: Job?) {
            if (jobs[slot] == job) {
                jobs.remove(slot)
            }
        }
    }
}
