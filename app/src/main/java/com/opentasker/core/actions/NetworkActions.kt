package com.opentasker.core.actions

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

/**
 * Ping a host (check connectivity).
 *
 * Args:
 *   - "host": hostname or IP
 *   - "timeout_sec": optional timeout (default: 5)
 *   - "var": variable to store result (true/false)
 */
class PingAction : DeclaredAction(ActionCatalog.require("ping")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val host = args["host"] ?: return ActionResult.Failure("missing host")
        val varName = args["var"] ?: "result"
        if (!HOST_PATTERN.matches(host)) return ActionResult.Failure("invalid host")
        val timeoutMs = (args["timeout_sec"]?.toIntOrNull() ?: 5).coerceIn(1, 30) * 1000
        return try {
            val target = java.net.InetAddress.getByName(host)
            // Resolve first, then gate. Demanding ACCESS_LOCAL_NETWORK before knowing the target
            // made `ping 8.8.8.8` fail closed on Android 17 without a permission it never needed,
            // contradicting both the capability copy and how http.request gates the same thing.
            if (isPrivateOrLocalAddress(target)) {
                checkLocalNetworkPermission(ctx)?.let { return it }
            }
            val reachable = target.isReachable(timeoutMs)
            ctx.variables.set(varName, reachable.toString())
            ctx.logger("Ping $host → $reachable")
            ActionResult.Success
        } catch (e: Exception) {
            ctx.variables.set(varName, "false")
            ctx.logger("Ping $host failed: ${e.message}")
            ActionResult.Success
        }
    }
}

private val HOST_PATTERN = Regex("^[A-Za-z0-9.-]{1,253}$")

/**
 * Download file from URL.
 *
 * Args:
 *   - "url": download URL
 *   - "path": destination file path (relative to the OpenTasker user_files sandbox)
 *   - "timeout_sec": optional timeout (default: 30, max 120)
 *   - "max_bytes": optional size cap (default and maximum: 50 MB)
 *
 * Delegates to [HttpRequestAction] with `output_file` so the cleartext private-LAN DNS policy, the
 * API 37 LAN-permission gate, same-origin redirect handling, the 50 MB cap, and atomic fsync'd
 * writes are all enforced by the single shared transport instead of a parallel implementation.
 * Downloads land in the shared `user_files` sandbox, so the `file.*` actions can read them back.
 */
class DownloadAction(
    private val delegate: HttpRequestAction = HttpRequestAction(),
) : DeclaredAction(ActionCatalog.require("download")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (args["url"].isNullOrBlank()) return ActionResult.Failure("missing url")
        val path = args["path"]?.takeIf(String::isNotBlank) ?: return ActionResult.Failure("missing path")

        val delegated = args.toMutableMap()
        delegated.remove("path")
        delegated.remove("max_bytes")
        delegated["output_file"] = path
        // Downloads follow same-origin redirects by default (CDNs commonly 3xx); callers can still
        // pass redirects=none explicitly.
        delegated.putIfAbsent("redirects", "same_origin")
        args["max_bytes"]?.let { raw ->
            val parsed = raw.toLongOrNull()?.takeIf { it > 0 }
                ?: return ActionResult.Failure("max_bytes must be a positive integer")
            delegated["max_response_bytes"] = parsed.coerceAtMost(MAX_DOWNLOAD_BYTES).toString()
        }
        args["timeout_sec"]?.toLongOrNull()?.let { seconds ->
            delegated["timeout_sec"] = seconds.coerceIn(1, MAX_DOWNLOAD_TIMEOUT_SEC).toString()
        }
        return delegate.run(ctx, delegated)
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 52_428_800L // 50 MB, matching HttpRequestAction's file cap
        const val MAX_DOWNLOAD_TIMEOUT_SEC = 120L // HttpRequestAction's max per-timeout budget
    }
}

/**
 * Wake-on-LAN magic packet.
 *
 * Args:
 *   - "mac": target MAC address (e.g. "AA:BB:CC:DD:EE:FF")
 *   - "broadcast": broadcast IP (default: "255.255.255.255")
 *   - "port": UDP port (default: 9)
 */
class WakeOnLanAction : DeclaredAction(ActionCatalog.require("wol")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val macStr = args["mac"] ?: return ActionResult.Failure("missing mac")
        val broadcast = args["broadcast"] ?: "255.255.255.255"
        val port = (args["port"]?.toIntOrNull() ?: 9).coerceIn(1, 65535)
        checkLocalNetworkPermission(ctx)?.let { return it }

        val macBytes = parseMac(macStr)
            ?: return ActionResult.Failure("invalid MAC address: $macStr")

        val packet = buildMagicPacket(macBytes)

        return try {
            val addr = InetAddress.getByName(broadcast)
            // The capability gates this action permanently on the premise that it "only ever
            // targets a private address"; nothing enforced that, so a task could aim an
            // unsolicited packet at any public host and port.
            if (!addr.isBroadcastLikeOrPrivate()) {
                return ActionResult.Failure("wol only targets a local network address: $broadcast")
            }
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(packet, packet.size, addr, port))
            }
            ctx.logger("WoL sent to $macStr via $broadcast:$port")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("WoL failed: ${e.message}")
        }
    }

    companion object {
        // Backreference \2 forces one consistent separator, so mixed forms like AA:BB-CC... are rejected.
        private val MAC_PATTERN = Regex("^([0-9A-Fa-f]{2})([:-])([0-9A-Fa-f]{2}\\2){4}[0-9A-Fa-f]{2}$")

        internal fun parseMac(mac: String): ByteArray? {
            if (!MAC_PATTERN.matches(mac)) return null
            return mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        }

        internal fun buildMagicPacket(mac: ByteArray): ByteArray {
            val packet = ByteArray(6 + 16 * 6)
            for (i in 0..5) packet[i] = 0xFF.toByte()
            for (i in 0..15) {
                System.arraycopy(mac, 0, packet, 6 + i * 6, 6)
            }
            return packet
        }
    }
}

private const val ANDROID_17_API = 37

internal fun enforceHttpPolicy(url: URL, args: Map<String, String>): ActionResult? {
    if (url.protocol == "https") return null
    if (url.protocol != "http") return ActionResult.Failure("unsupported protocol: ${url.protocol}")
    val allowHttp = args["allow_http"]?.lowercase() == "true"
    if (!allowHttp) {
        return ActionResult.Failure(
            "only https URLs are allowed; set allow_http=true for LAN/private-network hosts"
        )
    }
    val addresses = runCatching { InetAddress.getAllByName(url.host).toList() }.getOrNull()
        ?: return ActionResult.Failure("cannot resolve host: ${url.host}")
    if (addresses.isEmpty() || addresses.any { !isPrivateOrLocalAddress(it) }) {
        return ActionResult.Failure(
            "HTTP is only allowed when every resolved address for ${url.host} is private/LAN"
        )
    }
    return null
}

/**
 * True when [addr] is a loopback, link-local, IPv4 site-local (10/8, 172.16/12, 192.168/16), or
 * IPv6 Unique Local (fc00::/7) address. `InetAddress.isSiteLocalAddress` does NOT cover IPv6 ULA,
 * so it is detected explicitly; without this a `fd00::` LAN host would be treated as public.
 */
/** A LAN broadcast, multicast, or private address - the only destinations a magic packet has. */
private fun InetAddress.isBroadcastLikeOrPrivate(): Boolean =
    isAnyLocalAddress ||
        isMulticastAddress ||
        address.all { it.toInt() and 0xff == 0xff } ||
        isPrivateOrLocalAddress(this)

internal fun isPrivateOrLocalAddress(addr: InetAddress): Boolean {
    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
    if (addr is java.net.Inet6Address) {
        val firstByte = addr.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
        // fc00::/7 -> high 7 bits equal 1111110, i.e. first byte is 0xfc or 0xfd.
        if (firstByte and 0xfe == 0xfc) return true
    }
    return false
}

internal fun urlTargetsLocalNetwork(url: URL): Boolean {
    if (url.protocol != "http" && url.protocol != "https") return false
    val addresses = runCatching { InetAddress.getAllByName(url.host).toList() }.getOrNull() ?: return false
    return addresses.any(::isPrivateOrLocalAddress)
}

internal fun checkLocalNetworkPermission(ctx: ActionContext): ActionResult? {
    // Short-circuit before touching the permission API below Android 17 so the permission check is
    // never evaluated where it does not apply (and cannot NPE against a bare test context).
    if (Build.VERSION.SDK_INT < ANDROID_17_API) return null
    val granted = ContextCompat.checkSelfPermission(ctx.app, "android.permission.ACCESS_LOCAL_NETWORK") ==
        PackageManager.PERMISSION_GRANTED
    return localNetworkPermissionDenial(Build.VERSION.SDK_INT, granted)
}

/**
 * Pure ACCESS_LOCAL_NETWORK policy. Below Android 17 (API 37) the permission is not enforced, so LAN
 * actions proceed regardless. On API 37+ a missing grant (never granted or revoked) fails closed with
 * a clear message; a granted permission proceeds. Kept pure so granted/denied/revoked mapping is
 * unit-testable without a Context or a specific SDK level.
 */
internal fun localNetworkPermissionDenial(sdkInt: Int, granted: Boolean): ActionResult? {
    if (sdkInt < ANDROID_17_API) return null
    if (granted) return null
    return ActionResult.Failure(
        "Android 17+ requires ACCESS_LOCAL_NETWORK permission for LAN communication; grant it in Setup",
    )
}


/**
 * Which kind of connection an HTTP Request is willing to use.
 *
 * Metered-aware requests are a common ask, and without this a user has to wrap the action in a
 * `flow.if` on a connectivity state variable, which races the request it is guarding.
 */
enum class HttpNetworkConstraint(val wireValue: String) {
    ANY("any"),
    WIFI("wifi"),
    CELLULAR("cellular"),
    UNMETERED("unmetered"),
    ;

    companion object {
        /** Anything blank, absent or unrecognised means no constraint, so an old bundle still runs. */
        fun parse(raw: String?): HttpNetworkConstraint {
            val value = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == value } ?: ANY
        }
    }
}

/**
 * What the device is connected through right now, as far as [HttpNetworkConstraint] cares.
 *
 * [connected] false means there is no active network at all. A null [ActiveTransport] elsewhere
 * means the answer could not be read, which is treated differently: see [httpNetworkDenial].
 *
 * [internet] is deliberately separate from [connected]. A Wi-Fi network with no route to the
 * internet is still a Wi-Fi network, and reaching a device on it is the whole point of the
 * action's `allow_http` private-LAN rule, so it must satisfy a `wifi` constraint. Internet
 * reachability only colours the message when some other constraint is unsatisfied.
 */
data class ActiveTransport(
    val connected: Boolean,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val unmetered: Boolean = false,
    val internet: Boolean = false,
) {
    companion object {
        val NONE = ActiveTransport(connected = false)
    }
}

/**
 * Pure constraint policy, so the wifi/cellular/metered decision is unit-testable without a device.
 *
 * Fails closed on an unreadable transport: sending a request that was explicitly restricted, over
 * a connection nobody could identify, is the one outcome the setting exists to prevent.
 */
internal fun httpNetworkDenial(
    constraint: HttpNetworkConstraint,
    transport: ActiveTransport?,
): ActionResult? {
    if (constraint == HttpNetworkConstraint.ANY) return null
    if (transport == null) {
        return ActionResult.Failure(
            "this request is limited to ${constraint.wireValue}, and the current connection could not be identified",
        )
    }
    if (!transport.connected) {
        return ActionResult.Failure("this request is limited to ${constraint.wireValue}, and the device is offline")
    }
    val satisfied = when (constraint) {
        HttpNetworkConstraint.ANY -> true
        HttpNetworkConstraint.WIFI -> transport.wifi
        HttpNetworkConstraint.CELLULAR -> transport.cellular
        HttpNetworkConstraint.UNMETERED -> transport.unmetered
    }
    if (satisfied) return null
    return ActionResult.Failure(
        "this request is limited to ${constraint.wireValue}, and the current connection is ${transport.describe()}",
    )
}

private fun ActiveTransport.describe(): String {
    val kind = when {
        !connected -> return "offline"
        wifi && unmetered -> "unmetered Wi-Fi"
        wifi -> "metered Wi-Fi"
        cellular -> "cellular"
        unmetered -> "another unmetered connection"
        else -> "another metered connection"
    }
    // Worth saying, because a local-only network is the case people are most likely to be
    // surprised by, and it explains why a constraint they expected to match did not.
    return if (internet) kind else "$kind with no internet access"
}

/** Reads the live transport. Returns null when the answer cannot be read at all. */
internal fun activeTransport(ctx: ActionContext): ActiveTransport? = readActiveTransport(ctx.app)

/**
 * The live transport, read from a plain [android.content.Context].
 *
 * [activeTransport] takes an [ActionContext] because that is what an action has; the preflight
 * preview has only the application context, and both need the same answer.
 */
fun readActiveTransport(context: android.content.Context): ActiveTransport? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = manager.activeNetwork ?: return ActiveTransport.NONE
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    return ActiveTransport(
        // There is an active network with readable capabilities, so we are connected to something.
        // Deriving this from NET_CAPABILITY_INTERNET instead would report a LAN with no WAN, a
        // captive portal, or a router that is down as "offline", and refuse a wifi-restricted
        // request aimed at a device on that very LAN.
        connected = true,
        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    )
}
