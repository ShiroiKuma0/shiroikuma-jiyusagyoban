package com.opentasker.core.actions

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.platform.LockDeviceAdminReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lock action against a real DevicePolicyManager.
 *
 * This action shipped for months as an unconditional failure, so the thing worth proving on a
 * device is not that it returns Success but that the screen actually locks. Activation normally
 * needs a user tap, which a test cannot make, so the admin is activated through the shell the way
 * a user would through Settings.
 */
@RunWith(AndroidJUnit4::class)
class LockDeviceActionInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val keyguard: KeyguardManager
        get() = context.getSystemService(KeyguardManager::class.java)

    @After
    fun tearDown() {
        removeAdmin()
        // Put the device back the way the AVD ships it, or every later test runs behind a keyguard.
        shell("locksettings set-disabled true")
        shell("wm dismiss-keyguard")
    }

    @Test
    fun theActionLocksTheScreenWhenTheAdminIsActive() {
        // This AVD ships with the lock screen disabled, and lockNow cannot show a keyguard that
        // does not exist. Without this the action would look broken while working correctly.
        shell("locksettings set-disabled false")
        shell("wm dismiss-keyguard")
        waitUntil("the keyguard must start dismissed") { !keyguard.isKeyguardLocked }

        shell("dpm set-active-admin --user current $ADMIN")
        assertTrue("the shell activation must have taken", LockDeviceAdminReceiver.isActive(context))

        val result = runBlocking { LockDeviceAction().run(actionContext(), emptyMap()) }

        assertEquals(ActionResult.Success, result)
        // The claim is the lock, not the return value. An action that returned Success while the
        // screen stayed on would be the same failure this action already had once.
        assertTrue(
            "the screen must actually be locked",
            waitUntil("locked") { keyguard.isKeyguardLocked },
        )
    }

    @Test
    fun theActionRefusesAndNamesSetupWhenTheAdminIsInactive() {
        removeAdmin()
        assertFalse("the admin must start inactive", LockDeviceAdminReceiver.isActive(context))

        val result = runBlocking { LockDeviceAction().run(actionContext(), emptyMap()) }

        val failure = result as? ActionResult.Failure
        assertTrue("an inactive admin must refuse, got $result", failure != null)
        assertTrue(
            "the message must point at the row that fixes it: ${failure!!.message}",
            "Setup" in failure.message,
        )
    }

    private fun actionContext(): ActionContext =
        ActionContext(context.applicationContext, VariableStore(), logger = {})

    /**
     * Removal has to come from the app, which is also what Setup's "Turn off" does.
     *
     * `dpm remove-active-admin` refuses with "Attempt to remove non-test admin" unless the policy
     * declares itself test-only, and leaving the admin active blocks the uninstall the next
     * instrumentation run performs.
     */
    private fun removeAdmin() {
        val manager = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        runCatching { manager?.removeActiveAdmin(LockDeviceAdminReceiver.component(context)) }
        waitUntil("admin removed") { !LockDeviceAdminReceiver.isActive(context) }
    }

    private fun waitUntil(what: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + CONDITION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        // The command runs asynchronously and is only guaranteed to have completed once its output
        // stream reaches EOF, so draining this is what makes the next assertion meaningful.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private companion object {
        const val ADMIN = "com.opentasker.app/com.opentasker.core.platform.LockDeviceAdminReceiver"
        const val CONDITION_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
