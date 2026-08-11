package com.opentasker.core.actions

import com.opentasker.core.engine.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantWebhookProtocolTest {
    @Test
    fun parsesBoundedHttpsWebhookDefaults() {
        val config = HomeAssistantWebhookProtocol.parse(
            mapOf("url" to "https://hooks.example.test/api/webhook/secret"),
        ).getOrThrow()

        assertEquals("{}", config.payload)
        assertEquals(15, config.timeoutSeconds)
        assertEquals(2, config.maxRetries)
        assertEquals(500L, config.backoffMilliseconds)
        assertFalse(config.allowHttp)
    }

    @Test
    fun requiresExplicitPrivateHttpOptInAndObjectPayload() {
        assertTrue(HomeAssistantWebhookProtocol.parse(mapOf("url" to "http://homeassistant.local/hook")).isFailure)
        assertTrue(
            HomeAssistantWebhookProtocol.parse(
                mapOf("url" to "http://homeassistant.local/hook", "allow_http" to "true", "payload" to "[]"),
            ).isFailure,
        )
        assertTrue(
            HomeAssistantWebhookProtocol.parse(
                mapOf("url" to "http://homeassistant.local/hook", "allow_http" to "true", "payload" to "{\"event\":\"test\"}"),
            ).isSuccess,
        )
    }

    @Test
    fun buildsHomeAssistantNotificationCommandEnvelope() {
        val config = HomeAssistantWebhookProtocol.parse(
            mapOf(
                "url" to "https://hooks.example.test/ha",
                "message" to "command_broadcast_intent",
                "data" to "{\"intent_action\":\"com.opentasker.action.RUN_TASK\"}",
            ),
        ).getOrThrow()

        assertEquals(
            "{\"message\":\"command_broadcast_intent\",\"data\":{\"intent_action\":\"com.opentasker.action.RUN_TASK\"}}",
            config.payload,
        )
    }

    @Test
    fun rejectsUnknownHomeAssistantCommandAndAmbiguousPayloadFields() {
        assertTrue(
            HomeAssistantWebhookProtocol.parse(
                mapOf("url" to "https://hooks.example.test/ha", "message" to "command_not_real"),
            ).isFailure,
        )
        assertTrue(
            HomeAssistantWebhookProtocol.parse(
                mapOf(
                    "url" to "https://hooks.example.test/ha",
                    "payload" to "{}",
                    "message" to "command_update_sensors",
                ),
            ).isFailure,
        )
    }

    @Test
    fun transientFailuresRetryButPermanentResponsesDoNot() {
        assertTrue(HomeAssistantWebhookProtocol.isTransientFailure(ActionResult.Failure("HTTP 500")))
        assertTrue(HomeAssistantWebhookProtocol.isTransientFailure(ActionResult.Failure("HTTP 429")))
        assertTrue(HomeAssistantWebhookProtocol.isTransientFailure(ActionResult.Failure("connection reset")))
        assertFalse(HomeAssistantWebhookProtocol.isTransientFailure(ActionResult.Failure("HTTP 401")))
        assertFalse(HomeAssistantWebhookProtocol.isTransientFailure(ActionResult.Success))
    }

    @Test
    fun backoffIsExponentialAndCapped() {
        val config = HomeAssistantWebhookConfig(
            url = "https://hooks.example.test/hook",
            payload = "{}",
            timeoutSeconds = 15,
            maxRetries = 3,
            backoffMilliseconds = 500,
            allowHttp = false,
        )

        assertEquals(500L, HomeAssistantWebhookProtocol.retryDelayMilliseconds(config, 0))
        assertEquals(1_000L, HomeAssistantWebhookProtocol.retryDelayMilliseconds(config, 1))
        assertEquals(2_000L, HomeAssistantWebhookProtocol.retryDelayMilliseconds(config, 2))
        assertEquals(4_000L, HomeAssistantWebhookProtocol.retryDelayMilliseconds(config, 3))
    }

    @Test
    fun secretPathIsNeverReturnedForDisplay() {
        assertEquals(
            "https://hooks.example.test/${ActionArgumentSensitivity.REDACTED}",
            HomeAssistantWebhookProtocol.redactedUrl("https://hooks.example.test/api/webhook/secret"),
        )
        assertEquals(ActionArgumentSensitivity.REDACTED, HomeAssistantWebhookProtocol.redactedUrl("not a URL"))
    }
}
