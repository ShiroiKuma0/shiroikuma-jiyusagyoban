package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.ActionRetrySafety
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A bounded, cancellable HTTP action with explicit redirect and cleartext policies. */
class HttpRequestAction(
    private val baseClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
    private val localNetworkGuard: (ActionContext) -> ActionResult? = ::checkLocalNetworkPermission,
) : Action {
    override val id = ID
    override val category = ActionCategory.NET
    override val retrySafety = ActionRetrySafety.NEVER

    override fun retrySafetyFor(args: Map<String, String>): ActionRetrySafety = when (
        args["method"]?.trim()?.uppercase() ?: "GET"
    ) {
        "GET", "HEAD" -> ActionRetrySafety.IDEMPOTENT
        else -> ActionRetrySafety.NEVER
    }

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val config = try {
            HttpRequestConfig.parse(ctx, args)
        } catch (error: IllegalArgumentException) {
            return ActionResult.Failure(error.message ?: "invalid HTTP request")
        }

        var url = config.url
        var method = config.method
        var body = config.body
        var redirects = 0

        while (true) {
            enforceHttpPolicy(URL(url.toString()), args)?.let { return it }
            if (urlTargetsLocalNetwork(URL(url.toString()))) {
                localNetworkGuard(ctx)?.let { return it }
            }

            val request = buildRequest(url, method, body, config.headers)
            when (val attempt = execute(request, config, config.redirectPolicy == RedirectPolicy.SAME_ORIGIN)) {
                is HttpAttempt.Failed -> {
                    return ActionResult.Failure(
                        attempt.publicMessage ?: "request failed: ${attempt.error.javaClass.simpleName}",
                    )
                }
                is HttpAttempt.Redirect -> {
                    if (redirects >= MAX_REDIRECTS) {
                        return ActionResult.Failure("redirect limit ($MAX_REDIRECTS) exceeded")
                    }
                    val target = url.resolve(attempt.location)
                        ?: return ActionResult.Failure("redirect Location is invalid")
                    if (!url.sameOrigin(target)) {
                        return ActionResult.Failure("cross-origin redirects are blocked")
                    }
                    redirects += 1
                    url = target
                    if (attempt.status == 303 && method != "HEAD") {
                        method = "GET"
                        body = null
                    }
                }
                is HttpAttempt.Complete -> {
                    config.statusVariable?.let { ctx.variables.set(it, attempt.status.toString()) }
                    config.headersVariable?.let { ctx.variables.set(it, attempt.headers) }
                    config.responseVariable?.let { ctx.variables.set(it, attempt.body.orEmpty()) }
                    val destination = config.outputFile?.name?.let { " to $it" }.orEmpty()
                    ctx.logger("HTTP $method ${url.host} -> ${attempt.status}$destination")
                    return if (attempt.status in 200..299) {
                        ActionResult.Success
                    } else {
                        ActionResult.Failure("HTTP ${attempt.status}")
                    }
                }
            }
        }
    }

    private suspend fun execute(
        request: Request,
        config: HttpRequestConfig,
        captureRedirect: Boolean,
    ): HttpAttempt = suspendCancellableCoroutine { continuation ->
        val client = baseClient.newBuilder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.callTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .dns(if (request.url.scheme == "http") PRIVATE_ONLY_DNS else Dns.SYSTEM)
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(HttpAttempt.Failed(e)) { _, _, _ -> }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { current ->
                        val location = current.header("Location")
                        if (captureRedirect && current.code in REDIRECT_CODES && location != null) {
                            HttpAttempt.Redirect(current.code, location)
                        } else {
                            readCompleteResponse(current, config)
                        }
                    }
                } catch (error: IOException) {
                    HttpAttempt.Failed(error)
                } catch (error: IllegalStateException) {
                    val message = error.message ?: "invalid HTTP response"
                    HttpAttempt.Failed(IOException(message, error), message)
                }
                continuation.resume(result) { _, _, _ -> }
            }
        })
    }

    private fun readCompleteResponse(response: Response, config: HttpRequestConfig): HttpAttempt {
        val serializedHeaders = response.headers.toBoundedText()
        val responseBody = response.body
        val declaredLength = responseBody.contentLength()
        if (declaredLength > config.maxResponseBytes) {
            throw IllegalStateException("response exceeds ${config.maxResponseBytes} byte limit")
        }

        val bodyText = if (config.outputFile != null) {
            writeResponseAtomically(responseBody.byteStream(), config.outputFile, config.maxResponseBytes)
            null
        } else {
            val bytes = responseBody.byteStream().readBoundedBytes(config.maxResponseBytes)
            val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            bytes.toString(charset)
        }
        return HttpAttempt.Complete(response.code, serializedHeaders, bodyText)
    }

    companion object {
        const val ID = "http.request"

        private val DEFAULT_HTTP_CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        private val PRIVATE_ONLY_DNS = Dns { hostname ->
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPrivateOrLocalAddress(it) }) {
                throw UnknownHostException("cleartext target did not resolve exclusively to private/LAN addresses")
            }
            addresses
        }
    }
}

/** Compatibility execution path for stored `http.get` actions. New actions use [HttpRequestAction]. */
class HttpGetAction(
    private val delegate: HttpRequestAction = HttpRequestAction(),
) : Action {
    override val id = "http.get"
    override val category = ActionCategory.NET
    override val retrySafety = ActionRetrySafety.IDEMPOTENT

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (args["url"].isNullOrBlank()) return ActionResult.Failure("missing url")
        return delegate.run(
            ctx,
            args + mapOf(
                "method" to "GET",
                "response_var" to (args["var"] ?: args["variable"] ?: "result"),
            ),
        )
    }
}

/** Compatibility execution path for stored `http.post` actions. New actions use [HttpRequestAction]. */
class HttpPostAction(
    private val delegate: HttpRequestAction = HttpRequestAction(),
) : Action {
    override val id = "http.post"
    override val category = ActionCategory.NET
    override val retrySafety = ActionRetrySafety.NEVER

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (args["url"].isNullOrBlank()) return ActionResult.Failure("missing url")
        val legacyBody = args["data"] ?: args["body"].orEmpty()
        if (legacyBody.toByteArray(Charsets.UTF_8).size > MAX_INLINE_REQUEST_BYTES) {
            return ActionResult.Failure("POST body exceeds ${MAX_INLINE_REQUEST_BYTES / 1024} KB limit")
        }
        return delegate.run(
            ctx,
            args + mapOf(
                "method" to "POST",
                "body" to legacyBody,
                "response_var" to (args["var"] ?: args["variable"] ?: "result"),
            ),
        )
    }
}

private data class HttpRequestConfig(
    val method: String,
    val url: HttpUrl,
    val headers: Headers,
    val body: RequestBody?,
    val redirectPolicy: RedirectPolicy,
    val responseVariable: String?,
    val statusVariable: String?,
    val headersVariable: String?,
    val outputFile: File?,
    val maxResponseBytes: Long,
    val connectTimeoutSeconds: Long,
    val readTimeoutSeconds: Long,
    val writeTimeoutSeconds: Long,
    val callTimeoutSeconds: Long,
) {
    companion object {
        fun parse(ctx: ActionContext, args: Map<String, String>): HttpRequestConfig {
            val tlsBypass = args.keys.firstOrNull { it.lowercase() in FORBIDDEN_TLS_ARGUMENTS }
            require(tlsBypass == null) { "TLS verification cannot be disabled" }

            val rawMethod = args["method"].orEmpty().ifBlank { "GET" }
            require(rawMethod.length <= MAX_METHOD_CHARS) { "method exceeds $MAX_METHOD_CHARS character limit" }
            val method = rawMethod.uppercase()
            require(method in HTTP_METHODS) { "unsupported method: $method" }
            val rawUrl = args["url"]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("missing or invalid url")
            require(rawUrl.toByteArray(Charsets.UTF_8).size <= MAX_URL_BYTES) { "url exceeds $MAX_URL_BYTES byte limit" }
            val scheme = runCatching { URI(rawUrl).scheme?.lowercase() }.getOrNull()
                ?: throw IllegalArgumentException("missing or invalid url")
            require(scheme == "http" || scheme == "https") { "unsupported protocol: $scheme" }
            val baseUrl = rawUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("missing or invalid url")
            val urlBuilder = baseUrl.newBuilder()
            parseQuery(args["query"]).forEach { (name, value) -> urlBuilder.addQueryParameter(name, value) }

            val headers = parseHeaders(args["headers"]).newBuilder()
            args["authorization"]?.takeIf(String::isNotBlank)?.let { authorization ->
                require(authorization.toByteArray(Charsets.UTF_8).size <= MAX_AUTHORIZATION_BYTES) {
                    "authorization exceeds $MAX_AUTHORIZATION_BYTES byte limit"
                }
                require(headers["Authorization"] == null) { "Authorization is defined twice" }
                try {
                    headers.add("Authorization", authorization)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("invalid Authorization header")
                }
            }
            val contentTypeText = args["content_type"]?.takeIf(String::isNotBlank)
            require(contentTypeText == null || contentTypeText.length <= MAX_CONTENT_TYPE_CHARS) {
                "content_type exceeds $MAX_CONTENT_TYPE_CHARS character limit"
            }
            if (contentTypeText != null && headers["Content-Type"] == null) {
                try {
                    headers.add("Content-Type", contentTypeText)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("invalid content_type")
                }
            }
            val builtHeaders = headers.build()
            val mediaType = builtHeaders["Content-Type"]?.toMediaTypeOrNull()
            require(builtHeaders["Content-Type"] == null || mediaType != null) { "invalid content_type" }

            val inlineBody = args["body"]?.takeIf(String::isNotEmpty)
            val bodyFilePath = args["body_file"]?.takeIf(String::isNotBlank)
            require(inlineBody == null || bodyFilePath == null) { "body and body_file are mutually exclusive" }
            val requestBody = when {
                inlineBody != null -> {
                    val bytes = inlineBody.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_INLINE_REQUEST_BYTES) {
                        "request body exceeds $MAX_INLINE_REQUEST_BYTES byte limit"
                    }
                    bytes.toRequestBody(mediaType)
                }
                bodyFilePath != null -> {
                    val file = safeHttpUserFile(ctx, bodyFilePath, mustExist = true)
                        ?: throw IllegalArgumentException("body_file is outside OpenTasker files or missing")
                    require(file.isFile) { "body_file is not a file" }
                    require(file.length() <= MAX_FILE_REQUEST_BYTES) {
                        "body_file exceeds $MAX_FILE_REQUEST_BYTES byte limit"
                    }
                    file.asRequestBody(mediaType)
                }
                method in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody(mediaType)
                else -> null
            }
            require(method !in METHODS_FORBIDDING_BODY || requestBody == null) { "$method does not accept a request body" }

            val outputPath = args["output_file"]?.takeIf(String::isNotBlank)
            val responseVariable = args["response_var"]?.takeIf(String::isNotBlank)
                ?: if (outputPath == null) "result" else null
            require(outputPath == null || args["response_var"].isNullOrBlank()) {
                "response_var and output_file are mutually exclusive"
            }
            val outputFile = outputPath?.let { path ->
                safeHttpUserFile(ctx, path) ?: throw IllegalArgumentException("output_file is outside OpenTasker files")
            }
            val responseCap = if (outputFile == null) MAX_VARIABLE_RESPONSE_BYTES else MAX_FILE_RESPONSE_BYTES
            val maxResponseBytes = args["max_response_bytes"]?.let { raw ->
                raw.toLongOrNull() ?: throw IllegalArgumentException("max_response_bytes must be an integer")
            } ?: responseCap
            require(maxResponseBytes in 1..responseCap) { "max_response_bytes must be between 1 and $responseCap" }

            val defaultTimeout = parseTimeout(args, "timeout_sec", DEFAULT_TIMEOUT_SECONDS)
            val callTimeout = parseTimeout(args, "call_timeout_sec", defaultTimeout)
            val connectTimeout = parseTimeout(args, "connect_timeout_sec", defaultTimeout)
            val readTimeout = parseTimeout(args, "read_timeout_sec", defaultTimeout)
            val writeTimeout = parseTimeout(args, "write_timeout_sec", defaultTimeout)
            val redirectPolicy = when (args["redirects"].orEmpty().ifBlank { "none" }.lowercase()) {
                "none" -> RedirectPolicy.NONE
                "same_origin" -> RedirectPolicy.SAME_ORIGIN
                else -> throw IllegalArgumentException("redirects must be none or same_origin")
            }

            return HttpRequestConfig(
                method = method,
                url = urlBuilder.build(),
                headers = builtHeaders,
                body = requestBody,
                redirectPolicy = redirectPolicy,
                responseVariable = responseVariable,
                statusVariable = args["status_var"]?.takeIf(String::isNotBlank),
                headersVariable = args["headers_var"]?.takeIf(String::isNotBlank),
                outputFile = outputFile,
                maxResponseBytes = maxResponseBytes,
                connectTimeoutSeconds = connectTimeout,
                readTimeoutSeconds = readTimeout,
                writeTimeoutSeconds = writeTimeout,
                callTimeoutSeconds = callTimeout,
            )
        }
    }
}

private sealed interface HttpAttempt {
    data class Redirect(val status: Int, val location: String) : HttpAttempt
    data class Complete(val status: Int, val headers: String, val body: String?) : HttpAttempt
    data class Failed(val error: IOException, val publicMessage: String? = null) : HttpAttempt
}

private enum class RedirectPolicy { NONE, SAME_ORIGIN }

private fun buildRequest(url: HttpUrl, method: String, body: RequestBody?, headers: Headers): Request =
    Request.Builder()
        .url(url)
        .headers(headers)
        .method(method, body)
        .build()

private fun parseQuery(raw: String?): List<Pair<String, String>> = parseStructuredLines(raw, MAX_QUERY_ENTRIES) { line ->
    val separator = line.indexOf('=')
    require(separator > 0) { "query entries must use name=value" }
    val name = line.substring(0, separator).trim()
    val value = line.substring(separator + 1).trim()
    require(name.isNotBlank() && name.none(Char::isISOControl)) { "invalid query name" }
    name to value
}

private fun parseHeaders(raw: String?): Headers {
    val builder = Headers.Builder()
    parseStructuredLines(raw, MAX_HEADER_ENTRIES) { line ->
        val separator = line.indexOf(':')
        require(separator > 0) { "headers must use Name: Value" }
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        require(name.lowercase() !in RESTRICTED_REQUEST_HEADERS) { "$name is managed by the HTTP client" }
        name to value
    }.forEach { (name, value) ->
        try {
            builder.add(name, value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid header: $name")
        }
    }
    return builder.build()
}

private fun <T> parseStructuredLines(raw: String?, limit: Int, parse: (String) -> T): List<T> {
    if (raw.isNullOrBlank()) return emptyList()
    require(raw.toByteArray(Charsets.UTF_8).size <= MAX_STRUCTURED_FIELD_BYTES) {
        "structured field exceeds $MAX_STRUCTURED_FIELD_BYTES byte limit"
    }
    val lines = raw.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    require(lines.size <= limit) { "structured field exceeds $limit entries" }
    return lines.map(parse)
}

private fun parseTimeout(args: Map<String, String>, key: String, default: Long): Long {
    val raw = args[key] ?: return default
    val value = raw.toLongOrNull() ?: throw IllegalArgumentException("$key must be an integer")
    require(value in 1..MAX_TIMEOUT_SECONDS) { "$key must be between 1 and $MAX_TIMEOUT_SECONDS" }
    return value
}

private fun Headers.toBoundedText(): String {
    val text = buildString {
        for (index in 0 until size) append(name(index)).append(": ").append(value(index)).append('\n')
    }.trimEnd()
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_HEADER_BYTES) {
        "response headers exceed $MAX_RESPONSE_HEADER_BYTES byte limit"
    }
    return text
}

private fun java.io.InputStream.readBoundedBytes(limit: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw IllegalStateException("response exceeds $limit byte limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun writeResponseAtomically(input: java.io.InputStream, destination: File, limit: Long) {
    destination.parentFile?.mkdirs()
    val temp = File.createTempFile(destination.name.ifBlank { "response" }.padEnd(3, '_'), ".part", destination.parentFile)
    try {
        temp.outputStream().use { output ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IllegalStateException("response exceeds $limit byte limit")
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
        replaceHttpFile(temp, destination)
    } finally {
        if (temp.exists()) temp.delete()
    }
}

private fun replaceHttpFile(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun safeHttpUserFile(ctx: ActionContext, path: String, mustExist: Boolean = false): File? {
    if (path.isBlank() || path.length > MAX_FILE_PATH_CHARS || path.contains('\u0000')) return null
    val baseDir = File(ctx.app.filesDir, "user_files").canonicalFile
    val requested = File(baseDir, path.trimStart('/', '\\')).canonicalFile
    if (!requested.path.startsWith(baseDir.path + File.separator) && requested != baseDir) return null
    if (mustExist && !requested.exists()) return null
    return requested
}

private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
private val METHODS_FORBIDDING_BODY = setOf("GET", "HEAD")
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val RESTRICTED_REQUEST_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection")
private val FORBIDDEN_TLS_ARGUMENTS = setOf("insecure_tls", "ignore_tls", "trust_all", "verify_tls")
private const val MAX_REDIRECTS = 5
private const val MAX_METHOD_CHARS = 16
private const val MAX_URL_BYTES = 8_192
private const val MAX_AUTHORIZATION_BYTES = 8_192
private const val MAX_CONTENT_TYPE_CHARS = 256
private const val MAX_FILE_PATH_CHARS = 512
private const val MAX_QUERY_ENTRIES = 64
private const val MAX_HEADER_ENTRIES = 64
private const val MAX_STRUCTURED_FIELD_BYTES = 32_768
private const val MAX_RESPONSE_HEADER_BYTES = 65_536
private const val MAX_INLINE_REQUEST_BYTES = 1_048_576
private const val MAX_FILE_REQUEST_BYTES = 10_485_760L
private const val MAX_VARIABLE_RESPONSE_BYTES = 1_048_576L
private const val MAX_FILE_RESPONSE_BYTES = 52_428_800L
private const val DEFAULT_TIMEOUT_SECONDS = 30L
private const val MAX_TIMEOUT_SECONDS = 120L
private const val STREAM_BUFFER_BYTES = 8_192
