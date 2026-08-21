package com.opentasker.core.contexts

import com.opentasker.core.engine.PulseEventContinuity
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunWindowPulseIdentityTest {
    private val sunriseSpec = ContextSpec(
        ContextType.EVENT,
        mapOf(
            "event" to "sunrise",
            "latitude" to "40.7128",
            "longitude" to "-74.0060",
            "windowMinutes" to "5",
        ),
    )

    private fun tick(time: String): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to "sun_tick",
            "date" to "2026-06-21",
            "time" to time,
            "zone" to "America/New_York",
        ),
    )

    @Test
    fun sunWindowTicksShareOneOccurrenceId() {
        val matchingTimes = matchingTimes(sunriseSpec)
        assertTrue("expected a multi-minute sunrise window", matchingTimes.size >= 2)

        val first = ContextMatchEvaluator.withStablePulseIdentity(sunriseSpec, tick(matchingTimes.first()))
        val second = ContextMatchEvaluator.withStablePulseIdentity(sunriseSpec, tick(matchingTimes[1]))
        val outside = ContextMatchEvaluator.withStablePulseIdentity(sunriseSpec, tick("12:00"))

        assertEquals(first.metadata["eventId"], second.metadata["eventId"])
        assertTrue(first.metadata.getValue("eventId").startsWith("sun:sunrise:2026-06-21:"))
        assertNull(outside.metadata["eventId"])
    }

    @Test
    fun exactMinuteSunTicksKeepDistinctOccurrenceIds() {
        val exact = sunriseSpec.copy(config = sunriseSpec.config + ("windowMinutes" to "1"))
        val matchingTimes = matchingTimes(exact)
        assertEquals(1, matchingTimes.size)

        val matching = ContextMatchEvaluator.withStablePulseIdentity(exact, tick(matchingTimes.single()))
        val laterClock = laterMinute(matchingTimes.single())
        val later = ContextMatchEvaluator.withStablePulseIdentity(exact, tick(laterClock))

        assertTrue(matching.metadata["eventId"].orEmpty().isNotBlank())
        assertNull(later.metadata["eventId"])
    }

    @Test
    fun sunWindowReplayCollapsesAtThePulseBoundary() {
        val matchingTimes = matchingTimes(sunriseSpec)
        val continuity = PulseEventContinuity()
        val firstTick = ContextMatchEvaluator.withStablePulseIdentity(sunriseSpec, tick(matchingTimes.first()))
        val laterTick = ContextMatchEvaluator.withStablePulseIdentity(sunriseSpec, tick(matchingTimes.last()))

        val first = continuity.observe(contextIndex = 0, firstTick)
        val replay = continuity.observe(contextIndex = 0, laterTick)

        assertFalse(first.duplicate)
        assertTrue(replay.duplicate)
        assertEquals(first.sequence, replay.sequence)
    }

    private fun matchingTimes(spec: ContextSpec): List<String> =
        (0 until 24 * 60).mapNotNull { minute ->
            val time = "%02d:%02d".format(minute / 60, minute % 60)
            time.takeIf { ContextMatchEvaluator.matches(spec, tick(it)) }
        }

    private fun laterMinute(time: String): String {
        val parts = time.split(":")
        val minute = parts[0].toInt() * 60 + parts[1].toInt() + 1
        val wrapped = minute % (24 * 60)
        return "%02d:%02d".format(wrapped / 60, wrapped % 60)
    }

    @Test
    fun calendarEventIdsAreLeftAlone() {
        val spec = ContextSpec(ContextType.EVENT, mapOf("event" to "calendar"))
        val event = ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf("event" to "calendar", "eventId" to "cal:1"),
        )
        assertEquals("cal:1", ContextMatchEvaluator.withStablePulseIdentity(spec, event).metadata["eventId"])
    }

    @Test
    fun differentSunWindowsStayDistinct() {
        val sunset = sunriseSpec.copy(config = sunriseSpec.config + ("event" to "sunset"))
        val sunriseId = ContextMatchEvaluator.withStablePulseIdentity(sunriseSpec, tick("05:27")).metadata["eventId"]
        val sunsetId = ContextMatchEvaluator.withStablePulseIdentity(sunset, tick("05:27")).metadata["eventId"]
        assertNotEquals(sunriseId, sunsetId)
    }

    @Test
    fun aWindowThatWrapsMidnightKeepsOneOccurrenceId() {
        val wrapping = (0..180 step 15).map { offset ->
            ContextSpec(
                ContextType.EVENT,
                mapOf(
                    "event" to "sunset",
                    "latitude" to "40.7128",
                    "longitude" to "-74.0060",
                    "offsetMinutes" to offset.toString(),
                    "windowMinutes" to "180",
                ),
            )
        }.first { spec ->
            val times = matchingTimes(spec)
            times.any { it < "01:00" } && times.any { it >= "23:00" }
        }
        val matching = matchingTimes(wrapping)
        val evening = matching.last { it >= "23:00" }
        val morning = matching.first { it < "01:00" }
        val beforeMidnight = ContextMatchEvaluator.withStablePulseIdentity(wrapping, tick(evening))
        val afterMidnight = ContextMatchEvaluator.withStablePulseIdentity(
            wrapping,
            tick(morning).copy(metadata = tick(morning).metadata + ("date" to "2026-06-22")),
        )
        assertEquals(beforeMidnight.metadata["eventId"], afterMidnight.metadata["eventId"])
        assertTrue(beforeMidnight.metadata.getValue("eventId").contains("2026-06-21"))
    }
}
