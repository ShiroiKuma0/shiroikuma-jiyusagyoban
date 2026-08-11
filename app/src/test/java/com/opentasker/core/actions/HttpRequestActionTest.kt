package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpRequestActionTest {
    @Test
    fun supportsEveryDocumentedMethodAndBoundedBodies() = withServer { server ->
        val methods = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        methods.forEach { method ->
            server.enqueue(MockResponse.Builder().code(200).body(if (method == "HEAD") "" else "ok").build())
            val args = mutableMapOf(
                "method" to method,
                "url" to server.url("/method").toString(),
                "allow_http" to "true",
            )
            if (method !in setOf("GET", "HEAD")) args["body"] = "payload-$method"

            assertSuccess(runAction(args))
            val request = server.takeRequest()
            assertEquals(method, request.method)
            if (method !in setOf("GET", "HEAD")) assertEquals("payload-$method", request.body?.utf8().orEmpty())
        }
    }

    @Test
    fun sendsStructuredQueryHeadersAndAuthorizationAndCapturesOutputs() = withServer { server ->
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("X-Request-Id", "abc-123")
                .body("created")
                .build(),
        )
        val variables = VariableStore()
        val logs = mutableListOf<String>()

        val result = runAction(
            mapOf(
                "method" to "POST",
                "url" to server.url("/items").toString(),
                "query" to "name=Open Tasker\ntag=one two",
                "headers" to "Accept: application/json\nX-Custom: value",
                "authorization" to "Bearer top-secret",
                "content_type" to "application/json",
                "body" to "{\"enabled\":true}",
                "response_var" to "body",
                "status_var" to "status",
                "headers_var" to "response_headers",
                "allow_http" to "true",
            ),
            variables = variables,
            logger = logs::add,
        )

        assertSuccess(result)
        assertEquals("created", variables.get("body"))
        assertEquals("200", variables.get("status"))
        assertTrue(variables.get("response_headers").orEmpty().contains("X-Request-Id: abc-123"))
        val request = server.takeRequest()
        assertEquals("Open Tasker", request.url.queryParameter("name"))
        assertEquals("one two", request.url.queryParameter("tag"))
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("value", request.headers["X-Custom"])
        assertEquals("Bearer top-secret", request.headers["Authorization"])
        assertEquals("{\"enabled\":true}", request.body?.utf8().orEmpty())
        assertFalse(logs.joinToString().contains("top-secret"))
        assertFalse(logs.joinToString().contains("/items"))
    }

    @Test
    fun exposesBodiesAndStatusForClientAndServerErrors() = withServer { server ->
        listOf(404 to "missing", 503 to "unavailable").forEach { (status, body) ->
            server.enqueue(MockResponse.Builder().code(status).body(body).build())
            val variables = VariableStore()
            val result = runAction(
                mapOf(
                    "url" to server.url("/status").toString(),
                    "status_var" to "status",
                    "allow_http" to "true",
                ),
                variables,
            )

            assertFailure(result, "HTTP $status")
            assertEquals(status.toString(), variables.get("status"))
            assertEquals(body, variables.get("result"))
        }
    }

    @Test
    fun redirectsDefaultToNoneAndSameOriginCanBeEnabled() = withServer { server ->
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "/not-followed").body("redirect-body").build(),
        )
        val defaultVariables = VariableStore()
        val defaultResult = runAction(
            mapOf("url" to server.url("/start").toString(), "allow_http" to "true"),
            defaultVariables,
        )
        assertFailure(defaultResult, "HTTP 302")
        assertEquals("redirect-body", defaultVariables.get("result"))
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse.Builder().code(303).addHeader("Location", "/final").build())
        server.enqueue(MockResponse.Builder().code(200).body("done").build())
        val followed = runAction(
            mapOf(
                "method" to "POST",
                "url" to server.url("/redirect").toString(),
                "body" to "payload",
                "redirects" to "same_origin",
                "allow_http" to "true",
            ),
        )
        assertSuccess(followed)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun sameOriginPolicyRejectsCrossOriginRedirect() {
        val first = MockWebServer()
        val second = MockWebServer()
        first.start()
        second.start()
        try {
            first.enqueue(
                MockResponse.Builder().code(302).addHeader("Location", second.url("/blocked")).build(),
            )

            val result = runAction(
                mapOf(
                    "url" to first.url("/start").toString(),
                    "redirects" to "same_origin",
                    "allow_http" to "true",
                ),
            )

            assertFailure(result, "cross-origin redirects are blocked")
            assertEquals(0, second.requestCount)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun localNetworkDenialFailsBeforeOpeningTheConnection() = withServer { server ->
        val result = runBlocking {
            HttpRequestAction(
                localNetworkGuard = { ActionResult.Failure("local network permission denied") },
            ).run(
                context(),
                mapOf("url" to server.url("/private").toString(), "allow_http" to "true"),
            )
        }

        assertFailure(result, "local network permission denied")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun streamsFileInputAndAtomicallyPublishesFileOutput() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(201).addHeader("X-Saved", "yes").body("response-file").build())
        val filesDir = Files.createTempDirectory("opentasker-http-files").toFile()
        try {
            File(filesDir, "user_files/input.txt").apply {
                parentFile?.mkdirs()
                writeText("request-file")
            }
            val variables = VariableStore()

            val result = runAction(
                mapOf(
                    "method" to "PUT",
                    "url" to server.url("/upload").toString(),
                    "body_file" to "input.txt",
                    "content_type" to "text/plain",
                    "output_file" to "responses/output.txt",
                    "status_var" to "status",
                    "headers_var" to "headers",
                    "allow_http" to "true",
                ),
                variables,
                filesDir,
            )

            assertSuccess(result)
            assertEquals("request-file", server.takeRequest().body?.utf8().orEmpty())
            assertEquals("response-file", File(filesDir, "user_files/responses/output.txt").readText())
            assertEquals("201", variables.get("status"))
            assertTrue(variables.get("headers").orEmpty().contains("X-Saved: yes"))
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun anErrorStatusLeavesAnExistingDownloadUntouched() = withServer { server ->
        // A scheduled download to a fixed path used to overwrite the good file with the error
        // page whenever the server returned 4xx/5xx, while still reporting failure.
        server.enqueue(MockResponse.Builder().code(503).body("<html>Service Unavailable</html>").build())
        val filesDir = Files.createTempDirectory("opentasker-http-error-status").toFile()
        try {
            val destination = File(filesDir, "user_files/report.json").apply {
                parentFile?.mkdirs()
                writeText("""{"good":true}""")
            }

            val result = runAction(
                mapOf(
                    "url" to server.url("/report").toString(),
                    "output_file" to "report.json",
                    "allow_http" to "true",
                ),
                filesDir = filesDir,
            )

            assertFailure(result, "HTTP 503")
            assertEquals("""{"good":true}""", destination.readText())
            assertTrue(destination.parentFile?.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun oversizedFileResponseKeepsExistingDestinationAndCleansStaging() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(200).body("too-large").build())
        val filesDir = Files.createTempDirectory("opentasker-http-atomic").toFile()
        try {
            val destination = File(filesDir, "user_files/output.txt").apply {
                parentFile?.mkdirs()
                writeText("original")
            }
            val result = runAction(
                mapOf(
                    "url" to server.url("/large").toString(),
                    "output_file" to "output.txt",
                    "max_response_bytes" to "4",
                    "allow_http" to "true",
                ),
                filesDir = filesDir,
            )

            assertFailure(result, "response exceeds 4 byte limit")
            assertEquals("original", destination.readText())
            assertTrue(destination.parentFile?.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun cancellationCancelsAnInFlightCall() = withServer { server ->
        server.enqueue(
            MockResponse.Builder().code(200).body("slow").bodyDelay(5, TimeUnit.SECONDS).build(),
        )
        try {
            runBlocking {
                withTimeout(200) {
                    HttpRequestAction().run(
                        context(),
                        mapOf("url" to server.url("/slow").toString(), "allow_http" to "true"),
                    )
                }
            }
            fail("request should have been cancelled")
        } catch (_: TimeoutCancellationException) {
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun rejectsTlsBypassMalformedStructuredFieldsAndUnsafeFilePaths() {
        assertFailure(
            runAction(mapOf("url" to "https://example.com", "trust_all" to "true")),
            "TLS verification cannot be disabled",
        )
        assertFailure(
            runAction(mapOf("url" to "https://example.com", "headers" to "not-a-header")),
            "headers must use Name: Value",
        )
        val invalidAuthorization = runAction(
            mapOf("url" to "https://example.com", "authorization" to "Bearer secret\nleak"),
        )
        assertFailure(invalidAuthorization, "invalid Authorization header")
        assertFalse((invalidAuthorization as ActionResult.Failure).message.contains("secret"))
        assertFailure(
            runAction(mapOf("url" to "https://example.com", "query" to "not-a-query")),
            "query entries must use name=value",
        )
        assertFailure(
            runAction(mapOf("method" to "PUT", "url" to "https://example.com", "body_file" to "../secret")),
            "body_file is outside OpenTasker files or missing",
        )
        assertFailure(
            runAction(mapOf("url" to "https://example.com", "timeout_sec" to "eventually")),
            "timeout_sec must be an integer",
        )
        assertFailure(
            runAction(mapOf("url" to "https://example.com", "max_response_bytes" to "unbounded")),
            "max_response_bytes must be an integer",
        )
    }

    @Test
    fun legacyGetAndPostIdsDelegateWithoutBundleMigration() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(200).body("get-result").build())
        server.enqueue(MockResponse.Builder().code(200).body("post-result").build())
        val variables = VariableStore()

        assertSuccess(
            runBlocking {
                HttpGetAction().run(
                    context(variables = variables),
                    mapOf("url" to server.url("/get").toString(), "var" to "legacy_get", "allow_http" to "true"),
                )
            },
        )
        assertSuccess(
            runBlocking {
                HttpPostAction().run(
                    context(variables = variables),
                    mapOf(
                        "url" to server.url("/post").toString(),
                        "data" to "legacy-body",
                        "variable" to "legacy_post",
                        "allow_http" to "true",
                    ),
                )
            },
        )

        assertEquals("get-result", variables.get("legacy_get"))
        assertEquals("post-result", variables.get("legacy_post"))
        assertEquals("GET", server.takeRequest().method)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("legacy-body", post.body?.utf8().orEmpty())
    }

    private fun runAction(
        args: Map<String, String>,
        variables: VariableStore = VariableStore(),
        filesDir: File = Files.createTempDirectory("opentasker-http-context").toFile(),
        logger: (String) -> Unit = {},
    ): ActionResult = try {
        runBlocking { HttpRequestAction().run(context(filesDir, variables, logger), args) }
    } finally {
        if (filesDir.name.startsWith("opentasker-http-context")) filesDir.deleteRecursively()
    }

    private fun context(
        filesDir: File = Files.createTempDirectory("opentasker-http-context").toFile(),
        variables: VariableStore = VariableStore(),
        logger: (String) -> Unit = {},
    ): ActionContext = ActionContext(
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        },
        variables,
        logger = logger,
    )

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun assertSuccess(result: ActionResult) {
        assertTrue("Expected success, got $result", result is ActionResult.Success)
    }

    private fun assertFailure(result: ActionResult, expectedMessage: String) {
        assertTrue("Expected failure, got $result", result is ActionResult.Failure)
        assertEquals(expectedMessage, (result as ActionResult.Failure).message)
    }
}
