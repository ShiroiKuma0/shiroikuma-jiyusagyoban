package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.io.File
import java.net.HttpURLConnection
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
            if (parsedUrl.protocol != "https") return ActionResult.Failure("only https URLs are allowed")
            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                val response = connection.apply {
                    connectTimeout = timeout
                    readTimeout = timeout
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }.getInputStream().bufferedReader().use { it.readText() }
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
            if (parsedUrl.protocol != "https") return ActionResult.Failure("only https URLs are allowed")
            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                val response = connection.apply {
                    connectTimeout = timeout
                    readTimeout = timeout
                    requestMethod = "POST"
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Content-Type", args["content_type"] ?: "text/plain; charset=utf-8")
                }.run {
                    outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                    val stream = if (responseCode in 200..299) inputStream else errorStream
                    stream?.bufferedReader()?.use { it.readText() }.orEmpty()
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
            if (parsedUrl.protocol != "https") return ActionResult.Failure("only https URLs are allowed")
            val destination = safeDownloadFile(ctx, path)
                ?: return ActionResult.Failure("path is outside OpenTasker downloads")
            destination.parentFile?.mkdirs()

            val connection = parsedUrl.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeout
                connection.readTimeout = timeout
                connection.instanceFollowRedirects = false
                connection.inputStream.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
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

private fun safeDownloadFile(ctx: ActionContext, path: String): File? {
    if (path.isBlank() || path.contains('\u0000')) return null
    val baseDir = File(ctx.app.filesDir, "downloads").canonicalFile
    val requested = File(baseDir, path.trimStart('/', '\\')).canonicalFile
    if (!requested.path.startsWith(baseDir.path + File.separator) && requested != baseDir) return null
    return requested
}
