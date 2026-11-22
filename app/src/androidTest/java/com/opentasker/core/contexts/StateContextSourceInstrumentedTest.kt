package com.opentasker.core.contexts

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.math.abs

/**
 * The state source against the phone's real managers. Protected broadcasts like a headset plug
 * can't be sent by a test, so the receiver the source registers is captured and handed real
 * Intents directly. Nothing on the phone changes: it only reads.
 */
@RunWith(AndroidJUnit4::class)
class StateContextSourceInstrumentedTest {
    private val target: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theFirstEventIsTheStateAndroidReportsNow() = runBlocking {
        val app = RecordingContext(target)
        val events = Channel<ContextEvent>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { StateContextSourceImpl().events(app, "battery_level").collect { events.send(it) } }
        val seed = withTimeout(10_000) { events.receive() }.metadata
        job.cancel()

        val display = target.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        assertEquals(if (display.state == Display.STATE_ON) "on" else "off", seed["screen"])
        assertEquals((!target.getSystemService(KeyguardManager::class.java).isKeyguardLocked).toString(), seed["unlocked"])
        assertEquals(target.getSystemService(PowerManager::class.java).isPowerSaveMode.toString(), seed["power_save"])
        val airplane = Settings.Global.getInt(target.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        assertEquals(airplane.toString(), seed["airplane"])

        val capacity = target.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val level = seed.getValue("battery_level").toInt()
        assertTrue("seeded $level%, BatteryManager says $capacity%", abs(level - capacity) <= 1)
        val sticky = target.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        assertEquals(plugged.toString(), seed["charging"])
    }

    @Test
    fun theReceiverTurnsEachBroadcastIntoStateAndIsUnregisteredOnClose() = runBlocking {
        val app = RecordingContext(target)
        val events = Channel<ContextEvent>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { StateContextSourceImpl().events(app, "battery_level").collect { events.send(it) } }
        // Every event carries the whole merged state, so the latest one is the state now.
        var state = withTimeout(10_000) { events.receive() }.metadata
        suspend fun awaitState(predicate: (Map<String, String>) -> Boolean) = withTimeout(10_000) {
            while (!predicate(state)) state = events.receive().metadata
        }
        val (receiver, filter, flags) = withTimeout(10_000) {
            while (app.registered.isEmpty()) delay(20)
            app.registered.single()
        }

        listOf(
            Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_HEADSET_PLUG, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_USER_PRESENT, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, Intent.ACTION_AIRPLANE_MODE_CHANGED,
        ).forEach { action -> assertTrue(action, filter.hasAction(action)) }
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("other apps must not reach this receiver", flags and Context.RECEIVER_NOT_EXPORTED != 0)
        }

        // Each step sends the opposite of what the state holds, so it has to produce a new event.
        val headphones = state["headphones"] == "true"
        receiver.onReceive(app, Intent(Intent.ACTION_HEADSET_PLUG).putExtra("state", if (headphones) 0 else 1))
        awaitState { it["headphones"] == (!headphones).toString() }

        val screenOn = state["screen"] == "on"
        receiver.onReceive(app, Intent(if (screenOn) Intent.ACTION_SCREEN_OFF else Intent.ACTION_SCREEN_ON))
        awaitState { it["screen"] == if (screenOn) "off" else "on" }
        receiver.onReceive(app, Intent(if (screenOn) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        awaitState { it["screen"] == if (screenOn) "on" else "off" }

        // 3 out of 200 is 1%, which a phone running this test won't be at, on USB power.
        receiver.onReceive(
            app,
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 3)
                .putExtra(BatteryManager.EXTRA_SCALE, 200)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB),
        )
        awaitState { it["battery_level"] == "1" && it["charging"] == "true" }

        job.cancel()
        job.join()
        assertEquals("closing the flow must unregister what it registered", listOf(receiver), app.unregistered.toList())
    }

    /** Passes everything to the real context, and remembers the receivers it registers and drops. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val registered: MutableList<Triple<BroadcastReceiver, IntentFilter, Int>> = Collections.synchronizedList(mutableListOf())
        val unregistered: MutableList<BroadcastReceiver> = Collections.synchronizedList(mutableListOf())

        private fun record(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int) {
            if (receiver != null) registered += Triple(receiver, filter, flags)
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            record(receiver, filter, 0)
            return super.registerReceiver(receiver, filter)
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
            record(receiver, filter, flags)
            return super.registerReceiver(receiver, filter, flags)
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter,
            broadcastPermission: String?,
            scheduler: Handler?,
        ): Intent? {
            record(receiver, filter, 0)
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter,
            broadcastPermission: String?,
            scheduler: Handler?,
            flags: Int,
        ): Intent? {
            record(receiver, filter, flags)
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            unregistered += receiver
            super.unregisterReceiver(receiver)
        }
    }
}
