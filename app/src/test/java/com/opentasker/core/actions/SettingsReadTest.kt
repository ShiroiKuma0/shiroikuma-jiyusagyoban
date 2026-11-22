package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Argument checks for Read Setting. They all come before the Settings provider is touched, so a
 * context with nothing behind it is enough; the read itself runs in SettingsReadInstrumentedTest.
 */
class SettingsReadTest {

    private fun refusal(vararg args: Pair<String, String>): String {
        val variables = VariableStore()
        val result = runBlocking { SettingsReadAction().run(ActionContext(ContextWrapper(null), variables), mapOf(*args)) }
        assertTrue("expected a refusal, got $result", result is ActionResult.Failure)
        assertNull("a refused read stores nothing", variables.get("Out"))
        return (result as ActionResult.Failure).message
    }

    @Test
    fun aReadNeedsATableANameAndAVariable() {
        assertTrue(refusal("key" to "location_mode", "var" to "%Out").let { "global" in it && "system" in it })
        assertTrue(refusal("table" to "secure", "key" to "Location Mode", "var" to "%Out").contains("64 characters"))
        assertTrue(refusal("table" to "secure", "key" to "location_mode").contains("variable"))
        assertTrue(refusal("table" to "secure", "key" to "location_mode", "var" to "%1bad").contains("variable"))
    }

    @Test
    fun aReadTheProviderRefusesFailsTheStepInsteadOfThrowing() {
        // On the JVM every Settings call throws, which stands in for Android refusing the read.
        listOf("global", "secure", "system").forEach { table ->
            val message = refusal("table" to table, "key" to "location_mode", "var" to "%Out")
            assertTrue(message, message.startsWith("Android won't let this app read $table/location_mode"))
        }
    }
}
