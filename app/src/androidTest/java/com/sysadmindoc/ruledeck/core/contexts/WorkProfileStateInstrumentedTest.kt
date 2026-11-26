package com.sysadmindoc.ruledeck.core.contexts

import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileInputStream

/**
 * `work_profile` read from real profiles (A-280). Private Space is a profile Android
 * hides from this app, so it must not read as a work profile, and Android 15's profile types have
 * to name the work profile for this app or every work profile would read none.
 *
 * It creates and removes Android users, so it runs on an emulator only.
 */
class WorkProfileStateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun aWorkProfileReadsAvailableThenPausedAndPrivateSpaceNeverCounts() {
        assumeTrue("creates Android users, so emulators only", Build.PRODUCT.startsWith("sdk_gphone") || Build.FINGERPRINT.startsWith("generic"))
        assumeTrue(WorkProfileState.supported(context))
        // A run killed part way leaves its users behind, and they'd skip every later run.
        Regex("""UserInfo\{(\d+):$PROBE:""").findAll(shell("pm list users")).forEach { shell("pm remove-user ${it.groupValues[1]}") }
        assumeTrue("starts from a device with no other profile", WorkProfileState.read(context) == WorkProfileState.NONE)
        val parent = shell("am get-current-user").trim()
        val created = mutableListOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                val privateSpace = createProfile("--user-type android.os.usertype.profile.PRIVATE --profileOf $parent")
                if (privateSpace == null) {
                    Log.w(TAG, "This image can't create Private Space, so that half went unchecked")
                } else {
                    created += privateSpace
                    assertEquals("Private Space isn't the work profile", WorkProfileState.NONE, WorkProfileState.read(context))
                }
            }
            val work = createProfile("--managed --profileOf $parent")
            assumeTrue("this image can create a work profile", work != null)
            created += work!!
            assertEquals(WorkProfileState.AVAILABLE, WorkProfileState.read(context))

            val users = context.getSystemService(UserManager::class.java)
            val handle = users.userProfiles.first { it != Process.myUserHandle() && it.hashCode().toString() == work }
            // Held by the shell, not grantable to an app, and not in the public SDK.
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MODIFY_QUIET_MODE")
            try {
                users.requestQuietModeEnabled(true, handle)
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
            assertEquals(WorkProfileState.PAUSED, waitFor(WorkProfileState.PAUSED))
        } finally {
            created.forEach { shell("pm remove-user $it") }
        }
    }

    private fun createProfile(options: String): String? =
        Regex("""created user id (\d+)""").find(shell("pm create-user $options $PROBE"))?.groupValues?.get(1)

    private fun waitFor(expected: String): String {
        val deadline = System.currentTimeMillis() + 10_000
        var latest = WorkProfileState.read(context)
        while (latest != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            latest = WorkProfileState.read(context)
        }
        return latest
    }

    private companion object {
        const val PROBE = "RuleDeckProbe"
        const val TAG = "WorkProfileStateTest"
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            .also { descriptor.close() }
    }
}
