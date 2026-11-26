package com.sysadmindoc.ruledeck.core.assistant

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.ruledeck.core.actions.AirplaneModeAction
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.engine.VariableStore
import com.sysadmindoc.ruledeck.core.power.ShizukuPowerBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Discussion #10 on a real phone: with RuleDeck as the digital assistant app and no Shizuku, the
 * Airplane Mode action asks Settings through a voice session, and the setting has to move both
 * ways. Run it with the role set (`cmd role add-role-holder android.app.role.ASSISTANT
 * com.sysadmindoc.ruledeck`), and once more with the screen off, which is how a profile runs it.
 */
class AssistantAirplaneInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun airplaneOn(): Boolean =
        Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    @Test
    fun theAssistantRouteSwitchesAirplaneModeOnAndBackOff() = runBlocking {
        assumeTrue("RuleDeck isn't the digital assistant app", AssistantAirplane.isActive(app))
        assumeFalse("Shizuku is ready, so the action would go through it", ShizukuPowerBackend.isReady())
        // Android binds the assistant service a moment after the role changes.
        withTimeout(10_000) { while (RuleDeckVoiceInteractionService.current == null) delay(100) }

        val before = airplaneOn()
        val logs = mutableListOf<String>()
        val ctx = ActionContext(app, VariableStore(), logger = { logs += it })
        try {
            assertEquals(logs.toString(), ActionResult.Success, AirplaneModeAction().run(ctx, mapOf("state" to "on")))
            assertTrue(airplaneOn())
            assertEquals(logs.toString(), ActionResult.Success, AirplaneModeAction().run(ctx, mapOf("state" to "off")))
            assertFalse(airplaneOn())
            assertTrue(logs.toString(), logs.any { "through the assistant app" in it })
        } finally {
            if (airplaneOn() != before) AirplaneModeAction().run(ctx, mapOf("state" to if (before) "on" else "off"))
        }
    }
}
