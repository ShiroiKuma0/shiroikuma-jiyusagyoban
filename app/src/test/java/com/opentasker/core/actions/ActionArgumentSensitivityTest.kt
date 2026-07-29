package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionArgumentSensitivityTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun httpCredentialFieldsAreMaskedInTheSummary() {
        val summary = ActionArgumentSensitivity.summarize(
            actionType = "http.request",
            args = linkedMapOf(
                "url" to "https://example.test/api",
                "authorization" to "Bearer super-secret-token",
                "headers" to "X-Api-Key: abc123",
                "body" to """{"pin":"4242"}""",
            ),
        )

        assertTrue(summary.contains("url=https://example.test/api"))
        assertFalse(summary.contains("super-secret-token"))
        assertFalse(summary.contains("abc123"))
        assertFalse(summary.contains("4242"))
        assertEquals(3, Regex(Regex.escape(ActionArgumentSensitivity.REDACTED)).findAll(summary).count())
    }

    @Test
    fun structuralFieldsStayReadableEvenWhenTheNameHeuristicWouldMaskThem() {
        assertFalse(ActionArgumentSensitivity.isSensitive("http.request", "headers_var"))
        assertFalse(ActionArgumentSensitivity.isSensitive("http.request", "body_file"))
        assertTrue(ActionArgumentSensitivity.isSensitiveArgumentName("headers_var"))
        assertTrue(ActionArgumentSensitivity.isSensitiveArgumentName("body_file"))

        val summary = ActionArgumentSensitivity.summarize(
            actionType = "http.request",
            args = linkedMapOf("headers_var" to "%resp_headers", "body_file" to "/sdcard/payload.json"),
        )
        assertEquals("body_file=/sdcard/payload.json, headers_var=%resp_headers", summary)
    }

    @Test
    fun unknownActionTypesAndUnknownKeysFailClosed() {
        assertTrue(ActionArgumentSensitivity.isSensitive(null, "api_key"))
        assertTrue(ActionArgumentSensitivity.isSensitive("not.a.registered.action", "password"))
        assertTrue(ActionArgumentSensitivity.isSensitive("http.request", "future_secret_field"))
        assertNull(ActionArgumentSensitivity.declaredSensitivity("http.request", "future_secret_field"))
        assertFalse(ActionArgumentSensitivity.isSensitive("not.a.registered.action", "package"))
    }

    @Test
    fun variableSetValueIsMaskedWhenTheVariableNameReadsAsASecret() {
        val secret = ActionArgumentSensitivity.summarize(
            actionType = "var.set",
            args = linkedMapOf("name" to "api_token", "value" to "s3cr3t"),
        )
        assertEquals("name=api_token, value=${ActionArgumentSensitivity.REDACTED}", secret)

        val ordinary = ActionArgumentSensitivity.summarize(
            actionType = "var.set",
            args = linkedMapOf("name" to "greeting", "value" to "hello"),
        )
        assertEquals("name=greeting, value=hello", ordinary)
    }

    @Test
    fun everyDeclaredSensitiveFieldMasksAndEveryOverrideIsIntentional() {
        val declared = ActionMetadataRegistry.all()
            .flatMap { metadata -> metadata.fields.map { metadata.id to it } }
            .filter { (_, field) -> field.sensitive != null }
        assertTrue("no action declares explicit field sensitivity", declared.isNotEmpty())

        declared.forEach { (actionId, field) ->
            assertEquals(
                "$actionId.${field.key} declaration must drive isSensitive",
                field.sensitive,
                ActionArgumentSensitivity.isSensitive(actionId, field.key),
            )
            if (field.sensitive == false) {
                assertTrue(
                    "$actionId.${field.key} overrides the heuristic without matching it; drop the override",
                    ActionArgumentSensitivity.isSensitiveArgumentName(field.key),
                )
            }
        }
    }

    @Test
    fun credentialBearingActionsDeclareTheirSensitiveFields() {
        val required = mapOf(
            "http.request" to setOf("authorization", "headers", "query", "body"),
            "http.post" to setOf("data"),
            "sms.send" to setOf("message"),
            "script.termux.run" to setOf("stdin"),
        )
        required.forEach { (actionId, keys) ->
            val metadata = ActionMetadataRegistry.get(actionId)
            requireNotNull(metadata) { "$actionId is not registered" }
            keys.forEach { key ->
                assertEquals(
                    "$actionId.$key must be declared sensitive",
                    true,
                    metadata.fields.firstOrNull { it.key == key }?.sensitive,
                )
            }
        }
    }

    @Test
    fun summaryIsDeterministicAndBounded() {
        val args = linkedMapOf(
            "zulu" to "1",
            "alpha" to "2",
            "mike" to "3",
            "bravo" to "4",
            "kilo" to "5",
        )
        assertEquals(
            "alpha=2, bravo=4, kilo=5, mike=3, +1 more",
            ActionArgumentSensitivity.summarize("app.launch", args),
        )
        assertEquals("", ActionArgumentSensitivity.summarize("app.launch", emptyMap()))
    }

    @Test
    fun longValuesAreCollapsedAndTruncated() {
        val value = "line one\n\tline two " + "x".repeat(200)
        val masked = ActionArgumentSensitivity.maskValue("app.launch", "package", value, maxLength = 20)
        assertEquals("line one line two xx...", masked)
    }
}
