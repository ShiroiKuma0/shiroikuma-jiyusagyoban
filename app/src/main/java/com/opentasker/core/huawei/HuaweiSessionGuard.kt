package com.opentasker.core.huawei

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The three promises a band session must keep, whatever the transport does.
 *
 *  1. **It returns.** Not "usually"; always, within a bound the caller states.
 *  2. **It closes the link.** On success, on failure, on timeout, and — the one that was missing —
 *     when the coroutine that started it has been cancelled.
 *  3. **It releases the lock.** The band serves one connection, so the lock is process-wide, and a
 *     lock left held turns every later Huawei task into "a sync is already running" for the life of
 *     the process.
 *
 * This lives apart from [HuaweiSyncRunner] because that file holds `Context` and the radio and so
 * cannot be unit-tested at all, and these three promises are precisely the ones that were broken
 * without anything noticing. Here they are exercised against fake transports that misbehave in the
 * ways the real band does: one that never answers, one whose write only a close can unblock, one
 * whose caller gives up half way.
 *
 * ## Why a watchdog AND a timeout, when either looks sufficient
 *
 * They break different things and neither breaks both.
 *
 * `withTimeoutOrNull` cancels at a **suspension point**. A coroutine parked in a blocking socket
 * write never reaches one, so the timeout waits for exactly the thing it is meant to interrupt.
 *
 * Closing the socket is what unparks blocking I/O — the call throws and the stack unwinds. But
 * closing it does nothing to a loop that keeps politely polling a dead transport, which is what the
 * watch-face pump used to do for the remainder of its budget.
 *
 * So: the watchdog closes the link at [timeoutMs] to make the block cancellable, and the timeout
 * fires [graceMs] later to cancel it. A block that survives both is stuck in something no close can
 * reach, and no wrapper at this layer could have saved it.
 */
object HuaweiSessionGuard {

    /**
     * How long the block is given to notice the link went away and unwind, after the watchdog has
     * closed it. Seconds rather than milliseconds: a pump waiting on a read with a three-second
     * timeout has to be allowed to come back round.
     */
    const val GRACE_MS = 5_000L

    /** The lock was already held. Deliberately not queued — see [HuaweiSyncRunner]. */
    class Busy : IllegalStateException("a sync is already running")

    /** The block outlasted its own ceiling even after the link was closed under it. */
    class Stalled(val timeoutMs: Long) : IllegalStateException(
        "the band stopped answering — gave up after ${timeoutMs / 1000}s and hung up",
    )

    /** So a block whose result is legitimately null is not mistaken for a timeout. */
    private class Held<T>(val value: T)

    /**
     * Run [block] holding [lock] and owning [transport], and keep the three promises above.
     *
     * @param watchdogs a scope that does NOT belong to the caller. This is load-bearing: a watchdog
     *   launched as a child of the call it guards is cancelled by the very cancellation it exists to
     *   clean up after, and a caller that gives up while parked in a blocking write would then be
     *   left with nothing able to close the socket — a permanent hang, unreachable by anything short
     *   of killing the process.
     */
    suspend fun <T> guard(
        lock: Mutex,
        transport: HuaweiTransport,
        timeoutMs: Long,
        watchdogs: CoroutineScope,
        graceMs: Long = GRACE_MS,
        block: suspend () -> T,
    ): Result<T> {
        if (!lock.tryLock()) return Result.failure(Busy())
        val watchdog = watchdogs.launch {
            delay(timeoutMs)
            runCatching { transport.close() }
        }
        return try {
            val held = withTimeoutOrNull(timeoutMs + graceMs) { Held(block()) }
            if (held == null) Result.failure(Stalled(timeoutMs)) else Result.success(held.value)
        } catch (e: CancellationException) {
            // The CALLER gave up. Propagate rather than dressing it as a Result: a coroutine that
            // swallows its own cancellation goes on running as though nothing happened. The finally
            // below still closes and unlocks, which is the part that actually matters.
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            watchdog.cancel()
            // NonCancellable, and this is the whole bug that wedged the app.
            //
            // `withContext` calls `ensureActive()` BEFORE it runs anything, so any close that hops
            // dispatchers — and the real one hops to `Dispatchers.IO` — throws instead of closing
            // once the coroutine has been cancelled. Wrapped in `runCatching`, as cleanup usually
            // is, that failure is silent and total: the socket stays open, the band serves one
            // connection, and every later attempt blocks on a link we are still holding ourselves.
            // Only force-stopping the app cleared it, which is exactly how it looked from outside.
            //
            // Closing BEFORE unlocking, not after: the lock is what stops a second session starting,
            // so releasing it while this one still owns the socket would hand the next caller a band
            // we have not let go of yet.
            withContext(NonCancellable) { runCatching { transport.close() } }
            lock.unlock()
        }
    }
}
