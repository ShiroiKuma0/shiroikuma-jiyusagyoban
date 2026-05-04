package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
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
        val timeout = (args["timeout_sec"]?.toIntOrNull() ?: 10) * 1000
        return try {
            val response = URL(url).openConnection().apply {
                connectTimeout = timeout
                readTimeout = timeout
            }.getInputStream().bufferedReader().use { it.readText() }
            ctx.variables.set(varName, response)
            ctx.logger("HTTP GET $url → \$$varName")
            ActionResult.Success
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
 *   - "timeout_sec": optional timeout
 *   - "var": variable to store result (true/false)
 */
class PingAction : Action {
    override val id = "net.ping"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val host = args["host"] ?: return ActionResult.Failure("missing host")
        val varName = args["var"] ?: "result"
        return try {
            val reachable = java.net.InetAddress.getByName(host).isReachable(5000)
            ctx.variables.set(varName, reachable.toString())
            ctx.logger("Ping $host → $reachable")
            ActionResult.Success
        } catch (e: Exception) {
            ctx.variables.set(varName, "false")
            ActionResult.Success
        }
    }
}

/**
 * Download file from URL.
 *
 * Args:
 *   - "url": download URL
 *   - "path": destination file path
 */
class DownloadAction : Action {
    override val id = "net.download"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        ctx.logger("Download $url → $path")
        // TODO: Implement with URLConnection and progress callback
        return ActionResult.Success
    }
}
