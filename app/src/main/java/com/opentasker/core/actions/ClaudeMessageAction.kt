package com.opentasker.core.actions

import com.opentasker.core.claude.ClaudeClient
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Ask Claude one question and keep the answer in a variable — the general form of what 言語島's
 * translator does, reachable from any task.
 *
 * Args: `api_key` (required), `prompt` or `prompt_file` (one of them required), `system`, `model`
 * (default claude-opus-5-5), `effort` (default high), `max_tokens` (default 16000), `schema` (a JSON
 * Schema; the answer is then JSON matching it), `fallbacks` (default on — a request declined by a
 * safety classifier is re-run on Anthropic's recommended fallback model), `response_var` (default
 * `claude`).
 *
 * Writes `<var>` (the answer), `<var>_stop` (end_turn / max_tokens / refusal), `<var>_model` (who
 * actually answered — differs after a fallback), `<var>_tokens` (`<in>/<out>`) and `<var>_error`.
 * The action fails unless the answer is complete: a truncated or refused answer is not an answer.
 */
class ClaudeMessageAction : Action {
    override val id = "claude.message"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        fun arg(name: String) = ctx.variables.expand(args[name].orEmpty()).trim()
        val store = arg("response_var").removePrefix("%").ifEmpty { "claude" }
        val key = arg("api_key")
        if (key.isEmpty()) return ActionResult.Failure("api_key is required")
        val prompt = ctx.variables.expand(args["prompt"].orEmpty()).ifBlank {
            arg("prompt_file").takeIf { it.isNotEmpty() }?.let { path ->
                runCatching { File(path).readText() }.getOrElse {
                    return ActionResult.Failure("cannot read prompt_file $path: ${it.message}")
                }
            }.orEmpty()
        }
        if (prompt.isBlank()) return ActionResult.Failure("prompt or prompt_file is required")
        val schema = arg("schema").takeIf { it.isNotEmpty() }?.let { text ->
            runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse {
                return ActionResult.Failure("schema is not a JSON object: ${it.message}")
            }
        }

        ctx.variables.set("${store}_error", "")
        val reply = runCatching {
            ClaudeClient.send(
                ClaudeClient.Request(
                    apiKey = key,
                    prompt = prompt,
                    system = ctx.variables.expand(args["system"].orEmpty()).ifBlank { null },
                    model = arg("model").ifEmpty { ClaudeClient.DEFAULT_MODEL },
                    maxTokens = arg("max_tokens").toIntOrNull()?.coerceIn(1, 64_000) ?: 16_000,
                    effort = arg("effort").ifEmpty { "high" },
                    schema = schema,
                    fallbacks = arg("fallbacks").lowercase() !in setOf("0", "false", "no", "off"),
                ),
            )
        }.getOrElse {
            ctx.variables.set("${store}_error", it.message.orEmpty())
            return ActionResult.Failure("Claude: ${it.message}")
        }
        ctx.variables.set(store, reply.text)
        ctx.variables.set("${store}_stop", reply.stopReason)
        ctx.variables.set("${store}_model", reply.model)
        ctx.variables.set("${store}_tokens", "${reply.inputTokens}/${reply.outputTokens}")
        ctx.logger("Claude (${reply.model}): ${reply.stopReason}, ${reply.inputTokens} in / ${reply.outputTokens} out")
        if (!reply.complete) {
            val why = when (reply.stopReason) {
                "refusal" -> "declined (${reply.refusal.orEmpty().ifEmpty { "no reason given" }})"
                "max_tokens" -> "cut off at max_tokens — raise it"
                else -> "stopped: ${reply.stopReason}"
            }
            ctx.variables.set("${store}_error", why)
            return ActionResult.Failure("Claude $why")
        }
        return ActionResult.Success
    }
}
