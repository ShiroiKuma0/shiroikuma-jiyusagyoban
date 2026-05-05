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
    fun dayContextMatchesConfiguredDayTokens() {
        val spec = ContextSpec(ContextType.DAY, config = mapOf("days" to "MON,WED,FRI"))

        assertTrue(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "wed"))))
        assertFalse(ContextMatchEvaluator.matches(spec, ContextEvent("time", true, mapOf("day" to "SUN"))))
    }

    @Test
    fun stateContextAppliesConfiguredKeyValueAndNumericPredicates() {
        val keyValue = ContextSpec(ContextType.STATE, config = mapOf("key" to "charging", "value" to "true"))
        val predicate = ContextSpec(ContextType.STATE, config = mapOf("predicate" to "battery_level>=80"))
        val event = ContextEvent(
            "state",
            true,
            metadata = mapOf("charging" to "true", "battery_level" to "85"),
        )

        assertTrue(ContextMatchEvaluator.matches(keyValue, event))
        assertTrue(ContextMatchEvaluator.matches(predicate, event))
        assertFalse(ContextMatchEvaluator.matches(predicate, event.copy(metadata = mapOf("battery_level" to "20"))))
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
                ContextEvent("event", true, mapOf("event" to "sms_received")),
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
}
