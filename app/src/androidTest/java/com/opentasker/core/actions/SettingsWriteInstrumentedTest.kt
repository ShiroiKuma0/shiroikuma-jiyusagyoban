package com.opentasker.core.actions

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Write Setting action against the real Settings provider.
 *
 * `WRITE_SECURE_SETTINGS` cannot be requested at runtime, so the only honest way to prove both
 * halves of this action is to grant and revoke it through the shell, exactly as a user does over a
 * cable. A JVM test can check the argument rules and nothing else: whether Android accepts the
 * write, and whether it quietly ignores it, is only answerable on a device.
 */
@RunWith(AndroidJUnit4::class)
class SettingsWriteInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val action = SettingsWriteAction()

    private var originalLocationMode: String? = null

    @After
    fun tearDown() {
        grantSecureSettings()
        originalLocationMode?.let { previous ->
            Settings.Secure.putString(context.contentResolver, LOCATION_MODE, previous)
        }
        Settings.Global.putString(context.contentResolver, PROBE_KEY, null)
    }

    @Test
    fun aGrantedWriteChangesTheSettingAndReadsItBack() = runBlocking {
        grantSecureSettings()
        originalLocationMode = Settings.Secure.getString(context.contentResolver, LOCATION_MODE)
        val target = if (originalLocationMode == "3") "0" else "3"

        val result = action.run(actionContext(), args(target))

        assertEquals(ActionResult.Success, result)
        assertEquals(
            "the action reported success, so the setting must actually hold the new value",
            target,
            Settings.Secure.getString(context.contentResolver, LOCATION_MODE),
        )
    }

    @Test
    fun withoutTheGrantTheActionRefusesAndHandsOverTheCommand() = runBlocking {
        revokeSecureSettings()

        val result = action.run(actionContext(), args("3"))

        assertTrue("expected a refusal, got $result", result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(
            "the refusal must carry the grant command, because nothing in the UI can grant this: $message",
            secureSettingsGrantCommand(context.packageName) in message,
        )
    }

    /**
     * The Settings tables have no fixed schema.
     *
     * This test first asserted that a name Android does not know would be ignored, and the device
     * disproved it: the provider stored `opentasker_no_such_setting` and read it straight back.
     * That is worth pinning, because it is the reason the action compares the value it read to the
     * value it wrote instead of validating the name against any list. A write "succeeding" says
     * nothing on its own.
     */
    @Test
    fun anUnknownSettingNameIsStoredRatherThanIgnored() = runBlocking {
        grantSecureSettings()

        val result = action.run(
            actionContext(),
            mapOf("table" to "global", "key" to PROBE_KEY, "value" to "1"),
        )

        assertEquals(ActionResult.Success, result)
        assertEquals("1", Settings.Global.getString(context.contentResolver, PROBE_KEY))
    }

    private fun args(value: String) =
        mapOf("table" to "secure", "key" to LOCATION_MODE, "value" to value)

    private fun actionContext() = ActionContext(context, VariableStore())

    private fun grantSecureSettings() = shell("pm grant ${context.packageName} $PERMISSION")

    private fun revokeSecureSettings() = shell("pm revoke ${context.packageName} $PERMISSION")

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        // The command completes only once its output stream reaches EOF; without draining it the
        // next assertion can run against the permission state from before the grant.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private companion object {
        const val LOCATION_MODE = "location_mode"
        const val PROBE_KEY = "opentasker_no_such_setting"
        const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    }
}
