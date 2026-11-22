package com.opentasker.core.actions

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read Setting against the real Settings provider. It only reads, so it runs on a personal phone
 * without changing anything there.
 */
@RunWith(AndroidJUnit4::class)
class SettingsReadInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun read(table: String, key: String): Pair<ActionResult, String?> {
        val variables = VariableStore()
        val result = runBlocking {
            SettingsReadAction().run(ActionContext(context, variables), mapOf("table" to table, "key" to key, "var" to "%Got"))
        }
        return result to variables.get("Got")
    }

    @Test
    fun eachTableReadsWhatAndroidHolds() {
        val resolver = context.contentResolver
        listOf(
            Triple("system", "screen_off_timeout", Settings.System.getString(resolver, "screen_off_timeout")),
            Triple("global", "airplane_mode_on", Settings.Global.getString(resolver, "airplane_mode_on")),
            Triple("secure", "location_mode", Settings.Secure.getString(resolver, "location_mode")),
        ).forEach { (table, key, expected) ->
            assertEquals("$table/$key", ActionResult.Success to expected.orEmpty(), read(table, key))
        }
    }

    @Test
    fun aSettingThatIsNotThereIsStoredAsEmpty() {
        assertEquals(ActionResult.Success to "", read("global", "opentasker_never_written_setting"))
    }
}
