package com.opentasker.core.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.opentasker.core.contexts.ApplicationContextEvents
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * `Get Device State` — read battery / charging / WiFi / airplane into variables. No special
 * permissions (battery via the sticky ACTION_BATTERY_CHANGED, the rest via system services/settings).
 *
 * Each arg names the variable to receive that value:
 *   - `battery`  → percentage, zero-padded to 2 digits ("05", "83") or "100" when full.
 *   - `charging` → "true" / "false".
 *   - `wifi`     → "true" / "false" (WiFi enabled).
 *   - `airplane` → "true" / "false".
 *   - `screen`   → "on" / "off".
 *   - `app`      → the foreground app's package name ("" if unknown).
 */
class StateGetAction : Action {
    override val id = "state.get"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val app = ctx.app
        fun store(key: String, value: String) {
            args[key]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() }?.let { ctx.variables.set(it, value) }
        }

        // Battery (sticky broadcast — no receiver registration cost).
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        // Charging = a power source is connected. EXTRA_PLUGGED covers AC / USB / WIRELESS and drops to 0
        // the instant you unplug. Verified via a REAL unplug (白い熊): all "powered" flags → false while
        // EXTRA_STATUS and BatteryManager.isCharging LINGER at charging on this Huawei — so trusting those
        // stuck %CHARGING true and the 電池線 sweep never stopped. Trust ONLY EXTRA_PLUGGED.
        val charging = plugged != 0
        store("battery", if (pct >= 100) "100" else "%02d".format(pct.coerceIn(0, 99)))
        store("charging", charging.toString())

        // WiFi enabled.
        val wifi = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
        store("wifi", wifi.toString())

        // Airplane mode.
        val airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        store("airplane", airplane.toString())

        // Screen on/off (interactive) — "on" / "off". Used by the tsuchi to pick passthrough vs wakedance.
        val screenOn = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        store("screen", if (screenOn) "on" else "off")

        // Foreground app package — for branching on the current app (e.g. physical-key per-app remaps).
        // Sourced from the usage monitor (needs the engine running + PACKAGE_USAGE_STATS); "" if unknown.
        val foreground = ApplicationContextEvents.current
        store("app", foreground)

        ctx.logger("State: battery=$pct% charging=$charging wifi=$wifi airplane=$airplane screen=${if (screenOn) "on" else "off"} app=$foreground")
        return ActionResult.Success
    }
}
