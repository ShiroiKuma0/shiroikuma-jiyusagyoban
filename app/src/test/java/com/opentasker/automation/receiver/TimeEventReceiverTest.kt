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
     * A message that merely mentions the refusal is not the refusal: the check reads class names,
     * so this stays false even though the text matches.
     */
    @Test
    fun aLookalikeMessageIsNotTreatedAsTheRefusal() {
        assertFalse(isBackgroundStartRefusal(RuntimeException(REFUSAL)))
    }

    private companion object {
        const val REFUSAL = "android.app.ForegroundServiceStartNotAllowedException"
    }
}
