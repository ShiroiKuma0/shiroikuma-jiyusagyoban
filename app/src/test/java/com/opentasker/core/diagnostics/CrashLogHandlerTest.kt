package com.opentasker.core.diagnostics

import java.nio.file.Files
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogHandlerTest {
    @Test
    fun crashLogsAreNewestFirstBoundedAndRedacted() {
        val directory = Files.createTempDirectory("opentasker-crash-test")
        try {
            repeat(7) { index ->
                val file = directory.resolve("crash-20260715-120${index}0.txt")
                file.writeText("failure token=secret-$index")
                file.toFile().setLastModified(1_000L + index)
            }

            val records = CrashLogHandler.listCrashLogFiles(directory.toFile())

            assertEquals(5, records.size)
            assertTrue(records.first().fileName.contains("12060"))
            assertFalse(records.first().redactedContent.contains("secret-6"))
            assertTrue(records.first().redactedContent.contains("[REDACTED]"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
