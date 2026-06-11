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
import kotlinx.coroutines.launch

/**
 * Real StateContextSource implementation using BroadcastReceivers.
 *
 * Matches predicates like:
 *   - "battery_level>=80" (battery percentage)
 *   - "charging=true" (is device charging)
 *   - "headphones=connected" or "headphones=true" (headphones plugged in)
 *   - "screen=on" (display state)
 */
class StateContextSourceImpl : ContextSource {
    override val type = "state"

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        var lastState: Map<String, String> = emptyMap()

        fun publishPatch(statePatch: Map<String, String>) {
            val mergedState = mergeStatePatch(lastState, statePatch)
            if (mergedState != lastState) {
                lastState = mergedState
                trySend(ContextEvent(type, true, mergedState))
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val statePatch = mutableMapOf<String, String>()

                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                            it == BatteryManager.BATTERY_STATUS_CHARGING ||
                            it == BatteryManager.BATTERY_STATUS_FULL
                        }
                        statePatch["battery_level"] = level.toString()
                        statePatch["charging"] = isCharging.toString()
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", 0)
                        statePatch["headphones"] = (state == 1).toString()
                    }
                    Intent.ACTION_SCREEN_ON -> statePatch["screen"] = "on"
                    Intent.ACTION_SCREEN_OFF -> statePatch["screen"] = "off"
                }

                publishPatch(statePatch)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val deviceStateJob = launch {
            DeviceStateEvents.events.collect(::publishPatch)
        }

        awaitClose {
            deviceStateJob.cancel()
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

    val normalizedKey = normalizeStateKey(key)
    if (normalizedKey == "wifi") return wifiMatches(op, value, state)

    val actualValue = state[normalizedKey] ?: return false
    val expectedValue = normalizeStateExpectedValue(normalizedKey, value.trim()) ?: return false

    return when (op) {
        "=" -> actualValue == expectedValue
        ">=" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual >= expected }
        "<=" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual <= expected }
        ">" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual > expected }
        "<" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual < expected }
        else -> false
    }
}

internal fun mergeStatePatch(
    currentState: Map<String, String>,
    patch: Map<String, String>,
): Map<String, String> {
    if (patch.isEmpty()) return currentState
    return currentState + patch
}

internal fun normalizeStateKey(key: String): String = when (key.trim().lowercase()) {
    "battery" -> "battery_level"
    "headset" -> "headphones"
    "ssid", "wifi_ssid" -> "wifi"
    else -> key.trim().lowercase()
}

private fun normalizeStateExpectedValue(key: String, value: String): String? {
    val normalized = value.trim().lowercase()
    return when (key) {
        "headphones" -> when (normalized) {
            "connected", "plugged", "plugged_in", "on", "true", "yes" -> "true"
            "disconnected", "unplugged", "off", "false", "no" -> "false"
            else -> null
        }
        "charging" -> when (normalized) {
            "charging", "plugged", "plugged_in", "on", "true", "yes" -> "true"
            "discharging", "not_charging", "unplugged", "off", "false", "no" -> "false"
            else -> null
        }
        "screen" -> when (normalized) {
            "on", "off" -> normalized
            else -> null
        }
        else -> value.trim()
    }
}

private fun wifiMatches(
    op: String,
    expectedRaw: String,
    state: Map<String, String>,
): Boolean {
    if (op != "=") return false
    val expected = expectedRaw.trim()
    val normalizedExpected = expected.lowercase()
    val connected = state["wifi_connected"]?.toBooleanStrictOrNull()
    val actualSsid = state["wifi"]?.takeUnless { it.equals("disconnected", ignoreCase = true) }
        ?: state["wifi_ssid"].orEmpty()

    return when (normalizedExpected) {
        "connected", "on", "true", "yes" -> connected == true
        "disconnected", "off", "false", "no" -> connected == false
        else -> actualSsid == expected
    }
}

private inline fun numericCompare(
    actualValue: String,
    expectedValue: String,
    compare: (actual: Int, expected: Int) -> Boolean,
): Boolean {
    val actual = actualValue.toIntOrNull() ?: return false
    val expected = expectedValue.toIntOrNull() ?: return false
    return compare(actual, expected)
}
