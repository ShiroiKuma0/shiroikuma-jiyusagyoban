package com.sysadmindoc.ruledeck.core.engine

import android.content.Context
import android.os.PowerManager
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The partial wakelock one task run may ask for (`power.keep_awake`), and that is given back when
 * that run ends.
 *
 * The engine holds no wakelock of its own — an idle phone is free to deep-sleep (白い熊). The other
 * side of that: a run that an alarm or a broadcast started keeps the CPU only for an instant, and
 * every wait, retry pause and timeout in it is counted on a clock that stands still while the CPU
 * sleeps. On an unplugged relay phone a 240 s limit took 18 minutes that way (2026-11-24), with the
 * whole relay queued behind it. A run that has to finish in real time says so; nothing else changes.
 *
 * Travels in the coroutine context from the outermost run, so a task called by `task.run` asks for
 * the same lock, and the outermost run's end releases it however the run ended.
 */
class RunAwake : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunAwake> {
        const val MAX_SECONDS = 3_600
        private const val TAG = "opentasker:run-awake"
    }

    private var lock: PowerManager.WakeLock? = null

    /** Stay awake for [seconds] from now, replacing whatever was asked before; 0 lets go at once. */
    @Synchronized
    fun hold(context: Context, seconds: Int): Boolean {
        if (seconds <= 0) {
            release()
            return true
        }
        return runCatching {
            val held = lock ?: (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
                .apply { setReferenceCounted(false) }
                .also { lock = it }
            // Not reference-counted: a new acquire replaces the earlier time limit.
            held.acquire(seconds.coerceAtMost(MAX_SECONDS) * 1_000L)
        }.isSuccess
    }

    @Synchronized
    fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }

    val isHeld: Boolean
        @Synchronized get() = lock?.isHeld == true
}
