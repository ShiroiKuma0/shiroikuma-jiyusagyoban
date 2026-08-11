package com.opentasker.core.contexts

import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Emits a one-shot context pulse, reporting the case where it was not delivered.
 *
 * These buses are `MutableSharedFlow` with a bounded extra buffer, so `tryEmit` returns false when
 * the buffer is full behind a busy matcher — and every call site discarded that boolean. A dropped
 * NFC tap, notification or SMS then looked identical to one that never happened, with nothing in
 * the log to distinguish "the trigger did not fire" from "the trigger fired and was thrown away".
 *
 * This does not make delivery reliable; it makes the loss visible. Replay for the bridges that
 * still lack it is tracked separately.
 */
internal fun <T> MutableSharedFlow<T>.tryEmitPulse(source: String, event: T): Boolean {
    val delivered = tryEmit(event)
    if (!delivered) {
        AppLogger.warn(
            "OpenTasker.Pulse",
            "Dropped a $source pulse: the subscriber buffer was full. The trigger did not run.",
        )
    }
    return delivered
}
