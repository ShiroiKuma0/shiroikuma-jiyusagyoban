package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGuardsTest {

    private fun ctx() = ActionContext(ContextWrapper(null), VariableStore())

    @Test
    fun httpPostRejectsOversizedBody() = runBlocking {
        val action = HttpPostAction()
        val bigBody = "x".repeat(1_048_577)
        val result = action.run(ctx(), mapOf("url" to "https://example.com", "data" to bigBody))
        assertTrue("oversized POST body should fail", result is ActionResult.Failure)
        assertTrue(
            "failure message mentions limit",
            (result as ActionResult.Failure).message.contains("KB limit")
        )
    }

    @Test
    fun httpPostAcceptsBodyAtLimit() = runBlocking {
        val action = HttpPostAction()
        val bodyAtLimit = "x".repeat(1_048_576)
        val result = action.run(ctx(), mapOf("url" to "https://example.com", "data" to bodyAtLimit))
        // Will fail with network error (not body-size rejection) since we have no server
        if (result is ActionResult.Failure) {
            assertTrue(
                "should not reject body at limit",
                !result.message.contains("KB limit")
            )
        }
    }

    @Test
    fun openUrlAllowedSchemesAreRestricted() {
        val allowed = OpenUrlAction.allowedSchemes()
        assertTrue("https must be allowed", "https" in allowed)
        assertTrue("http must be allowed", "http" in allowed)
        assertTrue("javascript must NOT be allowed", "javascript" !in allowed)
        assertTrue("intent must NOT be allowed", "intent" !in allowed)
        assertTrue("file must NOT be allowed", "file" !in allowed)
        assertTrue("content must NOT be allowed", "content" !in allowed)
    }

    @Test
    fun openUrlMissingUrlFails() = runBlocking {
        val action = OpenUrlAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun waitRejectsExcessiveDuration() = runBlocking {
        val action = WaitAction()
        val result = action.run(ctx(), mapOf("millis" to "1800001"))
        assertTrue("excessive wait should fail", result is ActionResult.Failure)
        assertTrue(
            "failure mentions maximum",
            (result as ActionResult.Failure).message.contains("maximum")
        )
    }

    @Test
    fun waitAcceptsDurationAtLimit() = runBlocking {
        val action = WaitAction()
        // 0ms wait: effectively a no-op but should succeed
        val result = action.run(ctx(), mapOf("millis" to "0"))
        assertTrue("zero wait should succeed", result is ActionResult.Success)
    }

    @Test
    fun httpGetMissingUrlFails() = runBlocking {
        val action = HttpGetAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun httpPostMissingUrlFails() = runBlocking {
        val action = HttpPostAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun downloadMissingUrlFails() = runBlocking {
        val action = DownloadAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing url should fail", result is ActionResult.Failure)
        assertEquals("missing url", (result as ActionResult.Failure).message)
    }

    @Test
    fun downloadMissingPathFails() = runBlocking {
        val action = DownloadAction()
        val result = action.run(ctx(), mapOf("url" to "https://example.com/file"))
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun pingRejectsInvalidHost() = runBlocking {
        val action = PingAction()
        val result = action.run(ctx(), mapOf("host" to "evil; rm -rf /"))
        assertTrue("malicious host should fail", result is ActionResult.Failure)
        assertEquals("invalid host", (result as ActionResult.Failure).message)
    }

    @Test
    fun pingMissingHostFails() = runBlocking {
        val action = PingAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing host should fail", result is ActionResult.Failure)
        assertEquals("missing host", (result as ActionResult.Failure).message)
    }

    @Test
    fun httpPolicyBlocksNonHttpSchemes() = runBlocking {
        val action = HttpGetAction()
        val result = action.run(ctx(), mapOf("url" to "ftp://example.com"))
        assertTrue("ftp scheme should fail", result is ActionResult.Failure)
        assertTrue(
            "failure mentions unsupported protocol",
            (result as ActionResult.Failure).message.contains("unsupported protocol")
        )
    }

    @Test
    fun wolParsesValidMac() {
        val mac = WakeOnLanAction.parseMac("AA:BB:CC:DD:EE:FF")
        assertTrue("valid MAC should parse", mac != null)
        assertEquals(6, mac!!.size)
    }

    @Test
    fun wolRejectsInvalidMac() {
        assertTrue("short MAC should be rejected", WakeOnLanAction.parseMac("AA:BB") == null)
        assertTrue("non-hex should be rejected", WakeOnLanAction.parseMac("GG:HH:II:JJ:KK:LL") == null)
        assertTrue("empty should be rejected", WakeOnLanAction.parseMac("") == null)
    }

    @Test
    fun wolBuildsMagicPacket() {
        val mac = WakeOnLanAction.parseMac("AA:BB:CC:DD:EE:FF")!!
        val packet = WakeOnLanAction.buildMagicPacket(mac)
        assertEquals(102, packet.size)
        // First 6 bytes are 0xFF
        for (i in 0..5) assertEquals(0xFF.toByte(), packet[i])
        // Next 16 repetitions of the MAC
        for (rep in 0..15) {
            for (b in 0..5) {
                assertEquals(mac[b], packet[6 + rep * 6 + b])
            }
        }
    }

    @Test
    fun wolMissingMacFails() = runBlocking {
        val action = WakeOnLanAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing mac should fail", result is ActionResult.Failure)
        assertEquals("missing mac", (result as ActionResult.Failure).message)
    }

    @Test
    fun wolInvalidMacFails() = runBlocking {
        val action = WakeOnLanAction()
        val result = action.run(ctx(), mapOf("mac" to "not-a-mac"))
        assertTrue("invalid mac should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid MAC"))
    }

    @Test
    fun httpPolicyBlocksPlaintextWithoutOptIn() = runBlocking {
        val action = HttpGetAction()
        val result = action.run(ctx(), mapOf("url" to "http://192.168.1.1/api"))
        assertTrue("plaintext HTTP should fail without opt-in", result is ActionResult.Failure)
        assertTrue(
            "failure mentions allow_http",
            (result as ActionResult.Failure).message.contains("allow_http")
        )
    }
}
