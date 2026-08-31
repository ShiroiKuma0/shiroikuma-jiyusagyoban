package com.opentasker.core.huawei

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a band session must do when the band does NOT behave.
 *
 * The happy path is already covered by [HuaweiWatchFaceReplayTest] and nothing here repeats it.
 * These are the cases that actually cost an evening: a link that answers nothing, a write only a
 * close can unblock, and a caller that gives up half way. Each of them wedged the app — the sync
 * lock left held, or worse the BAND left held, since it serves one connection — and none could be
 * reproduced without the hardware until the lifecycle moved out of the Android layer and into
 * [HuaweiSessionGuard].
 *
 * The time bounds below are loose on purpose. They are not performance assertions; they are the
 * difference between "returned" and "hung", which is the only distinction under test.
 */
class HuaweiSessionGuardTest {

    /** Watchdogs, as production gives them: a scope that does not belong to any caller. */
    private val watchdogs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Sessions under test run here rather than on the test's own `runBlocking` scope, so that a
     * session which fails to come back fails the test instead of hanging it — `runBlocking` waits
     * for its children, and a hang is precisely what several of these guard against.
     */
    private val subjects = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        subjects.cancel()
        watchdogs.cancel()
    }

    /**
     * A band that is connected and says nothing — the commonest real failure.
     *
     * Closes the way the REAL transport closes: by hopping to another dispatcher. That detail is
     * load-bearing for `a session cancelled by its caller still closes the link`, so it is modelled
     * here rather than reduced to setting a flag.
     */
    private open class SilentTransport : HuaweiTransport {
        var closes = 0
            private set
        val closed: Boolean get() = closes > 0

        override suspend fun write(data: ByteArray) = Unit

        override suspend fun read(timeoutMs: Long): ByteArray? {
            delay(timeoutMs)
            return null
        }

        override suspend fun close() {
            withContext(Dispatchers.Default) { closes++ }
        }
    }

    /**
     * A transport whose write parks until the link is closed.
     *
     * `NonCancellable` here is not decoration: it is what makes this a faithful model of
     * `OutputStream.write()` on a socket. A blocking JVM call cannot be cancelled — that is the
     * whole reason a session needs a watchdog that closes the socket rather than a timeout that asks
     * politely — and a fake that merely `delay`ed would be cancellable, so it would pass with no
     * watchdog at all and prove nothing.
     */
    private class ParkingWriteTransport : SilentTransport() {
        private val released = CompletableDeferred<Unit>()

        override suspend fun write(data: ByteArray) {
            withContext(NonCancellable) { released.await() }
            throw IOException("socket closed under the write")
        }

        override suspend fun close() {
            released.complete(Unit)
            super.close()
        }
    }

    @Test
    fun `a band that never answers ends the session instead of holding it`() = runBlocking {
        val lock = Mutex()
        val band = SilentTransport()

        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(10_000) {
            HuaweiSessionGuard.guard(lock, band, timeoutMs = 200, watchdogs = watchdogs, graceMs = 200) {
                while (true) band.read(50)
            }
        }
        val elapsed = System.currentTimeMillis() - started

        assertNotNull("the session never came back — this is the hang, reproduced", result)
        assertTrue("a band that says nothing is a failure, not a success", result!!.isFailure)
        assertTrue("it must give up on its own clock, not the caller's: ${elapsed}ms", elapsed < 5_000)
        assertTrue("the link was left open", band.closed)
        assertTrue("the sync lock was left held", lock.tryLock())
    }

    @Test
    fun `a write that only a close can unblock still ends the session`() = runBlocking {
        val lock = Mutex()
        val band = ParkingWriteTransport()

        val result = withTimeoutOrNull(10_000) {
            HuaweiSessionGuard.guard(lock, band, timeoutMs = 200, watchdogs = watchdogs, graceMs = 200) {
                band.write(byteArrayOf(1, 2, 3))
            }
        }

        assertNotNull("a parked write hung the session; only closing the link can break one", result)
        assertTrue("a write that never got out is a failure", result!!.isFailure)
        assertTrue("the sync lock was left held", lock.tryLock())
    }

    @Test
    fun `a session cancelled by its caller still closes the link`() = runBlocking {
        // THE regression test. Cleanup that hops dispatchers — `withContext(Dispatchers.IO) { … }`,
        // which is exactly how the real transport closes — throws instead of running once the
        // coroutine has been cancelled, because `withContext` calls `ensureActive()` first. Wrapped
        // in the `runCatching` that cleanup usually gets, that is silent and total: the socket stays
        // open, and since the band serves ONE connection every later attempt then fails identically
        // until the app is force-stopped. Releasing the lock was never the problem; the link was.
        val lock = Mutex()
        val band = SilentTransport()
        val running = CompletableDeferred<Unit>()

        val job = subjects.launch {
            HuaweiSessionGuard.guard(lock, band, timeoutMs = 30_000, watchdogs = watchdogs) {
                running.complete(Unit)
                while (true) band.read(20)
            }
        }
        running.await()
        job.cancelAndJoin()

        assertTrue("a cancelled session left the band connected to nobody", band.closed)
        assertTrue("a cancelled session left the sync lock held", lock.tryLock())
    }

    @Test
    fun `a cancelled session parked in a write is still broken free`() = runBlocking {
        // Why the watchdog may not be a child of the call it guards. Cancel a session parked in an
        // uninterruptible write and a child watchdog dies first, leaving nothing able to close the
        // socket — the coroutine can then never finish, and neither can anything waiting on it.
        // `join()` returning here IS the assertion.
        val lock = Mutex()
        val band = ParkingWriteTransport()
        val running = CompletableDeferred<Unit>()

        val job = subjects.launch {
            HuaweiSessionGuard.guard(lock, band, timeoutMs = 300, watchdogs = watchdogs) {
                running.complete(Unit)
                band.write(byteArrayOf(9))
            }
        }
        running.await()
        job.cancel()

        withTimeout(10_000) { job.join() }
        assertTrue("the link was never closed, so the write could never come back", band.closed)
        assertTrue("the sync lock was left held", lock.tryLock())
    }

    @Test
    fun `a second session is refused at once rather than queued`() = runBlocking {
        val lock = Mutex()
        val first = SilentTransport()
        val second = SilentTransport()
        val running = CompletableDeferred<Unit>()

        val held = subjects.launch {
            HuaweiSessionGuard.guard(lock, first, timeoutMs = 5_000, watchdogs = watchdogs) {
                running.complete(Unit)
                while (true) first.read(50)
            }
        }
        running.await()

        val started = System.currentTimeMillis()
        val refused = HuaweiSessionGuard.guard(lock, second, timeoutMs = 30_000, watchdogs = watchdogs) {
            throw AssertionError("the second session must never reach the band")
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("a second session must be refused, not queued", refused.isFailure)
        assertTrue("refusing must be immediate, not a wait: ${elapsed}ms", elapsed < 1_000)
        assertTrue(
            "a refusal must say the band is busy, not something opaque: " +
                "${refused.exceptionOrNull()}",
            refused.exceptionOrNull() is HuaweiSessionGuard.Busy,
        )
        assertTrue("the refused session must not touch a link it never got", !second.closed)

        held.cancelAndJoin()
    }

    @Test
    fun `a session that fails inside the block still hangs up and unlocks`() = runBlocking {
        val lock = Mutex()
        val band = SilentTransport()

        val result = HuaweiSessionGuard.guard(lock, band, timeoutMs = 5_000, watchdogs = watchdogs) {
            throw IllegalStateException("not bound — pair the band first")
        }

        assertTrue(result.isFailure)
        assertEquals(
            "the block's own reason must survive, or the failure says nothing useful",
            "not bound — pair the band first",
            result.exceptionOrNull()?.message,
        )
        assertTrue("a failed session left the link open", band.closed)
        assertTrue("a failed session left the sync lock held", lock.tryLock())
    }

    @Test
    fun `a session that finishes hands back its value and lets the next one in`() = runBlocking {
        val lock = Mutex()
        val band = SilentTransport()

        val first = HuaweiSessionGuard.guard(lock, band, timeoutMs = 5_000, watchdogs = watchdogs) {
            "battery 62%"
        }
        assertEquals("battery 62%", first.getOrNull())
        assertTrue("the link must not be held after a session", band.closed)

        // A free lock is what the NEXT Huawei task depends on, so assert it by taking one.
        val second = HuaweiSessionGuard.guard(lock, SilentTransport(), 5_000, watchdogs) { "again" }
        assertEquals("again", second.getOrNull())
    }

    @Test
    fun `a block that answers null has succeeded, not stalled`() = runBlocking {
        // `withTimeoutOrNull` returns null both for "timed out" and for "the block returned null",
        // and several band queries legitimately answer null — an absent firmware string, a battery
        // the band would not report. Conflating the two would turn every one of those into a hang
        // report.
        val lock = Mutex()
        val result = HuaweiSessionGuard.guard(lock, SilentTransport(), 5_000, watchdogs) { null }
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }
}
