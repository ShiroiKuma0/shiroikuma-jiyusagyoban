package com.opentasker.core.actions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ping a host (check connectivity).
 *
 * Args:
 *   - "host": hostname or IP
 *   - "timeout_sec": optional timeout (default: 5)
 *   - "var": variable to store result (true/false)
 */
class PingAction : Action {
    override val id = "ping"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val host = args["host"] ?: return ActionResult.Failure("missing host")
        val varName = args["var"] ?: "result"
        if (!HOST_PATTERN.matches(host)) return ActionResult.Failure("invalid host")
        checkLocalNetworkPermission(ctx)?.let { return it }
        val timeoutMs = (args["timeout_sec"]?.toIntOrNull() ?: 5).coerceIn(1, 30) * 1000
        return try {
            val reachable = java.net.InetAddress.getByName(host).isReachable(timeoutMs)
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
 *   - "path": destination file path
 *   - "timeout_sec": optional timeout (default: 30)
 */
class DownloadAction : Action {
    override val id = "download"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val timeout = (args["timeout_sec"]?.toIntOrNull() ?: 30).coerceIn(1, 300) * 1000
        return try {
            val parsedUrl = URL(url)
            enforceHttpPolicy(parsedUrl, args)?.let { return it }
            if (urlTargetsLocalNetwork(parsedUrl)) checkLocalNetworkPermission(ctx)?.let { return it }
            val destination = safeDownloadFile(ctx, path)
                ?: return ActionResult.Failure("path is outside OpenTasker downloads")
            destination.parentFile?.mkdirs()

            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.instanceFollowRedirects = false
                val maxDownload = parseMaxDownloadBytes(args)
                    ?: return ActionResult.Failure("max_bytes must be a positive integer")
                val effectiveLimit = maxDownload.coerceAtMost(MAX_DOWNLOAD_BYTES)
                val tempFile = File.createTempFile(tempFilePrefix(destination), ".part", destination.parentFile)
                try {
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            val copied = input.copyBounded(output, effectiveLimit)
                            if (copied < 0) {
                                return ActionResult.Failure("download exceeds $effectiveLimit byte limit")
                            }
                        }
                    }
                    replaceFile(tempFile, destination)
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
                ctx.logger("Downloaded ${parsedUrl.host} to ${destination.name}")
                ActionResult.Success
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            ActionResult.Failure("download failed: ${e.message}")
        }
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
class WakeOnLanAction : Action {
    override val id = "wol"
    override val category = ActionCategory.NET

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

private const val MAX_RESPONSE_BYTES = 1_048_576L // 1 MB for in-memory response variables
private const val MAX_DOWNLOAD_BYTES = 52_428_800L // 50 MB for file downloads

private fun InputStream.readBounded(maxBytes: Long): String {
    val buffer = ByteArray(8192)
    val result = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw IllegalStateException("response exceeds ${maxBytes / 1024 / 1024} MB limit")
        result.write(buffer, 0, n)
    }
    return result.toByteArray().toString(Charsets.UTF_8)
}

private fun InputStream.copyBounded(out: java.io.OutputStream, maxBytes: Long): Long {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) return -1
        out.write(buffer, 0, n)
    }
    return total
}

private const val ANDROID_17_API = 37

private fun parseMaxDownloadBytes(args: Map<String, String>): Long? {
    val raw = args["max_bytes"] ?: return MAX_DOWNLOAD_BYTES
    return raw.toLongOrNull()?.takeIf { it > 0 }
}

private fun tempFilePrefix(destination: File): String =
    destination.name.ifBlank { "download" }.padEnd(3, '_')

private fun replaceFile(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

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
    if (Build.VERSION.SDK_INT < ANDROID_17_API) return null
    if (ContextCompat.checkSelfPermission(ctx.app, "android.permission.ACCESS_LOCAL_NETWORK")
        != PackageManager.PERMISSION_GRANTED
    ) {
        return ActionResult.Failure(
            "Android 17+ requires ACCESS_LOCAL_NETWORK permission for LAN communication; grant it in Setup"
        )
    }
    return null
}

private fun safeDownloadFile(ctx: ActionContext, path: String): File? {
    if (path.isBlank() || path.contains('\u0000')) return null
    val baseDir = File(ctx.app.filesDir, "downloads").canonicalFile
    val requested = File(baseDir, path.trimStart('/', '\\')).canonicalFile
    if (!requested.path.startsWith(baseDir.path + File.separator) && requested != baseDir) return null
    return requested
}
