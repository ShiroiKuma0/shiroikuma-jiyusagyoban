package com.opentasker.core.actions

import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands out collision-free request codes for PendingIntents.
 *
 * Deriving a request code from a user-controllable value (e.g. a notification id) risks two
 * distinct PendingIntents sharing a code, so with `FLAG_UPDATE_CURRENT` a newer notification can
 * silently overwrite an older button intent and fire the wrong task. A process-wide monotonic
 * counter guarantees every allocated code is unique for the life of the process.
 */
object PendingIntentRequestCodes {
    // Start above the small fixed codes other call sites use (0, widget ids) so allocations never
    // collide with them either.
    private val counter = AtomicInteger(100_000)

    /** Returns the next unique, always-positive request code. */
    fun next(): Int = counter.getAndIncrement() and 0x7FFFFFFF
}
