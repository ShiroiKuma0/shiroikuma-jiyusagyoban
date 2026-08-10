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

    /**
     * `target_args` holds the *target action's* arguments and never carries `target_action`, so a
     * `capture(context, args)` overload silently resolves the action id to "" and returns null for
     * every invocation. Keep the action id an explicit parameter so that overload cannot come back.
     */
    @Test
    fun captureAlwaysRequiresAnExplicitActionId() {
        val captureOverloads = TemporaryStateTarget::class.java.methods.filter { it.name == "capture" }

        assertTrue("capture(context, actionId, args) must exist", captureOverloads.isNotEmpty())
        captureOverloads.forEach { method ->
            assertEquals(
                "capture must take the action id explicitly, not read it back out of the target args",
                3,
                method.parameterCount,
            )
            assertEquals(String::class.java, method.parameterTypes[1])
        }
    }
}
