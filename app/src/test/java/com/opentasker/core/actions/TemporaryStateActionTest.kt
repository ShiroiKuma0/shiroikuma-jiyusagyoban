package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryStateActionTest {
    @Test
    fun parsesBoundedTemporaryStatePlan() {
        val plan = TemporaryStatePlan.parse(
            mapOf(
                "target_action" to "brightness.set",
                "target_args" to "{\"brightness\":\"80\"}",
                "key" to "quiet-hours",
                "duration_sec" to "3600",
            ),
        ).getOrThrow()

        assertEquals("brightness.set", plan.targetAction)
        assertEquals(mapOf("brightness" to "80"), plan.targetArgs)
        assertEquals("quiet-hours", plan.key)
        assertEquals(3600L, plan.durationSec)
    }

    @Test
    fun rejectsUnknownTargetsAndUnboundedInputs() {
        val unknown = TemporaryStatePlan.parse(
            mapOf(
                "target_action" to "wifi.toggle",
                "target_args" to "{}",
                "key" to "wifi",
                "duration_sec" to "60",
            ),
        )
        val oversizedArgs = TemporaryStatePlan.parse(
            mapOf(
                "target_action" to "brightness.set",
                "target_args" to "{\"brightness\":\"${"8".repeat(513)}\"}",
                "key" to "brightness",
                "duration_sec" to "60",
            ),
        )

        assertTrue(unknown.isFailure)
        assertTrue(oversizedArgs.isFailure)
    }

    @Test
    fun onlyReversibleSettingTargetsAreAdvertised() {
        assertEquals(
            setOf("brightness.set", "volume.set", "ringer.set", "dnd.set"),
            TemporaryStateTarget.SUPPORTED_ACTIONS,
        )
        assertTrue(TemporaryStateTarget.forAction("brightness.set") != null)
        assertTrue(TemporaryStateTarget.forAction("torch.set") == null)
    }
}
