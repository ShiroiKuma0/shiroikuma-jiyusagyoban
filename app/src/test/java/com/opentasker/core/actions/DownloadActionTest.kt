package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `download` action now runs entirely on the shared [HttpRequestAction] transport:
 * files land in the user_files sandbox (readable by file.*), same-origin redirects are followed,
 * the size cap is enforced, and the LAN-permission gate blocks before the connection opens.
 */
class DownloadActionTest {
    @Test
    fun writesResponseIntoTheUserFilesSandbox() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(200).body("payload-bytes").build())
        val filesDir = Files.createTempDirectory("opentasker-download").toFile()
        try {
            val result = runDownload(
                mapOf(
                    "url" to server.url("/file.bin").toString(),
                    "path" to "downloads/file.bin",
                    "allow_http" to "true",
                ),
                filesDir,
            )
            assertTrue("Expected success, got $result", result is ActionResult.Success)
            assertEquals("payload-bytes", File(filesDir, "user_files/downloads/file.bin").readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun followsSameOriginRedirectByDefault() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/final.bin").build())
        server.enqueue(MockResponse.Builder().code(200).body("redirected").build())
        val filesDir = Files.createTempDirectory("opentasker-download-redir").toFile()
        try {
            val result = runDownload(
                mapOf(
                    "url" to server.url("/start.bin").toString(),
                    "path" to "out.bin",
                    "allow_http" to "true",
                ),
                filesDir,
            )
            assertTrue("Expected success, got $result", result is ActionResult.Success)
            assertEquals("redirected", File(filesDir, "user_files/out.bin").readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun enforcesTheSizeCap() = withServer { server ->
        server.enqueue(MockResponse.Builder().code(200).body("way-too-large").build())
        val filesDir = Files.createTempDirectory("opentasker-download-cap").toFile()
        try {
            val result = runDownload(
                mapOf(
                    "url" to server.url("/big.bin").toString(),
                    "path" to "big.bin",
                    "max_bytes" to "4",
                    "allow_http" to "true",
                ),
                filesDir,
            )
            assertTrue("Expected failure, got $result", result is ActionResult.Failure)
            assertFalse("Partial file must not be published", File(filesDir, "user_files/big.bin").exists())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun localNetworkDenialBlocksBeforeConnecting() = withServer { server ->
        val filesDir = Files.createTempDirectory("opentasker-download-lan").toFile()
        try {
            val result = runBlocking {
                DownloadAction(
                    HttpRequestAction(localNetworkGuard = { ActionResult.Failure("local network permission denied") }),
                ).run(
                    context(filesDir),
                    mapOf(
                        "url" to server.url("/lan.bin").toString(),
                        "path" to "lan.bin",
                        "allow_http" to "true",
                    ),
                )
            }
            assertTrue("Expected failure, got $result", result is ActionResult.Failure)
            assertEquals("local network permission denied", (result as ActionResult.Failure).message)
            assertEquals(0, server.requestCount)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingArguments() {
        assertTrue(runDownloadNoServer(mapOf("path" to "x")) is ActionResult.Failure)
        assertTrue(runDownloadNoServer(mapOf("url" to "https://example.com")) is ActionResult.Failure)
        assertTrue(
            runDownloadNoServer(mapOf("url" to "https://example.com", "path" to "x", "max_bytes" to "-1"))
                is ActionResult.Failure,
        )
    }

    private fun runDownload(args: Map<String, String>, filesDir: File): ActionResult =
        runBlocking { DownloadAction().run(context(filesDir), args) }

    private fun runDownloadNoServer(args: Map<String, String>): ActionResult {
        val filesDir = Files.createTempDirectory("opentasker-download-arg").toFile()
        return try {
            runBlocking { DownloadAction().run(context(filesDir), args) }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun context(filesDir: File): ActionContext = ActionContext(
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        },
        VariableStore(),
        logger = {},
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
}
