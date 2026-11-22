package com.opentasker.automation.receiver

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.scheduling.ExactAlarmSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeEventReceiverTest {
    @Test
    fun actionClassificationRecognizesBothTickSourcesAndPermissionChanges() {
        assertEquals(
            TimeEventAction.TIME_TICK,
            classifyTimeEventAction(TimeEventScheduler.ACTION_TIME_TICK),
        )
        assertEquals(TimeEventAction.TIME_TICK, classifyTimeEventAction(Intent.ACTION_TIME_TICK))
        assertEquals(
            TimeEventAction.EXACT_ALARM_PERMISSION_CHANGED,
            classifyTimeEventAction(ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION),
        )
        assertEquals(TimeEventAction.IGNORE, classifyTimeEventAction("test.unknown"))
        assertEquals(TimeEventAction.IGNORE, classifyTimeEventAction(null))
    }

    @Test
    fun receiverIgnoresIncompleteBroadcasts() {
        val receiver = TimeEventReceiver()
        val app = RecordingContext()

        receiver.onReceive(null, null)
        receiver.onReceive(null, Intent(TimeEventScheduler.ACTION_TIME_TICK))
        receiver.onReceive(app, null)
        receiver.onReceive(app, broadcast(null))
        receiver.onReceive(app, broadcast("test.unknown"))

        // The ledger and the scheduler both start from the application context, so either shows
        // up here. A service start can't: building its Intent throws on the JVM before it gets
        // as far as the context.
        assertEquals(
            "a broadcast with nothing to act on must not mark a tick delivered or reach the scheduler",
            emptyList<String>(),
            app.touched,
        )
        assertTrue(app.prefs.values.isEmpty())
    }

    /**
     * The control for the test above: a real tick does write the ledger, so the recorder sees it.
     * Past the ledger the tick needs AlarmManager, which this JVM classpath can't provide, so what
     * happens after that isn't asserted here.
     */
    @Test
    fun aTickMarksTheLedgerDeliveredWhereTheRecorderCanSeeIt() {
        val app = RecordingContext()

        runCatching { TimeEventReceiver().onReceive(app, broadcast(TimeEventScheduler.ACTION_TIME_TICK)) }

        assertTrue(app.touched.toString(), app.touched.any { it.startsWith("prefs:") })
        assertTrue("the delivery reached storage", app.prefs.values.isNotEmpty())
    }

    private fun broadcast(actionName: String?): Intent = object : Intent() {
        override fun getAction(): String? = actionName
    }

    /** Records the two ways the ledger and the scheduler reach the context. */
    private class RecordingContext : ContextWrapper(null) {
        val touched = mutableListOf<String>()
        val prefs = InMemoryPreferences()

        override fun getApplicationContext(): Context {
            touched += "applicationContext"
            return this
        }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            touched += "prefs:$name"
            return prefs
        }
    }

    /** Just enough SharedPreferences for the trigger ledger. */
    private class InMemoryPreferences : SharedPreferences {
        val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val staged = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearFirst = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                staged[requireNotNull(key)] = value
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                removed += requireNotNull(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFirst = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFirst) values.clear()
                removed.forEach { values.remove(it) }
                staged.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }

    /**
     * A refused background start must not book a retry.
     *
     * The recovery alarm is five seconds out on the same PendingIntent. Android refuses a
     * background foreground-service start for a condition that lasts, so scheduling a retry there
     * produced a wakeup every five seconds and burned the allow-while-idle quota the ordinary
     * minute tick needs. Matched by class name so nothing loads a class that does not exist below
     * API 31.
     */
    @Test
    fun onlyABackgroundStartRefusalSkipsTheRecoveryAlarm() {
        assertTrue(
            "the platform refusal must be recognised wherever it sits in the chain",
            namesBackgroundStartRefusal(
                listOf("java.lang.RuntimeException", REFUSAL, "java.io.IOException"),
            ),
        )
        assertFalse(
            "an ordinary failure must still book a recovery",
            namesBackgroundStartRefusal(listOf("java.lang.IllegalStateException")),
        )
        assertFalse(
            "a class merely named like it, in another package, is not the refusal",
            namesBackgroundStartRefusal(listOf("com.example.ForegroundServiceStartNotAllowedException")),
        )
    }

    @Test
    fun theCauseChainIsWalkedOutermostFirstAndCannotHang() {
        val chain = RuntimeException("outer", IllegalStateException("inner", java.io.IOException("root")))

        assertEquals(
            listOf(
                "java.lang.RuntimeException",
                "java.lang.IllegalStateException",
                "java.io.IOException",
            ),
            causeChainClassNames(chain),
        )

        val cyclic = RuntimeException("a")
        cyclic.initCause(RuntimeException("b", cyclic))
        assertTrue("a cyclic chain must terminate", causeChainClassNames(cyclic).size <= 8)
    }

    /**
     * Below API 31 the dedicated exception class does not exist, and the same refusal arrives as a
     * plain IllegalStateException. Matching only the class name left the retry storm live on
     * Android 8 to 11, which is most of the devices this app's minSdk exists for.
     */
    @Test
    fun theSameRefusalIsRecognisedFromItsMessageOnOlderAndroid() {
        assertTrue(
            isBackgroundStartRefusal(
                IllegalStateException(
                    "Not allowed to start service Intent { cmp=com.opentasker.app/.Service }: " +
                        "app is in background uid UidRecord{1234}",
                ),
            ),
        )
        assertTrue(readsAsBackgroundStartRefusal("Not allowed to start service Intent"))
        assertFalse(readsAsBackgroundStartRefusal(null))
        assertFalse(readsAsBackgroundStartRefusal("database is locked"))
        assertFalse(
            "an unrelated failure must still book a recovery",
            isBackgroundStartRefusal(IllegalStateException("service died")),
        )
    }

    /**
     * Skipping the recovery is only safe once the ordinary tick is re-armed.
     *
     * Both are attempted independently, so if the re-arm also failed, the recovery alarm is the
     * only thing that can restart the chain. Skipping it in that case leaves time triggers dead
     * until a reboot, which is worse than the retry storm the skip exists to prevent.
     */
    @Test
    fun theRecoveryIsOnlySkippedWhenTheNextTickIsAlreadyArmed() {
        val source = com.opentasker.ProductionSources.block(
            "com/opentasker/automation/receiver/TimeEventReceiver.kt",
            "TimeEventAction.TIME_TICK ->",
            "TimeEventAction.EXACT_ALARM_PERMISSION_CHANGED ->",
        )

        assertTrue(
            "the re-arm result must gate the skip",
            "if (rearmed && isBackgroundStartRefusal(error))" in source,
        )
        assertTrue(
            "and it must be the result of scheduling the next tick",
            "val rearmed = runCatching { scheduler.scheduleNextMinute() }" in source,
        )
    }

    private companion object {
        const val REFUSAL = "android.app.ForegroundServiceStartNotAllowedException"
    }
}
