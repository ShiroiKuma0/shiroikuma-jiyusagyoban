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

/**
 * HTTP GET request.
 *
 * Args:
 *   - "url": request URL
 *   - "var": variable to store response
 *   - "timeout_sec": optional request timeout
 */
class HttpGetAction : Action {
    override val id = "http.get"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val varName = args["var"] ?: "result"
        val timeout = (args["timeout_sec"]?.toIntOrNull() ?: 10).coerceIn(1, 60) * 1000
        return try {
            val parsedUrl = URL(url)
            enforceHttpPolicy(parsedUrl, args)?.let { return it }
            if (parsedUrl.protocol == "http") checkLocalNetworkPermission(ctx)?.let { return it }
            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                val response = connection.apply {
                    connectTimeout = timeout
                    readTimeout = timeout
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }.getInputStream().readBounded(MAX_RESPONSE_BYTES)
                ctx.variables.set(varName, response)
                ctx.logger("HTTP GET ${parsedUrl.host} to \$$varName")
                ActionResult.Success
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            ActionResult.Failure("request failed: ${e.message}")
        }
    }
}

/**
 * HTTP POST request.
 *
 * Args:
 *   - "url": request URL
 *   - "data": POST body
 *   - "var": variable to store response
 */
class HttpPostAction : Action {
    override val id = "http.post"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val data = args["data"] ?: ""
        val varName = args["var"] ?: "result"
        val timeout = (args["timeout_sec"]?.toIntOrNull() ?: 10).coerceIn(1, 60) * 1000
        return try {
            val parsedUrl = URL(url)
            enforceHttpPolicy(parsedUrl, args)?.let { return it }
            if (parsedUrl.protocol == "http") checkLocalNetworkPermission(ctx)?.let { return it }
            val payload = data.toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_REQUEST_BODY_BYTES) {
                return ActionResult.Failure(
                    "POST body exceeds ${MAX_REQUEST_BODY_BYTES / 1024} KB limit"
                )
            }
            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                val response = connection.apply {
                    connectTimeout = timeout
                    readTimeout = timeout
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size.toLong())
                    setRequestProperty("Content-Type", args["content_type"] ?: "text/plain; charset=utf-8")
                }.run {
                    outputStream.use { it.write(payload) }
                    val stream = if (responseCode in 200..299) inputStream else errorStream
                    stream?.readBounded(MAX_RESPONSE_BYTES).orEmpty()
                }
                ctx.variables.set(varName, response)
                ctx.logger("HTTP POST ${parsedUrl.host} to \$$varName")
                ActionResult.Success
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            ActionResult.Failure("request failed: ${e.message}")
        }
    }
}

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
            if (parsedUrl.protocol == "http") checkLocalNetworkPermission(ctx)?.let { return it }
            val destination = safeDownloadFile(ctx, path)
                ?: return ActionResult.Failure("path is outside OpenTasker downloads")
            destination.parentFile?.mkdirs()

            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.instanceFollowRedirects = false
                val maxDownload = args["max_bytes"]?.toLongOrNull() ?: MAX_DOWNLOAD_BYTES
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val copied = input.copyBounded(output, maxDownload.coerceAtMost(MAX_DOWNLOAD_BYTES))
                        if (copied < 0) {
                            destination.delete()
                            return ActionResult.Failure("download exceeds ${maxDownload / 1024 / 1024} MB limit")
                        }
                    }
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
        private val MAC_PATTERN = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

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

private const val MAX_REQUEST_BODY_BYTES = 1_048_576L // 1 MB for POST request bodies
private const val MAX_RESPONSE_BYTES = 1_048_576L // 1 MB for in-memory response variables
private const val MAX_DOWNLOAD_BYTES = 52_428_800L // 50 MB for file downloads

private fun InputStream.readBounded(maxBytes: Long): String {
    val buffer = ByteArray(8192)
    val result = StringBuilder()
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw IllegalStateException("response exceeds ${maxBytes / 1024 / 1024} MB limit")
        result.append(String(buffer, 0, n, Charsets.UTF_8))
    }
    return result.toString()
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

private fun enforceHttpPolicy(url: URL, args: Map<String, String>): ActionResult? {
    if (url.protocol == "https") return null
    if (url.protocol != "http") return ActionResult.Failure("unsupported protocol: ${url.protocol}")
    val allowHttp = args["allow_http"]?.lowercase() == "true"
    if (!allowHttp) {
        return ActionResult.Failure(
            "only https URLs are allowed; set allow_http=true for LAN/private-network hosts"
        )
    }
    val addr = runCatching { InetAddress.getByName(url.host) }.getOrNull()
        ?: return ActionResult.Failure("cannot resolve host: ${url.host}")
    if (!addr.isSiteLocalAddress && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
        return ActionResult.Failure(
            "HTTP is only allowed for private/LAN addresses (${url.host} resolved to a public address)"
        )
    }
    return null
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
