package com.opentasker.core.contexts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Physical hardware-key events ("hardware_key"), fed by [com.opentasker.core.input.ShizukuKeyEventListener]
 * which reads `/dev/input` via Shizuku (uid 2000) and so sees presses even while the screen is off and
 * for the power button — below the framework's "ignore keys while screen off" policy.
 *
 * A profile EVENT context narrows on:
 *   - `key`   → one of [KEY_VOLUME_UP] / [KEY_VOLUME_DOWN] / [KEY_POWER]
 *   - `press` → one of [PRESS_SHORT] / [PRESS_LONG] / [PRESS_DOUBLE]
 * (matched in [ContextMatchEvaluator.matchesEvent]). Omitting a narrower fires on any value.
 *
 * Each event also exposes per-invocation vars `%HW_KEY`, `%HW_PRESS`, `%HW_KEYCODE` to the fired task.
 */
object HardwareKeyContextEvents {
    const val EVENT = "hardware_key"

    const val KEY_VOLUME_UP = "volume_up"
    const val KEY_VOLUME_DOWN = "volume_down"
    const val KEY_POWER = "power"

    const val PRESS_SHORT = "short"
    const val PRESS_LONG = "long"
    const val PRESS_DOUBLE = "double"
    const val PRESS_TRIPLE = "triple"

    private val keys = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 32,
    )

    val events: SharedFlow<ContextEvent> = keys.asSharedFlow()

    /** Publish a classified key gesture. [keyName]/[press] use the constants above; [keyCode] is the Android keycode. */
    fun publish(keyName: String, press: String, keyCode: Int): Boolean =
        keys.tryEmit(
            ContextEvent(
                type = "event",
                matched = true,
                metadata = mapOf(
                    "event" to EVENT,
                    "key" to keyName,
                    "press" to press,
                ),
                vars = mapOf(
                    "HW_KEY" to keyName,
                    "HW_PRESS" to press,
                    "HW_KEYCODE" to keyCode.toString(),
                ),
            ),
        )
}
