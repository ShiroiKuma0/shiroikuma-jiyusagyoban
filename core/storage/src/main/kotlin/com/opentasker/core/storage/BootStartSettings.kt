package com.opentasker.core.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the engine comes back by itself after a device reboot. **On by default** — that is the
 * long-standing behaviour ([com.opentasker.core.engine.BootReceiver] starting the service on
 * BOOT_COMPLETED), and the one 白い熊 wants; this only makes it switchable from the Monitor screen.
 *
 * Turning it OFF also means a reboot does *not* undo an "Exit app fully": the shutdown flag
 * ([com.opentasker.core.engine.EngineShutdown]) survives, and nothing runs until the app is opened by
 * hand. With it ON, boot clears the flag and starts the engine as usual.
 */
object BootStartSettings {
    private const val PREFS = "boot_start_settings"
    private const val KEY = "enabled"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    /** Direct prefs read — for [com.opentasker.core.engine.BootReceiver], which has no loaded state. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY, true)

    fun load(context: Context) { _enabled.value = isEnabled(context) }

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
        _enabled.value = enabled
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
