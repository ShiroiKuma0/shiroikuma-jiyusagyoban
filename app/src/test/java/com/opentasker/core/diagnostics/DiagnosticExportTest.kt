package com.opentasker.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportTest {
    @Test
    fun redactSensitiveRemovesPasswordValues() {
        val input = "password=secret123 other=visible"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("secret123"))
        assertTrue(result.contains("password=[REDACTED]"))
        assertTrue(result.contains("visible"))
    }

    @Test
    fun redactSensitiveRemovesTokenValues() {
        val input = "token: abc-xyz-123"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("abc-xyz-123"))
        assertTrue(result.contains("token=[REDACTED]"))
    }

    @Test
    fun redactSensitiveRemovesCardNumbers() {
        val input = "Card is 4111 1111 1111 1111 for purchase"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("4111"))
        assertTrue(result.contains("[REDACTED-CARD]"))
    }

    @Test
    fun redactSensitivePreservesNonSensitiveText() {
        val input = "Task completed successfully in 250ms"
        assertEquals(input, DiagnosticExport.redactSensitive(input))
    }

    @Test
    fun redactSensitiveCaseInsensitive() {
        val input = "SECRET=mysecret AUTH=mytoken"
        val result = DiagnosticExport.redactSensitive(input)
        assertFalse(result.contains("mysecret"))
        assertFalse(result.contains("mytoken"))
    }
}
