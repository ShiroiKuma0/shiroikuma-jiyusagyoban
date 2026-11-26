package com.sysadmindoc.ruledeck.core.contexts

import com.opentasker.core.contexts.CompanionPresenceService
import com.opentasker.core.contexts.publishCompanionPresenceEvent
import android.companion.DevicePresenceEvent
import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.core.model.ContextType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The small sources that hand a one-shot fact to the matcher: the pulse helper every bus uses,
 * shake, the foreground-app source and the companion presence callbacks.
 */
class ContextEventSourcesTest {
    @Test
    fun aPulseWithRoomIsDeliveredAndOneDroppedOnAFullBufferIsLogged() = runBlocking {
        val bus = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        // A subscriber that takes nothing, like a matcher stuck on a slow profile.
        val stuck = launch(start = CoroutineStart.UNDISPATCHED) { bus.collect { awaitCancellation() } }
        val source = "pulse-test-${System.nanoTime()}"

        assertTrue(bus.tryEmitPulse(source, 1))
        assertFalse("a delivered pulse logs nothing", AppLogger.snapshot().any { source in it.message })

        assertFalse("the buffer is full, so this one is lost", bus.tryEmitPulse(source, 2))
        val warning = AppLogger.snapshot().single { source in it.message }
        assertEquals(AppLogger.Level.WARN, warning.level)
        assertTrue(warning.message, warning.message.contains("The trigger did not run"))
        stuck.cancel()
    }

    @Test
    fun aShakeIsPublishedAsAShakeEventWithItsMagnitude() = runBlocking {
        val received = async(start = CoroutineStart.UNDISPATCHED) { ShakeContextEvents.events.first() }
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            ShakeContextEvents.publish(2.345f)
        } finally {
            Locale.setDefault(previous)
        }

        val event = withTimeout(5_000) { received.await() }
        assertEquals(ContextEvent("event", true, mapOf("event" to "shake", "magnitude" to "2.3")), event)
    }

    @Test
    fun theForegroundAppSourceServesTheAppBusUnderTheKeyTheMatcherAsksFor() {
        val source = ApplicationContextSourceImpl()

        assertEquals(ContextMatchEvaluator.sourceKey(ContextType.APPLICATION), source.type)
        assertSame(ApplicationContextEvents.events, source.events(ContextWrapper(null)))
    }

    @Suppress("DEPRECATION")
    @Test
    fun aCompanionThatComesAndGoesIsPublishedByItsAddress() = runBlocking {
        val service = CompanionPresenceService()
        val events = async(start = CoroutineStart.UNDISPATCHED) { CompanionContextEvents.events.take(2).toList() }

        service.onDeviceAppeared("AA:BB:CC:DD:EE:FF")
        service.onDeviceDisappeared("AA:BB:CC:DD:EE:FF")

        val (appeared, left) = withTimeout(5_000) { events.await() }
        assertEquals(CompanionContextEvents.STATE_PRESENT, appeared.metadata["state"])
        assertEquals(CompanionContextEvents.STATE_ABSENT, left.metadata["state"])
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"), listOf(appeared, left).map { it.metadata["associationId"] })
    }

    @Test
    fun androidSixteensPresenceEventsBecomePresentOrAbsentAndTheRestAreIgnored() = runBlocking {
        val events = async(start = CoroutineStart.UNDISPATCHED) { CompanionContextEvents.events.take(3).toList() }

        publishCompanionPresenceEvent(DevicePresenceEvent.EVENT_BT_CONNECTED, 7)
        publishCompanionPresenceEvent(UNRELATED_EVENT, 8)
        publishCompanionPresenceEvent(DevicePresenceEvent.EVENT_BLE_DISAPPEARED, -1)
        publishCompanionPresenceEvent(DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED, 9)

        val published = withTimeout(5_000) { events.await() }.map { it.metadata["state"] to it.metadata["associationId"] }
        assertEquals(
            listOf(
                CompanionContextEvents.STATE_PRESENT to "7",
                CompanionContextEvents.STATE_ABSENT to CompanionContextEvents.UNKNOWN_ASSOCIATION,
                CompanionContextEvents.STATE_PRESENT to "9",
            ),
            published,
        )
    }

    private companion object {
        /** Not one of the appeared or disappeared codes. */
        const val UNRELATED_EVENT = 9_999
    }
}
