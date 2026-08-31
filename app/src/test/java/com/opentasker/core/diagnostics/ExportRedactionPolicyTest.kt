package com.opentasker.core.diagnostics

import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExportRedactionPolicyTest {
    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun actionFieldsAndKnownSecretsAreRedactedAcrossStructuredValues() {
        val result = ExportRedactionPolicy.sanitizeActionArguments(
            actionType = "http.request",
            args = linkedMapOf(
                "url" to "https://user:pass@example.test/api?token=sentinel",
                "headers" to "Authorization: Bearer sentinel",
                "body_file" to "/sdcard/sentinel.json",
                "response_var" to "result",
            ),
            context = ExportRedactionPolicy.Context(secretValues = setOf("sentinel")),
        )

        assertEquals(ExportRedactionPolicy.REDACTED, result.args.getValue("headers"))
        assertEquals("result", result.args.getValue("response_var"))
        assertTrue(result.redactedFields.containsAll(setOf("url", "headers", "body_file")))
        assertFalse(result.args.values.any { it.contains("sentinel") })
        assertFalse(result.args.getValue("url").contains("pass@"))
        assertFalse(result.args.getValue("url").contains("?token="))
    }

    @Test
    fun secretDerivedTemplateReferencesFailClosedEvenInOrdinaryFields() {
        val result = ExportRedactionPolicy.sanitizeActionArguments(
            actionType = "log",
            args = mapOf("message" to "Token is {{ global.API_TOKEN }}"),
            context = ExportRedactionPolicy.Context(secretNames = setOf("API_TOKEN")),
        )

        assertEquals(ExportRedactionPolicy.REDACTED, result.args.getValue("message"))
        assertEquals(setOf("message"), result.redactedFields)
    }

    @Test
    fun freeTextRedactionRemovesCredentialsAndUrlQueries() {
        val result = ExportRedactionPolicy.redactText(
            "failure password=sentinel at https://example.test/path?api_key=sentinel",
            secretValues = setOf("sentinel"),
        )

        assertFalse(result.contains("sentinel"))
        assertFalse(result.contains("api_key=sentinel"))
        assertTrue(result.contains("password=[REDACTED]"))
        assertTrue(result.contains("https://example.test/path?[REDACTED]"))
    }

    // Upstream also redacts secret-LOOKING action arguments when encoding a bundle (an arg named
    // API_TOKEN has its value replaced). The fork does not: its bundles are the workspace mirror and
    // have to round-trip exactly, so an export that silently replaced argument values with REDACTED
    // would come back as a broken workspace on the next import. Secret VARIABLES are still refused
    // outright by encode(), which is the guard that actually protects a credential.
}
