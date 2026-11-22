package com.opentasker.core.plugins.locale

import android.content.BroadcastReceiver
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginRequestQueryEventsTest {
    @Test
    fun buildsSanitizedRequestQueryEvent() {
        val event = LocalePluginRequestQueryEvents.buildEvent(
            activityClassName = " com.example.plugin.ConditionEditor ",
            bundleValues = mapOf("zeta" to "last", "alpha" to "first"),
        )

        requireNotNull(event)
        assertEquals("event", event.type)
        assertTrue(event.matched)
        assertEquals("locale_request_query", event.metadata["event"])
        assertEquals("com.example.plugin.ConditionEditor", event.metadata["activityClass"])
        assertEquals("""{"alpha":"first","zeta":"last"}""", event.metadata["bundleJson"])
    }

    @Test
    fun rejectsBlankOrInvalidActivityClassNames() {
        assertNull(LocalePluginRequestQueryEvents.buildEvent(""))
        assertNull(LocalePluginRequestQueryEvents.buildEvent("not a class"))
    }

    @Test
    fun everyListenerSharesOneExportedReceiver() = runBlocking {
        val registered = AtomicInteger()
        val unregistered = AtomicInteger()
        val app = object : ContextWrapper(null) {
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? = null.also { registered.incrementAndGet() }
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? = null.also { registered.incrementAndGet() }
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?): Intent? =
                null.also { registered.incrementAndGet() }
            override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? =
                null.also { registered.incrementAndGet() }
            override fun unregisterReceiver(receiver: BroadcastReceiver?) {
                unregistered.incrementAndGet()
            }
        }
        // One event matcher per profile used to mean one exported receiver each.
        val listeners = List(3) { launch(Dispatchers.Default) { LocalePluginRequestQueryEvents.events(app).collect {} } }
        withTimeout(5_000) { while (registered.get() == 0) delay(10) }
        delay(300)
        assertEquals(1, registered.get())

        listeners.forEach { it.cancel() }
        // The receiver goes once the last listener has been gone a few seconds.
        withTimeout(10_000) { while (unregistered.get() == 0) delay(50) }
        assertEquals(1, unregistered.get())
    }

    @Test
    fun onlyAMatcherForThisEventOrForAnyEventWantsTheReceiver() {
        assertTrue(LocalePluginRequestQueryEvents.isRequested(null))
        assertTrue(LocalePluginRequestQueryEvents.isRequested(" "))
        assertTrue(LocalePluginRequestQueryEvents.isRequested(" Locale_Request_Query "))
        assertFalse(LocalePluginRequestQueryEvents.isRequested("nfc"))
    }

    @Test
    fun normalizesInnerClassNames() {
        val normalized = LocalePluginRequestQueryEvents.normalizeActivityClassName(
            "com.example.plugin.ConditionEditor\$Nested",
        )

        assertEquals("com.example.plugin.ConditionEditor\$Nested", normalized)
    }
}
