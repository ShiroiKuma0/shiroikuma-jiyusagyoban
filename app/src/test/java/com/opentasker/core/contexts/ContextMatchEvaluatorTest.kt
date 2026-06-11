package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMatchEvaluatorTest {
    @Test
    fun applicationContextUsesRegisteredAppSourceAndConfiguredPackage() {
        val spec = ContextSpec(
            type = ContextType.APPLICATION,
            config = mapOf("package" to "com.example.target"),
        )

        assertEquals("app", ContextMatchEvaluator.sourceKey(ContextType.APPLICATION))
        assertTrue(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("app", matched = true, metadata = mapOf("foreground" to "com.example.target")),
            )
        )
        assertFalse(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("app", matched = true, metadata = mapOf("foreground" to "com.example.other")),
            )
        )
    }

    @Test
    fun timeContextHonorsConfiguredWindowIncludingOvernightRanges() {
        val daytime = ContextSpec(ContextType.TIME, config = mapOf("start" to "09:00", "end" to "17:30"))
        val overnight = ContextSpec(ContextType.TIME, config = mapOf("start" to "22:00", "end" to "06:00"))

        assertTrue(ContextMatchEvaluator.matches(daytime, ContextEvent("time", true, mapOf("time" to "12:15"))))
        assertFalse(ContextMatchEvaluator.matches(daytime, ContextEvent("time", true, mapOf("time" to "18:00"))))
        assertTrue(ContextMatchEvaluator.matches(overnight, ContextEvent("time", true, mapOf("time" to "23:30"))))
        assertTrue(ContextMatchEvaluator.matches(overnight, ContextEvent("time", true, mapOf("time" to "05:30"))))
        assertFalse(ContextMatchEvaluator.matches(overnight, ContextEvent("time", true, mapOf("time" to "12:00"))))
    }

    @Test
    fun locationContextUsesFossGeofenceAccuracyAndDwell() {
        val spec = ContextSpec(
            type = ContextType.LOCATION,
            config = mapOf(
                "latitude" to "40.7580",
                "longitude" to "-73.9855",
                "radiusMeters" to "150",
                "maxAccuracyMeters" to "50",
                "dwellSeconds" to "300",
            ),
        )

        val matchingEvent = ContextEvent(
            "location",
            true,
            mapOf(
                "latitude" to "40.7581",
                "longitude" to "-73.9856",
                "accuracyMeters" to "25",
                "observedAtEpochMs" to "600000",
                "insideSinceEpochMs" to "0",
            ),
        )
        val inaccurateEvent = matchingEvent.copy(metadata = matchingEvent.metadata + ("accuracyMeters" to "75"))
        val noDwellEvent = matchingEvent.copy(metadata = matchingEvent.metadata + ("insideSinceEpochMs" to "400000"))

        assertTrue(ContextMatchEvaluator.matches(spec, matchingEvent))
        assertFalse(ContextMatchEvaluator.matches(spec, inaccurateEvent))
        assertFalse(ContextMatchEvaluator.matches(spec, noDwellEvent))
    }

    @Test
    fun dayContextMatchesConfiguredDayTokens() {
        val spec = ContextSpec(ContextType.DAY, config = mapOf("days" to "MON-WED,FRI"))

        assertTrue(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "wed"))))
        assertTrue(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "1"))))
        assertFalse(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "SUN"))))
    }

    @Test
    fun stateContextAppliesConfiguredKeyValueAndNumericPredicates() {
        val keyValue = ContextSpec(ContextType.STATE, config = mapOf("key" to "charging", "value" to "true"))
        val predicate = ContextSpec(ContextType.STATE, config = mapOf("predicate" to "battery_level>=80"))
        val operatorValue = ContextSpec(
            ContextType.STATE,
            config = mapOf("key" to "battery", "operator" to "<=", "value" to "90"),
        )
        val event = ContextEvent(
            "state",
            true,
            metadata = mapOf("charging" to "true", "battery_level" to "85"),
        )

        assertTrue(ContextMatchEvaluator.matches(keyValue, event))
        assertTrue(ContextMatchEvaluator.matches(predicate, event))
        assertTrue(ContextMatchEvaluator.matches(operatorValue, event))
        assertFalse(ContextMatchEvaluator.matches(predicate, event.copy(metadata = mapOf("battery_level" to "20"))))
    }

    @Test
    fun stateContextMatchesMergedMultiFactStateAndDocumentedAliases() {
        val headphones = ContextSpec(ContextType.STATE, config = mapOf("key" to "headset", "value" to "connected"))
        val charging = ContextSpec(ContextType.STATE, config = mapOf("key" to "charging", "value" to "plugged"))
        val screen = ContextSpec(ContextType.STATE, config = mapOf("predicate" to "screen=on"))
        val event = ContextEvent(
            "state",
            true,
            metadata = mapOf(
                "headphones" to "true",
                "charging" to "true",
                "screen" to "on",
                "battery_level" to "35",
            ),
        )

        assertTrue(ContextMatchEvaluator.matches(headphones, event))
        assertTrue(ContextMatchEvaluator.matches(charging, event))
        assertTrue(ContextMatchEvaluator.matches(screen, event))
    }

    @Test
    fun stateNumericPredicatesFailClosedForMalformedThresholds() {
        val event = mapOf("battery_level" to "15")

        assertFalse(stateMatches("battery_level<not-a-number", event))
        assertFalse(stateMatches("battery_level>=not-a-number", event))
    }

    @Test
    fun eventContextMatchesTypeAndOptionalFilter() {
        val spec = ContextSpec(ContextType.EVENT, config = mapOf("event" to "boot_completed", "filter" to "boot"))

        assertTrue(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("event", true, mapOf("event" to "boot_completed")),
            )
        )
        assertFalse(
            ContextMatchEvaluator.matches(
                spec,
                ContextEvent("event", true, mapOf("event" to "notification")),
            )
        )
    }

    @Test
    fun notificationEventMatchesPackageTitleBodyAndRegexFilters() {
        val event = ContextEvent(
            "event",
            true,
            mapOf(
                "event" to "notification",
                "package" to "com.chat.example",
                "title" to "Build finished",
                "body" to "Debug APK is ready",
            ),
        )
        val containsSpec = ContextSpec(
            ContextType.EVENT,
            config = mapOf(
                "event" to "notification",
                "package" to "com.chat.example,com.mail.example",
                "title" to "build",
                "body" to "apk",
            ),
        )
        val regexSpec = ContextSpec(
            ContextType.EVENT,
            config = mapOf(
                "event" to "notification",
                "filter" to "debug\\s+apk",
                "regex" to "true",
            ),
        )
        val wrongPackage = containsSpec.copy(config = containsSpec.config + ("package" to "com.other"))

        assertTrue(ContextMatchEvaluator.matches(containsSpec, event))
        assertTrue(ContextMatchEvaluator.matches(regexSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongPackage, event))
    }

    @Test
    fun notificationRegexFiltersFailClosedWhenInvalidOrTooLarge() {
        val event = ContextEvent(
            "event",
            true,
            mapOf("event" to "notification", "title" to "Hello"),
        )

        assertFalse(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "filter" to "[", "regex" to "true")),
                event,
            )
        )
        assertFalse(
            ContextMatchEvaluator.matches(
                ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "filter" to "a".repeat(161), "regex" to "true")),
                event,
            )
        )
    }

    @Test
    fun nfcEventMatchesNormalizedTagIds() {
        val event = ContextEvent(
            "event",
            true,
            mapOf("event" to "nfc", "tagId" to "04AABBCC"),
        )
        val matchingSpec = ContextSpec(
            ContextType.EVENT,
            mapOf("event" to "nfc", "tagId" to "04:aa-bb cc, 01020304"),
        )
        val wrongTagSpec = matchingSpec.copy(config = matchingSpec.config + ("tagId" to "01020304"))

        assertTrue(ContextMatchEvaluator.matches(matchingSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongTagSpec, event))
    }

    @Test
    fun calendarEventMatchesCalendarStateAndBeforeWindowFilters() {
        val event = ContextEvent(
            "event",
            true,
            mapOf(
                "event" to "calendar",
                "state" to "upcoming",
                "calendar" to "Work",
                "minutesUntilStart" to "10",
            ),
        )
        val matchingSpec = ContextSpec(
            ContextType.EVENT,
            mapOf("event" to "calendar", "state" to "upcoming", "calendar" to "work", "beforeMinutes" to "15"),
        )
        val tooLateSpec = matchingSpec.copy(config = matchingSpec.config + ("beforeMinutes" to "5"))
        val wrongCalendarSpec = matchingSpec.copy(config = matchingSpec.config + ("calendar" to "Personal"))

        assertTrue(ContextMatchEvaluator.matches(matchingSpec, event))
        assertFalse(ContextMatchEvaluator.matches(tooLateSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongCalendarSpec, event))
    }

    @Test
    fun sunEventsMatchConfiguredOffsetWindow() {
        val event = ContextEvent(
            "event",
            true,
            mapOf(
                "event" to "sun_tick",
                "date" to "2026-06-21",
                "time" to "05:27",
                "zone" to "America/New_York",
            ),
        )
        val matchingSpec = ContextSpec(
            ContextType.EVENT,
            mapOf(
                "event" to "sunrise",
                "latitude" to "40.7128",
                "longitude" to "-74.0060",
                "windowMinutes" to "5",
            ),
        )
        val offsetSpec = matchingSpec.copy(config = matchingSpec.config + ("offsetMinutes" to "30"))
        val wrongEventSpec = matchingSpec.copy(config = matchingSpec.config + ("event" to "sunset"))

        assertTrue(ContextMatchEvaluator.matches(matchingSpec, event))
        assertFalse(ContextMatchEvaluator.matches(offsetSpec, event))
        assertFalse(ContextMatchEvaluator.matches(wrongEventSpec, event))
    }
}
