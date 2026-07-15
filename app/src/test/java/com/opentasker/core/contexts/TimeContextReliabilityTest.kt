package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeContextReliabilityTest {
    @Test
    fun alarmWakeEventReevaluatesWindowAtPostDozeWallClockTime() {
        val wakeTime = Instant.parse("2026-07-15T13:47:00Z").toEpochMilli()
        val event = timeContextEventAt(wakeTime, TimeZone.getTimeZone("UTC"))
        val window = ContextSpec(
            type = ContextType.TIME,
            config = mapOf("start" to "13:45", "end" to "14:00"),
        )

        assertEquals("13:47", event.metadata["time"])
        assertEquals("WED", event.metadata["day"])
        assertTrue(ContextMatchEvaluator.matches(window, event))
    }

    @Test
    fun inProcessFallbackSleepsToTheNextMinuteBoundary() {
        assertEquals(60_000L, millisUntilNextMinute(120_000L))
        assertEquals(58_766L, millisUntilNextMinute(61_234L))
    }
}
