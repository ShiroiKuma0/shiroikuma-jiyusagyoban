package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Wi-Fi Connected network name and a Notification title or text can match the whole value as a
 * Matches pattern, so those filters go through the same matcher as a `~` condition when they say
 * `~` or carry [ContextMatchEvaluator.MATCHES_PATTERNS].
 */
class FilterMatchesPatternTest {

    @Test
    fun aWifiNamePatternMatchesTheWholeName() {
        val homeOrWork = wifi("home/work*")

        assertTrue(homeOrWork.matches(connectedTo("Home")))
        assertTrue(homeOrWork.matches(connectedTo("WORK 5G")))
        assertFalse(homeOrWork.matches(connectedTo("My Home")))
    }

    @Test
    fun aCapitalLetterMakesTheNameCaseSensitive() {
        assertTrue(wifi("Home").matches(connectedTo("Home")))
        assertFalse(wifi("Home").matches(connectedTo("home")))
    }

    @Test
    fun aWifiNamePatternNeedsAConnection() {
        assertFalse(wifi("*").matches(disconnected))
        assertFalse(wifi("!home").matches(disconnected))
        assertTrue(wifi("!home").matches(connectedTo("Cafe")))
        assertFalse(wifi("!home").matches(connectedTo("HOME")))
    }

    @Test
    fun aWifiNamePatternCanHoldAnEqualsSignOrAWordForOff() {
        assertTrue(wifi("a=b").matches(connectedTo("a=b")))
        assertTrue(wifi("Off").matches(connectedTo("Off")))
        assertFalse(wifi("Off").matches(disconnected))
    }

    @Test
    fun aWifiNamePatternReadsTheName() {
        assertTrue(stateSpecReadsWifiSsid(wifi("*")))
        assertTrue(stateSpecReadsWifiSsid(wifi("Off")))
    }

    @Test
    fun aNotificationTitleAndTextMatchAsMatchesPatterns() {
        val door = notification("title" to "Door*", "body" to "*open*")

        assertTrue(door.matches(posted("Doorbell", "Front door is open now")))
        assertFalse(door.matches(posted("Front Door", "Front door is open now")))
        assertFalse(door.matches(posted("Doorbell", "closed")))
    }

    @Test
    fun aNotificationPatternCanAlternateOrTurnAround() {
        val notMail = notification("title" to "!mail/inbox")

        assertTrue(notMail.matches(posted("Reminder", "")))
        assertFalse(notMail.matches(posted("MAIL", "")))
        assertTrue(notification("title" to "~Rfoo\\d").matches(posted("a foo1 b", "")))
    }

    @Test
    fun withoutTheFlagANotificationTitleStillMatchesAnyPart() {
        val contains = ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "title" to "door"))

        assertTrue(contains.matches(posted("Front Door", "")))
    }

    private fun wifi(pattern: String) = ContextSpec(ContextType.STATE, mapOf("key" to "wifi", "operator" to "~", "value" to pattern))

    private fun notification(vararg filters: Pair<String, String>) =
        ContextSpec(ContextType.EVENT, mapOf("event" to "notification", "patterns" to "tasker") + filters)

    private fun connectedTo(ssid: String) = ContextEvent("state", true, DeviceStateEvents.wifiPatch(ssid, connected = true))

    private val disconnected = ContextEvent("state", true, DeviceStateEvents.wifiPatch("", connected = false))

    private fun posted(title: String, body: String) = NotificationContextEvents.buildEvent("com.a", title, body)

    private fun ContextSpec.matches(event: ContextEvent) = ContextMatchEvaluator.matches(this, event)
}
