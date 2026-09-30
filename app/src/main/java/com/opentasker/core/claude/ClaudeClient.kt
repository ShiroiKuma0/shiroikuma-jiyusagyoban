package com.opentasker.core.claude

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as HttpRequest
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One call to Anthropic's Messages API (`POST /v1/messages`), over plain HTTP.
 *
 * Plain OkHttp rather than the Java SDK because this app already carries OkHttp and nothing else the
 * SDK would pull in, and one non-streaming request is all it needs (the 言語島 plan, agreed with
 * 白い熊, 2026-09-30).
 *
 * ## What every request carries
 *
 * * `x-api-key` and `anthropic-version: 2023-06-01`.
 * * **Effort, explicitly.** Claude Opus 5.5 thinks always (adaptive thinking cannot be switched off)
 *   and its default effort is `medium`; the caller passes the level it wants.
 * * **Refusal fallback** (`fallbacks: "default"` + `anthropic-beta: server-side-fallback-2026-07-01`)
 *   when [Request.fallbacks] is on: a request declined by a safety classifier is re-run server-side
 *   on the recommended fallback model instead of coming back as a refusal. A refusal can still come
 *   back — the fallback model can decline too — so [Reply.stopReason] is checked before the text is
 *   trusted.
 * * **Structured output** (`output_config.format` = `json_schema`) when [Request.schema] is given:
 *   the reply's text is then JSON matching it, unless the stop reason says otherwise.
 *
 * ## Errors
 *
 * 429, 5xx and 529 (overloaded) are retried with back-off, honouring `retry-after`; network failures
 * too. Everything else — a bad key (401), a bad request (400), no credit (402) — fails at once with
 * the API's own message, because retrying it cannot help.
 */
object ClaudeClient {

    const val DEFAULT_MODEL = "claude-opus-5-5"
    private const val URL = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private const val MAX_ATTEMPTS = 4

    /**
     * Adaptive thinking on a long island can take minutes. Non-streaming, so the whole reply arrives
     * at once; the read timeout has to outlast the model's thinking, not merely the network.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.MINUTES)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(
        val apiKey: String,
        val prompt: String,
        val system: String? = null,
        val model: String = DEFAULT_MODEL,
        val maxTokens: Int = 16_000,
        /** low / medium / high / xhigh / max; blank leaves the model's default. */
        val effort: String = "high",
        /** A JSON Schema object (every object needs `additionalProperties: false`). */
        val schema: JsonObject? = null,
        val fallbacks: Boolean = true,
        /**
         * Ask for a readable summary of the thinking (`display: "summarized"`). Only worth it when
         * streaming, where it is the one thing to show while the model is still deciding.
         */
        val thinkingSummary: Boolean = false,
    )

    /** What a streaming call has produced so far — for a progress display, not for parsing. */
    data class Live(
        /** thinking / writing */
        val phase: String,
        /** The thinking summary so far (only with [Request.thinkingSummary]). */
        val thinking: String,
        /** The answer's text so far. */
        val text: String,
        val outputTokens: Long,
    )

    data class Reply(
        /** All text blocks joined. With a schema, this is the JSON document. */
        val text: String,
        /** end_turn, max_tokens, refusal, … — the reply is only whole on `end_turn`. */
        val stopReason: String,
        /** The model that actually produced it — differs from the request's after a fallback. */
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long,
        /** The refusal's category and explanation, when [stopReason] is `refusal`. */
        val refusal: String? = null,
    ) {
        val complete: Boolean get() = stopReason == "end_turn"
    }

    class ApiException(val status: Int, message: String) : IOException(message)

    /** The request body, built as JSON so no sentence can break it. */
    internal fun body(request: Request, stream: Boolean = false): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            if (stream) put("stream", true)
            // Claude Opus 5.5 thinks always; this only chooses whether the summary is sent back.
            if (request.thinkingSummary) {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", "adaptive")
                        put("display", "summarized")
                    },
                )
            }
            request.system?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", request.prompt)
                        },
                    )
                },
            )
            val effort = request.effort.trim()
            if (effort.isNotEmpty() || request.schema != null) {
                put(
                    "output_config",
                    buildJsonObject {
                        if (effort.isNotEmpty()) put("effort", effort)
                        request.schema?.let { schema ->
                            put(
                                "format",
                                buildJsonObject {
                                    put("type", "json_schema")
                                    put("schema", schema)
                                },
                            )
                        }
                    },
                )
            }
            if (request.fallbacks) put("fallbacks", "default")
        },
    )

    /** Parse a 200 response. Public for the tests; [send] is the only caller in the app. */
    internal fun parse(raw: String): Reply {
        val root = json.parseToJsonElement(raw).jsonObject
        val content = root["content"]?.jsonArray ?: JsonArray(emptyList())
        val text = content
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val stop = root["stop_reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val usage = root["usage"] as? JsonObject
        fun num(o: JsonObject?, k: String) = o?.get(k)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val refusal = if (stop == "refusal") {
            (root["stop_details"] as? JsonObject)?.let { d ->
                listOfNotNull(
                    d["category"]?.jsonPrimitive?.contentOrNull,
                    d["explanation"]?.jsonPrimitive?.contentOrNull,
                ).joinToString(": ")
            }.orEmpty()
        } else {
            null
        }
        return Reply(
            text = text,
            stopReason = stop,
            model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            inputTokens = num(usage, "input_tokens"),
            outputTokens = num(usage, "output_tokens"),
            refusal = refusal,
        )
    }

    /** The API's own one-line reason from an error body, or the raw body when it is not JSON. */
    internal fun errorMessage(status: Int, raw: String): String {
        val detail = runCatching {
            val err = json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject
            listOfNotNull(
                err?.get("type")?.jsonPrimitive?.contentOrNull,
                err?.get("message")?.jsonPrimitive?.contentOrNull,
            ).joinToString(": ")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw.take(300)
        return "HTTP $status — $detail"
    }

    suspend fun send(request: Request): Reply = withContext(Dispatchers.IO) {
        require(request.apiKey.isNotBlank()) { "no API key — set %Gengoshima_ApiKey in 日本語の設定" }
        val body = body(request).toRequestBody("application/json".toMediaType())
        val call = HttpRequest.Builder()
            .url(URL)
            .header("x-api-key", request.apiKey)
            .header("anthropic-version", VERSION)
            .header("content-type", "application/json")
            .apply { if (request.fallbacks) header("anthropic-beta", FALLBACK_BETA) }
            .post(body)
            .build()
        var attempt = 0
        while (true) {
            attempt++
            var waitMs: Long? = null
            val failure: IOException = try {
                http.newCall(call).execute().use { resp ->
                    val raw = resp.body.string()
                    if (resp.isSuccessful) return@withContext parse(raw)
                    val error = ApiException(resp.code, errorMessage(resp.code, raw))
                    // 429 rate limit, 529 overloaded, 5xx server: worth waiting for. Anything else
                    // (a bad key, a bad request, no credit) cannot be fixed by asking again.
                    if (resp.code != 429 && resp.code < 500) throw error
                    waitMs = resp.header("retry-after")?.toLongOrNull()?.times(1000)
                    error
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                e
            }
            if (attempt >= MAX_ATTEMPTS) throw failure
            delay(waitMs ?: (2_000L shl (attempt - 1)).coerceAtMost(30_000L))
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * The same call, streamed (server-sent events), so a caller can show it happening: [onLive] is
     * called as the thinking summary and the answer arrive, a few times a second at most.
     *
     * Retries exactly as [send] does, but only before the first byte of the answer: a stream that
     * breaks half-way is not re-asked silently, because what was shown would then be taken back.
     */
    suspend fun stream(request: Request, onLive: suspend (Live) -> Unit): Reply = withContext(Dispatchers.IO) {
        require(request.apiKey.isNotBlank()) { "no API key — set %Gengoshima_ApiKey in 日本語の設定" }
        val body = body(request, stream = true).toRequestBody("application/json".toMediaType())
        val call = HttpRequest.Builder()
            .url(URL)
            .header("x-api-key", request.apiKey)
            .header("anthropic-version", VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .apply { if (request.fallbacks) header("anthropic-beta", FALLBACK_BETA) }
            .post(body)
            .build()
        var attempt = 0
        while (true) {
            attempt++
            var waitMs: Long? = null
            val failure: IOException = try {
                http.newCall(call).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val raw = resp.body.string()
                        val error = ApiException(resp.code, errorMessage(resp.code, raw))
                        if (resp.code != 429 && resp.code < 500) throw error
                        waitMs = resp.header("retry-after")?.toLongOrNull()?.times(1000)
                        return@use error
                    }
                    return@withContext readStream(resp.body.source(), onLive)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                e
            }
            if (attempt >= MAX_ATTEMPTS) throw failure
            delay(waitMs ?: (2_000L shl (attempt - 1)).coerceAtMost(30_000L))
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** Fold the event stream into a [Reply], reporting progress on the way. */
    internal suspend fun readStream(source: okio.BufferedSource, onLive: suspend (Live) -> Unit): Reply {
        val text = StringBuilder()
        val thinking = StringBuilder()
        var model = ""
        var stop = ""
        var refusal: String? = null
        var inTokens = 0L
        var outTokens = 0L
        var phase = "thinking"
        var lastLive = 0L
        suspend fun live(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastLive < 250) return
            lastLive = now
            onLive(Live(phase, thinking.toString(), text.toString(), outTokens))
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (ev["type"]?.jsonPrimitive?.contentOrNull) {
                "message_start" -> {
                    val msg = ev["message"] as? JsonObject
                    model = msg?.get("model")?.jsonPrimitive?.contentOrNull.orEmpty()
                    (msg?.get("usage") as? JsonObject)?.let { u ->
                        inTokens = u["input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                    }
                    live(force = true)
                }
                "content_block_delta" -> {
                    val d = ev["delta"] as? JsonObject ?: continue
                    when (d["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> {
                            phase = "writing"
                            text.append(d["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        }
                        "thinking_delta" -> thinking.append(d["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                    live()
                }
                "message_delta" -> {
                    val d = ev["delta"] as? JsonObject
                    d?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stop = it }
                    if (stop == "refusal") {
                        refusal = (d?.get("stop_details") as? JsonObject)?.let { sd ->
                            listOfNotNull(
                                sd["category"]?.jsonPrimitive?.contentOrNull,
                                sd["explanation"]?.jsonPrimitive?.contentOrNull,
                            ).joinToString(": ")
                        }.orEmpty()
                    }
                    (ev["usage"] as? JsonObject)?.get("output_tokens")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?.let { outTokens = it }
                    live(force = true)
                }
                "error" -> {
                    val err = ev["error"] as? JsonObject
                    throw IOException(
                        "stream error — " + listOfNotNull(
                            err?.get("type")?.jsonPrimitive?.contentOrNull,
                            err?.get("message")?.jsonPrimitive?.contentOrNull,
                        ).joinToString(": "),
                    )
                }
                "message_stop" -> break
            }
        }
        if (stop.isEmpty()) throw IOException("the stream ended before Claude finished")
        return Reply(text.toString(), stop, model, inTokens, outTokens, refusal)
    }

    /** A schema helper: an object with these required properties and nothing else. */
    fun objectSchema(properties: Map<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        put("required", buildJsonArray { properties.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}
