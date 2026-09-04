package com.opentasker.automation.receiver

import android.content.Intent
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

        receiver.onReceive(null, null)
        receiver.onReceive(null, Intent(TimeEventScheduler.ACTION_TIME_TICK))
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
