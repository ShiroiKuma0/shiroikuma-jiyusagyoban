package com.opentasker.core.actions

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MqttPublishActionTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun successUsesTheInjectedTransportWithoutLoggingPayload() = runBlocking {
        val logs = mutableListOf<String>()
        val action = MqttPublishAction(MqttPublishTransport { config, _ ->
            assertEquals("opentasker/event", config.topic)
            assertEquals("secret", config.payload.toString(Charsets.UTF_8))
        })

        val result = action.run(
            ActionContext(context, VariableStore(), logger = logs::add),
            mapOf("host" to "broker.example", "topic" to "opentasker/event", "payload" to "secret"),
        )

        assertEquals(ActionResult.Success, result)
        assertTrue(logs.single().contains("broker.example"))
        assertTrue(logs.none { "secret" in it })
    }

    @Test
    fun cleartextPublicBrokerIsRejectedBeforeTransport() = runBlocking {
        var called = false
        val action = MqttPublishAction(MqttPublishTransport { _, _ -> called = true })
        val result = action.run(
            ActionContext(context, VariableStore()),
            mapOf("host" to "broker.example", "topic" to "opentasker/event", "tls" to "false"),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("private or local"))
        assertTrue(!called)
    }

    @Test
    fun transportTimeoutIsReturnedAsFailure() = runBlocking {
        val action = MqttPublishAction(MqttPublishTransport { _, _ -> throw SocketTimeoutException("timed out") })
        val result = action.run(
            ActionContext(context, VariableStore()),
            mapOf("host" to "broker.example", "topic" to "opentasker/event"),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("timed out"))
    }
}
