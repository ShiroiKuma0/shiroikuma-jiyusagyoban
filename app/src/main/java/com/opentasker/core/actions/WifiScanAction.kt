package com.opentasker.core.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Report the access points the platform can currently see.
 *
 * Deliberately honest about two things Android does not let an app control. Since Android 9 the
 * platform throttles `startScan` (four calls per two minutes for a foreground app, and background
 * scanning is off entirely when Wi-Fi scanning throttling is on), and `getScanResults` returns the
 * last cached scan whether or not a new one ran. So this action never claims to have taken a fresh
 * scan: it asks for one, says whether the request was accepted, and reports the cache with the age
 * of the newest entry so an automation can decide whether that is good enough.
 *
 * BSSIDs identify a physical access point and are treated as sensitive: they go into the output
 * variables so a task can use them, and are masked wherever arguments and variables are displayed.
 */
class WifiScanAction : DeclaredAction(ActionCatalog.require("wifi.scan")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val varName = (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: DEFAULT_VARIABLE
        val limit = args["limit"]?.trim()?.toIntOrNull()?.coerceIn(1, MAX_RESULTS) ?: DEFAULT_RESULTS

        val missing = missingPermissions(ctx.app)
        if (missing.isNotEmpty()) {
            return ActionResult.Failure("wifi.scan needs ${missing.joinToString()} before it can read scan results")
        }

        val wifi = ctx.app.applicationContext.getSystemService(WifiManager::class.java)
            ?: return ActionResult.Failure("wifi.scan could not reach the Wi-Fi service")
        if (!wifi.isWifiEnabled) {
            return ActionResult.Failure("wifi.scan needs Wi-Fi to be on, even for a cached scan")
        }

        // Documented as deprecated and rate-limited; a false return means the platform refused, not
        // that the read below will fail.
        @Suppress("DEPRECATION")
        val scanRequested = runCatching { wifi.startScan() }.getOrDefault(false)

        // SecurityException is caught by name rather than through runCatching: the permission check
        // above is not something lint can follow, and a revoked grant must fail closed here too.
        val results = try {
            wifi.scanResults
        } catch (error: SecurityException) {
            return ActionResult.Failure("wifi.scan could not read results: ${error.message ?: "permission denied"}")
        } catch (error: Exception) {
            return ActionResult.Failure("wifi.scan could not read results: ${error.message ?: "unknown error"}")
        }.orEmpty()

        val visible = results
            .sortedByDescending { it.level }
            .take(limit)

        val ssids = visible.map { result -> readSsid(result).take(MAX_FIELD_CHARS) }
        val bssids = visible.map { result -> result.BSSID.orEmpty().take(MAX_FIELD_CHARS) }
        val levels = visible.map { result -> result.level.toString() }

        ctx.variables.setArray("${varName}_ssid", ssids)
        ctx.variables.setArray("${varName}_bssid", bssids, sensitive = true)
        ctx.variables.setArray("${varName}_level", levels)
        ctx.variables.set("${varName}_count", visible.size.toString())
        ctx.variables.set("${varName}_scan_requested", scanRequested.toString())
        ctx.variables.set("${varName}_age_ms", newestAgeMillis(visible)?.toString().orEmpty())

        // Count only: the SSIDs a device can see are a location fingerprint and do not belong in a
        // run log.
        ctx.logger("wifi.scan: ${visible.size} access point(s), new scan requested = $scanRequested")
        return ActionResult.Success
    }

    private fun missingPermissions(context: Context): List<String> = buildList {
        if (!granted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            add("location access")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        ) {
            add("nearby Wi-Fi devices")
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun readSsid(result: android.net.wifi.ScanResult): String =
        if (Build.VERSION.SDK_INT >= 33) {
            result.wifiSsid?.toString()?.trim('"').orEmpty()
        } else {
            result.SSID.orEmpty()
        }

    /** Milliseconds since the newest cached result was observed, or null when nothing is cached. */
    private fun newestAgeMillis(results: List<android.net.wifi.ScanResult>): Long? {
        val newestMicros = results.maxOfOrNull { it.timestamp } ?: return null
        val elapsedMicros = android.os.SystemClock.elapsedRealtime() * 1_000L - newestMicros
        return (elapsedMicros / 1_000L).coerceAtLeast(0L)
    }

    companion object {
        internal const val DEFAULT_VARIABLE = "wifi"
        internal const val DEFAULT_RESULTS = 20
        internal const val MAX_RESULTS = 64
        internal const val MAX_FIELD_CHARS = 64
    }
}
