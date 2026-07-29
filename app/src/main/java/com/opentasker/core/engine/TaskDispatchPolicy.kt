package com.opentasker.core.engine

import com.opentasker.core.model.AutomationMode

/** What the service must do with a dispatch request, once the mode rules have been applied. */
enum class DispatchStep {
    /** Launch a tracked job in the profile's slot. */
    START,

    /** Cancel the slot's current job, then launch a tracked job in its place. */
    RESTART,

    /** Launch the queue consumer with this task as its head. */
    START_QUEUE,

    /** Append to the slot's queue; the running consumer will drain it. */
    ENQUEUE,

    /** Launch an untracked job that runs alongside any in-flight run. */
    LAUNCH_PARALLEL,

    /** SINGLE mode and the slot is busy. */
    SKIP_ALREADY_RUNNING,

    /** QUEUED mode and the queue is at its cap. */
    SKIP_QUEUE_FULL,
}

/**
 * [reservesCooldown] is deliberately part of the plan rather than a separate check: cooldown is
 * consumed only where a *fresh* run actually starts, so queuing behind a running task and running a
 * profile's exit task must not burn it.
 */
data class DispatchPlan(
    val step: DispatchStep,
    val reservesCooldown: Boolean,
) {
    val startsRun: Boolean
        get() = step != DispatchStep.SKIP_ALREADY_RUNNING &&
            step != DispatchStep.SKIP_QUEUE_FULL &&
            step != DispatchStep.ENQUEUE
}

/**
 * The pure decision behind `AutomationService.dispatchTask`: given a profile's automation mode and
 * the current state of its execution slot, what should happen?
 *
 * Extracted from the service so the concurrency rules that are hardest to get right — SINGLE
 * suppression, RESTART preemption, the QUEUED cap, exit tasks never consuming cooldown — can be
 * asserted directly instead of only through a running foreground service.
 */
object TaskDispatchPolicy {

    fun plan(
        mode: AutomationMode,
        isExit: Boolean,
        slotActive: Boolean,
        queuedCount: Int = 0,
        queueCap: Int = Int.MAX_VALUE,
    ): DispatchPlan {
        // Exit tasks are cleanup: they run in their own slot and never consume the profile's
        // cooldown, which exists to rate-limit re-triggering, not to suppress teardown.
        val reserves = !isExit

        return when (mode) {
            AutomationMode.SINGLE ->
                if (slotActive) {
                    DispatchPlan(DispatchStep.SKIP_ALREADY_RUNNING, reservesCooldown = false)
                } else {
                    DispatchPlan(DispatchStep.START, reserves)
                }

            AutomationMode.RESTART -> DispatchPlan(DispatchStep.RESTART, reserves)

            AutomationMode.QUEUED ->
                if (slotActive) {
                    if (queuedCount >= queueCap) {
                        DispatchPlan(DispatchStep.SKIP_QUEUE_FULL, reservesCooldown = false)
                    } else {
                        DispatchPlan(DispatchStep.ENQUEUE, reservesCooldown = false)
                    }
                } else {
                    DispatchPlan(DispatchStep.START_QUEUE, reserves)
                }

            AutomationMode.PARALLEL -> DispatchPlan(DispatchStep.LAUNCH_PARALLEL, reserves)
        }
    }
}
