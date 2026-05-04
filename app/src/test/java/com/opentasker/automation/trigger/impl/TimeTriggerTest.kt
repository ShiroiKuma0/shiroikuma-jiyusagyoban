package com.opentasker.automation.trigger.impl

import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.TriggerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TimeTriggerTest {
    @Test
    fun malformedStepDoesNotThrowOrMatch() {
        val config = TriggerConfig(type = "time").apply {
            config = mapOf("cron" to "*/0 * * * *")
        }

        assertFalse(TimeTrigger().matches(AutomationEvent.TimeEvent(System.currentTimeMillis()), config))
    }

    @Test
    fun validCronMatchesExpectedMinuteAndHour() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 15)
            set(Calendar.HOUR_OF_DAY, 9)
        }
        val config = TriggerConfig(type = "time").apply {
            config = mapOf("cron" to "15 9 * * *")
        }

        assertTrue(TimeTrigger().matches(AutomationEvent.TimeEvent(calendar.timeInMillis), config))
    }
}
