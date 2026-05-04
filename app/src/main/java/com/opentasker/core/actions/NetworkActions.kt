package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
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
        ctx.logger("HTTP POST $url")
        // TODO: Implement with URLConnection or OkHttp
        return ActionResult.Success
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
    override val id = "net.ping"
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
    override val id = "net.download"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val timeout = (args["timeout_sec"]?.toIntOrNull() ?: 30) * 1000
        ctx.logger("Download $url → $path (timeout: ${timeout}ms)")
        // TODO: Implement with URLConnection, configurable timeout, and progress callback
        return ActionResult.Success
    }
}
