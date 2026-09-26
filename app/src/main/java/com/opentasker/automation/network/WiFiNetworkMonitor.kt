package com.opentasker.automation.network

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.opentasker.automation.MonitorLifecycle
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.platform.AppVisibilityTracker

class WiFiNetworkMonitor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val lifecycle = MonitorLifecycle()

    // Callbacks arrive on the connectivity thread and the start-up seed on the caller's, so both
    // the tracker and the publish that follows it run under one lock: a seed computed first can
    // then never be published after the callback that superseded it.
    private val stateLock = Any()
    private val tracker = WifiConnectionTracker<Network>()
    private var lastState: WiFiState? = null

    // Android 12 strips the SSID from every WifiInfo a callback receives unless the callback asks
    // for location info, and the placeholder it leaves is not blank, so without the flag the first
    // capability update after connecting replaced the real name with Unknown for good (issue #17).
    private val callback = if (Build.VERSION.SDK_INT >= 31) {
        WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
    } else {
        WifiCallback()
    }

    private inner class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(31)
        constructor(flags: Int) : super(flags)

        // Android always follows onAvailable with onCapabilitiesChanged, which carries the SSID,
        // so publishing here would only announce a nameless connection a moment early.
        override fun onAvailable(network: Network) {
            synchronized(stateLock) { tracker.onAvailable(network) }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val ssid = readSsid(networkCapabilities)
            synchronized(stateLock) { publish(tracker.onCapabilities(network, ssid)) }
        }

        override fun onLost(network: Network) {
            synchronized(stateLock) { publish(tracker.onLost(network)) }
        }
    }

    private val request: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    // A withheld name only comes back with the next capability update, which a phone sitting on one
    // network may not get for hours, so the Inspector kept saying "turn Location on" after it was
    // on. Re-registering makes Android replay the current networks with location checked again;
    // do that when whatever withheld the name may have changed: Location switched on, or one of
    // the app's activities resuming. A resume follows both a return from the system settings and
    // Setup's own precise-location prompt, which never stops the activity, and a permission grant
    // sends no broadcast of its own.
    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshIfNameWithheld()
    }
    private val resumeListener: () -> Unit = { refreshIfNameWithheld() }

    // Set while a refresh has unregistered the callback and not yet registered it again. If the
    // registration throws, nothing is left listening while the lifecycle still says active, so
    // start() would return early for good; the next refresh trigger retries it instead.
    @Volatile
    private var callbackLost = false

    fun start(): Boolean {
        return lifecycle.start {
            val cm = connectivityManager
            if (cm == null) {
                AppLogger.warn(TAG, "ConnectivityManager unavailable; WiFi monitoring disabled")
                synchronized(stateLock) { publish(WifiSnapshot(connected = false, ssid = null)) }
                return@start false
            }

            try {
                registerAndSeed(cm)
                AppLogger.debug(TAG, "WiFi NetworkCallback registered")
            } catch (ex: RuntimeException) {
                AppLogger.error(TAG, "Failed to register WiFi NetworkCallback", ex)
                synchronized(stateLock) { publish(WifiSnapshot(connected = false, ssid = null)) }
                return@start false
            }
            // Refresh triggers only; the monitor works without them, so a failure here must not
            // report the start as failed while the network callback stays registered.
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    locationModeReceiver,
                    IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onFailure { AppLogger.warn(TAG, "Location change receiver unavailable", it) }
            AppVisibilityTracker.addResumeListener(resumeListener)
            true
        }
    }

    fun stop() {
        lifecycle.stop {
            AppVisibilityTracker.removeResumeListener(resumeListener)
            runCatching { appContext.unregisterReceiver(locationModeReceiver) }
            if (!callbackLost) connectivityManager?.unregisterNetworkCallback(callback)
            callbackLost = false
            AppLogger.debug(TAG, "WiFi NetworkCallback unregistered")
        }
    }

    private fun registerAndSeed(cm: ConnectivityManager) {
        synchronized(stateLock) { tracker.reset() }
        cm.registerNetworkCallback(request, callback)
        // Android replays onAvailable for every Wi-Fi network already up, so the one answer the
        // callback can never give is "nothing is connected". Seeding more than that here would race
        // the replay with a staler reading.
        if (!hasWifiNetwork(cm)) {
            synchronized(stateLock) { tracker.seedDisconnected()?.let(::publish) }
        }
    }

    private fun refreshIfNameWithheld() {
        val withheld = synchronized(stateLock) {
            lastState?.let { it.connected && it.ssidUnavailableReason.isNotEmpty() } == true
        }
        if (!withheld && !callbackLost) return
        lifecycle.whileActive {
            val cm = connectivityManager ?: return@whileActive
            if (!callbackLost) cm.unregisterNetworkCallback(callback)
            callbackLost = true
            try {
                registerAndSeed(cm)
                callbackLost = false
                AppLogger.debug(TAG, "WiFi NetworkCallback re-registered to re-read a withheld name")
            } catch (ex: RuntimeException) {
                AppLogger.error(TAG, "Re-registering the WiFi NetworkCallback failed; the next refresh retries it", ex)
            }
        }
    }

    /** Call with [stateLock] held. */
    private fun publish(snapshot: WifiSnapshot) {
        val ssid = snapshot.ssid
        val state = WiFiState(
            connected = snapshot.connected,
            ssid = ssid ?: UNKNOWN_SSID,
            ssidUnavailableReason = if (snapshot.connected && ssid == null) ssidUnavailableReason() else "",
        )
        if (state == lastState) return
        lastState = state
        AppLogger.debug(TAG, "WiFi event: connected=${state.connected}, ssid=${state.ssid}")
        DeviceStateEvents.publishWifi(state.ssid, state.connected, state.ssidUnavailableReason)
    }

    /**
     * Whether a network the callback's request would report is up. It has to be the request's own
     * test: a broader one (any Wi-Fi transport) counted networks the callback never reports, such
     * as another app's local-only link, and then the "disconnected" seed was never published at
     * all. An unknown answer counts as "Wi-Fi present", so the seed stays silent rather than
     * announcing a disconnect it cannot prove.
     */
    @Suppress("DEPRECATION")
    private fun hasWifiNetwork(cm: ConnectivityManager): Boolean = runCatching {
        cm.allNetworks.any { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@any false
            if (Build.VERSION.SDK_INT >= 30) {
                request.canBeSatisfiedBy(capabilities)
            } else {
                // The request's default capabilities, spelled out for API 26 to 29.
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            }
        }
    }.getOrDefault(true)

    private fun readSsid(capabilities: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= 31) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            // A redacted WifiInfo means Android withheld the name, and WifiManager applies the same
            // location rules, so asking it as well would only add a binder call to every update.
            if (wifiInfo != null) return readableSsid(wifiInfo.ssid)
        }
        return readableSsid(connectionInfoSsid())
    }

    @Suppress("DEPRECATION")
    private fun connectionInfoSsid(): String? = try {
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
    } catch (ex: SecurityException) {
        AppLogger.warn(TAG, "WiFi SSID unavailable because permission or location access is denied", ex)
        null
    }

    private fun ssidUnavailableReason(): String = when {
        !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ->
            "Android only shows the Wi-Fi name to apps with precise location access. Open Setup and allow precise location."
        !isLocationEnabled() ->
            "Android hides the Wi-Fi name while Location is turned off. Turn Location on to match a network by name."
        Build.VERSION.SDK_INT >= 29 && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
            "Android hides the Wi-Fi name from apps in the background. Open Setup and allow location all the time."
        else -> "Android did not report a name for this Wi-Fi network."
    }

    private fun isLocationEnabled(): Boolean =
        appContext.getSystemService(LocationManager::class.java)
            ?.let(LocationManagerCompat::isLocationEnabled) == true

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private data class WiFiState(
        val connected: Boolean,
        val ssid: String,
        val ssidUnavailableReason: String,
    )

    companion object {
        private const val TAG = "WiFiNetworkMonitor"
        const val UNKNOWN_SSID = "Unknown"

        /** What Android puts in place of an SSID it withholds. */
        private const val WITHHELD_SSID = "<unknown ssid>"

        /** The SSID without Android's quotes, or null when Android withheld or never had one. */
        internal fun readableSsid(rawSsid: String?): String? =
            rawSsid
                ?.trim()
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != WITHHELD_SSID }
    }
}

/**
 * Which Wi-Fi networks are up and what each is called, built only from what the network callback
 * said about those networks.
 *
 * `activeNetwork` answers neither question. At `onLost` it can still name the departing network,
 * which read as "still connected", matched the last published state and was dropped, and no later
 * callback arrives for a network that no longer exists, so the disconnect was lost until the app
 * restarted (issue #17). It is also the VPN or mobile network whenever Wi-Fi is up without being
 * the default.
 *
 * Names are kept per network because Android keeps sending capability updates (signal strength,
 * validation) and one that arrives with the name withheld says nothing new about it: a different
 * SSID always arrives as a different network.
 *
 * Not thread-safe; [WiFiNetworkMonitor] serialises every call.
 */
internal class WifiConnectionTracker<N : Any> {
    private val ssidByNetwork = LinkedHashMap<N, String?>()
    private var observed = false

    fun onAvailable(network: N) {
        observed = true
        if (network !in ssidByNetwork) ssidByNetwork[network] = null
    }

    fun onCapabilities(network: N, readableSsid: String?): WifiSnapshot {
        observed = true
        ssidByNetwork[network] = readableSsid ?: ssidByNetwork[network]
        return snapshot()
    }

    fun onLost(network: N): WifiSnapshot {
        observed = true
        ssidByNetwork.remove(network)
        return snapshot()
    }

    /** "Nothing connected", unless a callback has already reported otherwise. */
    fun seedDisconnected(): WifiSnapshot? = if (observed) null else WifiSnapshot(connected = false, ssid = null)

    fun reset() {
        ssidByNetwork.clear()
        observed = false
    }

    fun snapshot(): WifiSnapshot = WifiSnapshot(
        connected = ssidByNetwork.isNotEmpty(),
        // The most recently connected network whose name is known.
        ssid = ssidByNetwork.values.lastOrNull { it != null },
    )
}

internal data class WifiSnapshot(
    val connected: Boolean,
    val ssid: String?,
)
