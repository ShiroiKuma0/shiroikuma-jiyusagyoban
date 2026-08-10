package com.opentasker.automation.receiver

import android.content.Intent
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.scheduling.ExactAlarmSupport
import org.junit.Assert.assertEquals
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
}
