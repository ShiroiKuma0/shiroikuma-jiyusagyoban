package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.engine.ActionResult
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class HomeAssistantWebhookConfig(
    val url: String,
    val payload: String,
    val timeoutSeconds: Int,
    val maxRetries: Int,
    val backoffMilliseconds: Long,
    val allowHttp: Boolean,
)

object HomeAssistantWebhookProtocol {
    const val MAX_PAYLOAD_BYTES = 16 * 1024
    const val MAX_RETRIES = 3
    const val MAX_BACKOFF_MILLISECONDS = 5_000L

    fun parse(args: Map<String, String>): Result<HomeAssistantWebhookConfig> = runCatching {
        val url = args["url"]?.trim().orEmpty()
        require(url.isNotBlank()) { "Home Assistant webhook URL is required" }
        val parsedUrl = URI(url)
        require(parsedUrl.scheme.equals("https", ignoreCase = true) || parsedUrl.scheme.equals("http", ignoreCase = true)) {
            "Home Assistant webhook URL must use https or http"
        }
        require(!parsedUrl.host.isNullOrBlank()) { "Home Assistant webhook URL must include a host" }
        val allowHttp = args["allow_http"]?.equals("true", ignoreCase = true) == true
        require(!parsedUrl.scheme.equals("http", ignoreCase = true) || allowHttp) {
            "Home Assistant webhooks require https; set allow_http=true only for a private-LAN endpoint"
        }

        val payload = args["payload"].orEmpty().ifBlank { "{}" }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Home Assistant webhook payload exceeds ${MAX_PAYLOAD_BYTES / 1024} KB"
        }
        require(Json.parseToJsonElement(payload) is JsonObject) {
            "Home Assistant webhook payload must be a JSON object"
        }

        HomeAssistantWebhookConfig(
            url = url,
            payload = payload,
            timeoutSeconds = args["timeout_sec"]?.let { raw ->
                raw.toIntOrNull()?.also { require(it in 1..30) {
                    "Home Assistant webhook timeout must be between 1 and 30 seconds"
                } } ?: error("Home Assistant webhook timeout must be an integer")
            } ?: 15,
            maxRetries = args["retries"]?.let { raw ->
                raw.toIntOrNull()?.also { require(it in 0..MAX_RETRIES) {
                    "Home Assistant webhook retries must be between 0 and $MAX_RETRIES"
                } } ?: error("Home Assistant webhook retries must be an integer")
            } ?: 2,
            backoffMilliseconds = args["backoff_ms"]?.let { raw ->
                raw.toLongOrNull()?.also { require(it in 100..MAX_BACKOFF_MILLISECONDS) {
                    "Home Assistant webhook backoff must be between 100 and $MAX_BACKOFF_MILLISECONDS ms"
                } } ?: error("Home Assistant webhook backoff must be an integer")
            } ?: 500L,
            allowHttp = allowHttp,
        )
    }

    fun isTransientFailure(result: ActionResult): Boolean {
        if (result !is ActionResult.Failure) return false
        val status = Regex("^HTTP (\\d{3})$").matchEntire(result.message)?.groupValues?.get(1)?.toIntOrNull()
        return status == null || status in setOf(408, 425, 429) || status in 500..599
    }

    fun retryDelayMilliseconds(config: HomeAssistantWebhookConfig, retryIndex: Int): Long =
        (config.backoffMilliseconds * (1L shl retryIndex.coerceIn(0, 4))).coerceAtMost(10_000L)

    fun redactedUrl(url: String): String = runCatching {
        val parsed = URI(url)
        "${parsed.scheme}://${parsed.host}/${ActionArgumentSensitivity.REDACTED}"
    }.getOrDefault(ActionArgumentSensitivity.REDACTED)
}

class HomeAssistantWebhookAction(
    private val httpRequest: HttpRequestAction = HttpRequestAction(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : Action {
    override val id = ID
    override val category = ActionCategory.NET
    override val retrySafety = ActionRetrySafety.NEVER

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val config = HomeAssistantWebhookProtocol.parse(args).getOrElse { error ->
            return ActionResult.Failure(error.message ?: "Invalid Home Assistant webhook")
        }
        val requestArgs = mapOf(
            "method" to "POST",
            "url" to config.url,
            "body" to config.payload,
            "content_type" to "application/json",
            "allow_http" to config.allowHttp.toString(),
            "redirects" to "none",
            "timeout_sec" to config.timeoutSeconds.toString(),
            "connect_timeout_sec" to config.timeoutSeconds.toString(),
            "read_timeout_sec" to config.timeoutSeconds.toString(),
            "write_timeout_sec" to config.timeoutSeconds.toString(),
            "call_timeout_sec" to config.timeoutSeconds.toString(),
        )

        var result: ActionResult = ActionResult.Failure("Home Assistant webhook did not run")
        for (attempt in 0..config.maxRetries) {
            result = httpRequest.run(ctx, requestArgs)
            if (result == ActionResult.Success || attempt >= config.maxRetries || !HomeAssistantWebhookProtocol.isTransientFailure(result)) {
                break
            }
            val retryNumber = attempt + 1
            ctx.logger("Home Assistant webhook transient failure; retry $retryNumber/${config.maxRetries}")
            sleeper(HomeAssistantWebhookProtocol.retryDelayMilliseconds(config, attempt))
        }
        return result
    }

    companion object {
        const val ID = "integration.home_assistant.webhook"
    }
}
