package com.opentasker.core.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLoggerTest {
    @After
    fun tearDown() {
        AppLogger.clearForTest()
    }

    @Test
    fun processLogRingIsBoundedAndOrdered() {
        AppLogger.clearForTest()
        repeat(AppLogger.MAX_BUFFERED_ENTRIES + 5) { index ->
            AppLogger.info("test", "message-$index")
        }

        val snapshot = AppLogger.snapshot()

        assertEquals(AppLogger.MAX_BUFFERED_ENTRIES, snapshot.size)
        assertEquals("message-5", snapshot.first().message)
        assertEquals("message-${AppLogger.MAX_BUFFERED_ENTRIES + 4}", snapshot.last().message)
    }

    @Test
    fun minimumLevelAlsoFiltersDiagnosticRing() {
        AppLogger.clearForTest()
        AppLogger.setMinimumLevel(AppLogger.Level.WARN)

        AppLogger.info("test", "hidden")
        AppLogger.warn("test", "visible")

        assertEquals(listOf("visible"), AppLogger.snapshot().map { it.message })
    }
}
