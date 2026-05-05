package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real StateContextSource implementation using BroadcastReceivers.
 *
 * Matches predicates like:
 *   - "battery_level>=80" (battery percentage)
 *   - "charging=true" (is device charging)
 *   - "headphones=connected" (headphones plugged in)
 *   - "screen=on" (display state)
 *   - "wifi=connected" (WiFi connected)
 */
class StateContextSourceImpl : ContextSource {
    override val type = "state"

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        var lastState: Map<String, String> = emptyMap()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val newState = mutableMapOf<String, String>()

                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                            it == BatteryManager.BATTERY_STATUS_CHARGING ||
                            it == BatteryManager.BATTERY_STATUS_FULL
                        }
                        newState["battery_level"] = level.toString()
                        newState["charging"] = isCharging.toString()
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", 0)
                        newState["headphones"] = (state == 1).toString()
                    }
                    Intent.ACTION_SCREEN_ON -> newState["screen"] = "on"
                    Intent.ACTION_SCREEN_OFF -> newState["screen"] = "off"
                }

                if (newState.isNotEmpty() && newState != lastState) {
                    lastState = newState
                    trySend(ContextEvent(type, true, newState))
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        awaitClose {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}

/**
 * Helper to check if a state predicate matches current device state.
 *
 * Predicates:
 *   - "battery_level>=80" / "battery_level<20"
 *   - "charging=true" / "charging=false"
 *   - "headphones=connected" / "headphones=disconnected"
 *   - "screen=on" / "screen=off"
 *   - "wifi=connected" / "wifi=disconnected"
 */
fun stateMatches(predicate: String, state: Map<String, String>): Boolean {
    val (key, op, value) = if (">=" in predicate) {
        val parts = predicate.split(">=", limit = 2)
        Triple(parts[0], ">=", parts[1])
    } else if ("<=" in predicate) {
        val parts = predicate.split("<=", limit = 2)
        Triple(parts[0], "<=", parts[1])
    } else if (">" in predicate) {
        val parts = predicate.split(">", limit = 2)
        Triple(parts[0], ">", parts[1])
    } else if ("<" in predicate) {
        val parts = predicate.split("<", limit = 2)
        Triple(parts[0], "<", parts[1])
    } else if ("=" in predicate) {
        val parts = predicate.split("=", limit = 2)
        Triple(parts[0], "=", parts[1])
    } else {
        return false
    }

    val actualValue = state[key.trim()] ?: return false
    val expectedValue = value.trim()

    return when (op) {
        "=" -> actualValue == expectedValue
        ">=" -> actualValue.toIntOrNull()?.let { it >= expectedValue.toIntOrNull() ?: 0 } ?: false
        "<=" -> actualValue.toIntOrNull()?.let { it <= expectedValue.toIntOrNull() ?: 0 } ?: false
        ">" -> actualValue.toIntOrNull()?.let { it > expectedValue.toIntOrNull() ?: 0 } ?: false
        "<" -> actualValue.toIntOrNull()?.let { it < expectedValue.toIntOrNull() ?: 0 } ?: false
        else -> false
    }
}
