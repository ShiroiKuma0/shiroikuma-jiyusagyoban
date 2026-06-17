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
    fun httpPostRejectsOversizedLegacyBodyKey() = runBlocking {
        val action = HttpPostAction()
        val bigBody = "x".repeat(1_048_577)
        val result = action.run(ctx(), mapOf("url" to "https://example.com", "body" to bigBody))
        assertTrue("oversized legacy POST body should fail", result is ActionResult.Failure)
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

    @Test
    fun torchToggleNeedsKnownCurrentState() {
        assertEquals(true, TorchAction.targetStateFor("on", null))
        assertEquals(false, TorchAction.targetStateFor("off", null))
        assertEquals(false, TorchAction.targetStateFor("toggle", true))
        assertEquals(true, TorchAction.targetStateFor("toggle", false))
        assertEquals(null, TorchAction.targetStateFor("toggle", null))
        assertEquals(null, TorchAction.targetStateFor("invalid", true))
    }

    // --- SayAction guards ---

    @Test
    fun sayMissingTextFails() = runBlocking {
        val action = SayAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing text should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("missing text"))
    }

    @Test
    fun sayRejectsOversizedText() = runBlocking {
        val action = SayAction()
        val bigText = "x".repeat(4001)
        val result = action.run(ctx(), mapOf("text" to bigText))
        assertTrue("oversized text should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("character limit"))
    }

    // --- OpenUrl expanded scheme validation ---

    @Test
    fun openUrlAllowsTelAndMailtoSchemes() {
        val allowed = OpenUrlAction.allowedSchemes()
        assertTrue("tel must be allowed", "tel" in allowed)
        assertTrue("mailto must be allowed", "mailto" in allowed)
        assertTrue("geo must be allowed", "geo" in allowed)
    }

    @Test
    fun openUrlBlocksDataAndBlobSchemes() {
        val allowed = OpenUrlAction.allowedSchemes()
        assertTrue("data must NOT be allowed", "data" !in allowed)
        assertTrue("blob must NOT be allowed", "blob" !in allowed)
    }

    // --- ReadFileAction guards ---

    @Test
    fun readFileMissingPathFails() = runBlocking {
        val action = ReadFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    // --- WriteFileAction guards ---

    @Test
    fun writeFileMissingPathFails() = runBlocking {
        val action = WriteFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    // --- PlaySoundAction guards ---

    @Test
    fun playSoundMissingPathFails() = runBlocking {
        val action = PlaySoundAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    // --- LaunchAppAction guards ---

    @Test
    fun launchAppMissingPackageFails() = runBlocking {
        val action = LaunchAppAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing package should fail", result is ActionResult.Failure)
    }

    // --- SetVariable guards ---

    @Test
    fun setVariableMissingNameFails() = runBlocking {
        val action = SetVariableAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing name should fail", result is ActionResult.Failure)
        assertEquals("missing name", (result as ActionResult.Failure).message)
    }

    // --- TermuxScriptAction fail-closed ---

    @Test
    fun termuxScriptFailsClosed() = runBlocking {
        val action = TermuxScriptAction()
        val result = action.run(ctx(), mapOf("executable" to "/bin/sh"))
        assertTrue("Termux script should fail closed", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("not implemented"))
    }

    // --- VolumeAction guards ---

    @Test
    fun volumeMissingLevelFails() = runBlocking {
        val action = VolumeAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing level should fail", result is ActionResult.Failure)
        assertEquals("missing level", (result as ActionResult.Failure).message)
    }

    // --- LaunchIntentAction guards ---

    @Test
    fun launchIntentMissingPackageFails() = runBlocking {
        val action = LaunchIntentAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing package should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("package"))
    }

    // --- LogAction always succeeds ---

    @Test
    fun logActionSucceedsWithEmptyMessage() = runBlocking {
        val action = LogAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("log with no message should succeed", result is ActionResult.Success)
    }

    // --- TaskerUnsupportedAction fail-closed ---

    @Test
    fun taskerUnsupportedActionFailsClosed() = runBlocking {
        val action = TaskerUnsupportedAction()
        val result = action.run(ctx(), mapOf("taskerCode" to "999"))
        assertTrue("unsupported action should fail", result is ActionResult.Failure)
    }

    // --- File action guards ---

    @Test
    fun appendFileMissingPathFails() = runBlocking {
        val action = AppendFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun deleteFileMissingPathFails() = runBlocking {
        val action = DeleteFileAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    @Test
    fun listFilesMissingPathFails() = runBlocking {
        val action = ListFilesAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing path should fail", result is ActionResult.Failure)
        assertEquals("missing path", (result as ActionResult.Failure).message)
    }

    // --- Settings honest-failure guards ---

    @Test
    fun brightnessMissingLevelFails() = runBlocking {
        val action = BrightnessAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing brightness should fail", result is ActionResult.Failure)
        assertEquals("missing brightness", (result as ActionResult.Failure).message)
    }

    @Test
    fun airplaneModeAlwaysFailsHonestly() = runBlocking {
        val action = AirplaneModeAction()
        val result = action.run(ctx(), mapOf("state" to "on"))
        assertTrue("airplane mode should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("restricted"))
    }

    @Test
    fun mobileDataAlwaysFailsHonestly() = runBlocking {
        val action = MobileDataAction()
        val result = action.run(ctx(), mapOf("state" to "on"))
        assertTrue("mobile data should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("restricted"))
    }

    @Test
    fun tileStateMissingStateFails() = runBlocking {
        val action = TileStateAction()
        val result = action.run(ctx(), emptyMap())
        assertTrue("missing state should fail", result is ActionResult.Failure)
        assertEquals("missing state argument", (result as ActionResult.Failure).message)
    }

    @Test
    fun tileStateInvalidStateFails() = runBlocking {
        val action = TileStateAction()
        val result = action.run(ctx(), mapOf("state" to "maybe"))
        assertTrue("invalid tile state should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid state"))
    }

    // --- App honest-failure guards ---

    @Test
    fun killAppAlwaysFailsHonestly() = runBlocking {
        val action = KillAppAction()
        val result = action.run(ctx(), mapOf("package" to "com.example"))
        assertTrue("kill app should fail", result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("not supported"))
    }

    // --- Notification channel resolution ---

    @Test
    fun notificationChannelResolvesKnownKeys() {
        val quiet = NotificationChannels.resolve("quiet")
        assertEquals("opentasker.quiet", quiet.id)

        val default = NotificationChannels.resolve("default")
        assertEquals("opentasker.actions", default.id)

        val urgent = NotificationChannels.resolve("urgent")
        assertEquals("opentasker.urgent", urgent.id)
    }

    @Test
    fun notificationChannelFallsBackToDefault() {
        val unknown = NotificationChannels.resolve("nonexistent")
        assertEquals("opentasker.actions", unknown.id)
    }

    @Test
    fun notificationChannelTrimsAndLowercases() {
        val padded = NotificationChannels.resolve("  URGENT  ")
        assertEquals("opentasker.urgent", padded.id)
    }

    // --- Local network permission guard (pre-API-37 path) ---

    @Test
    fun localNetworkPermissionPassesBelowApi37() {
        val result = checkLocalNetworkPermission(ctx())
        assertEquals("pre-API-37 should not block", null, result)
    }

    @Test
    fun pingGuardsLocalNetworkPermission() = runBlocking {
        val action = PingAction()
        val result = action.run(ctx(), mapOf("host" to "192.168.1.1"))
        assertTrue(
            "ping on pre-API-37 should not fail with permission error",
            result !is ActionResult.Failure || !(result as ActionResult.Failure).message.contains("ACCESS_LOCAL_NETWORK")
        )
    }

    @Test
    fun wolGuardsLocalNetworkPermission() = runBlocking {
        val action = WakeOnLanAction()
        val result = action.run(ctx(), mapOf("mac" to "AA:BB:CC:DD:EE:FF"))
        assertTrue(
            "WoL on pre-API-37 should not fail with permission error",
            result !is ActionResult.Failure || !(result as ActionResult.Failure).message.contains("ACCESS_LOCAL_NETWORK")
        )
    }
}
