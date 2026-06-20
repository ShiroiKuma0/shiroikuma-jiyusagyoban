package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDenialTest {

    private fun ctx() = ActionContext(ContextWrapper(null), VariableStore())

    @Test
    fun httpGetFailsWithMissingUrl() = runBlocking {
        val result = HttpGetAction().run(ctx(), emptyMap())
        assertTrue("HTTP GET should fail without url", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing url",
            (result as ActionResult.Failure).message.contains("url"),
        )
    }

    @Test
    fun httpPostFailsWithMissingUrl() = runBlocking {
        val result = HttpPostAction().run(ctx(), emptyMap())
        assertTrue("HTTP POST should fail without url", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing url",
            (result as ActionResult.Failure).message.contains("url"),
        )
    }

    @Test
    fun pingFailsWithMissingHost() = runBlocking {
        val result = PingAction().run(ctx(), emptyMap())
        assertTrue("ping should fail without host", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing host",
            (result as ActionResult.Failure).message.contains("host"),
        )
    }

    @Test
    fun downloadFailsWithMissingUrl() = runBlocking {
        val result = DownloadAction().run(ctx(), emptyMap())
        assertTrue("download should fail without url", result is ActionResult.Failure)
    }

    @Test
    fun downloadFailsWithMissingPath() = runBlocking {
        val result = DownloadAction().run(ctx(), mapOf("url" to "https://example.com/file"))
        assertTrue("download should fail without path", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing path",
            (result as ActionResult.Failure).message.contains("path"),
        )
    }

    @Test
    fun wolFailsWithMissingMac() = runBlocking {
        val result = WakeOnLanAction().run(ctx(), emptyMap())
        assertTrue("WoL should fail without mac", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing mac",
            (result as ActionResult.Failure).message.contains("mac"),
        )
    }

    @Test
    fun wolFailsWithInvalidMac() = runBlocking {
        val result = WakeOnLanAction().run(ctx(), mapOf("mac" to "not-a-mac"))
        assertTrue("WoL should fail with invalid mac", result is ActionResult.Failure)
        assertTrue(
            "message mentions invalid MAC",
            (result as ActionResult.Failure).message.contains("MAC"),
        )
    }

    @Test
    fun setVariableFailsWithMissingName() = runBlocking {
        val result = SetVariableAction().run(ctx(), emptyMap())
        assertTrue("var.set should fail without name", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing name",
            (result as ActionResult.Failure).message.contains("name"),
        )
    }

    @Test
    fun ttsFailsWithMissingText() = runBlocking {
        val result = SayAction().run(ctx(), emptyMap())
        assertTrue("TTS should fail without text", result is ActionResult.Failure)
    }

    @Test
    fun ttsFailsWithOversizedText() = runBlocking {
        val result = SayAction().run(ctx(), mapOf("text" to "x".repeat(4001)))
        assertTrue("TTS should fail with oversized text", result is ActionResult.Failure)
        assertTrue(
            "message mentions character limit",
            (result as ActionResult.Failure).message.contains("character limit"),
        )
    }

    @Test
    fun waitFailsWithMissingMillis() = runBlocking {
        val result = WaitAction().run(ctx(), emptyMap())
        assertTrue("wait should fail without millis", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing millis",
            (result as ActionResult.Failure).message.contains("millis"),
        )
    }

    @Test
    fun waitFailsWithNegativeDuration() = runBlocking {
        val result = WaitAction().run(ctx(), mapOf("millis" to "-100"))
        assertTrue("wait should fail with negative millis", result is ActionResult.Failure)
        assertTrue(
            "message mentions non-negative",
            (result as ActionResult.Failure).message.contains("non-negative"),
        )
    }

    @Test
    fun intentLaunchFailsWithMissingPackage() = runBlocking {
        val result = LaunchIntentAction().run(ctx(), emptyMap())
        assertTrue("intent launch should fail without package", result is ActionResult.Failure)
        assertTrue(
            "message mentions missing package",
            (result as ActionResult.Failure).message.contains("package"),
        )
    }
}
