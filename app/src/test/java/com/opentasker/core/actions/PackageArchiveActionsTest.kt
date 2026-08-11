package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageArchiveActionsTest {
    @Test
    fun archiveFailsClosedBelowAndroid15() = runBlocking {
        val result = AppArchiveAction { 34 }.run(
            ActionContext(ContextWrapper(null), VariableStore()),
            mapOf("package" to "com.example.target"),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("Android 15"))
    }

    @Test
    fun unarchiveFailsClosedBelowAndroid15() = runBlocking {
        val result = AppUnarchiveAction { 34 }.run(
            ActionContext(ContextWrapper(null), VariableStore()),
            mapOf("package" to "com.example.target"),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("Android 15"))
    }

    @Test
    fun archiveRejectsMalformedPackageBeforeTouchingPackageInstaller() = runBlocking {
        val result = AppArchiveAction { 35 }.run(
            ActionContext(ContextWrapper(null), VariableStore()),
            mapOf("package" to "not a package"),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("invalid package"))
    }

    @Test
    fun theConfirmationAndroidAlwaysAsksForIsNotTreatedAsFailure() {
        // OpenTasker is never the installer of record, so STATUS_PENDING_USER_ACTION is the first
        // answer to every request. Classifying it as terminal is why the action could never
        // succeed; it must stay pending so the confirmation can be shown.
        assertTrue(
            PackageInstaller.STATUS_PENDING_USER_ACTION
                .needsUserConfirmation(PackageArchiveMode.ARCHIVE.name),
        )
        assertTrue(
            PackageInstaller.UNARCHIVAL_ERROR_USER_ACTION_NEEDED
                .needsUserConfirmation(PackageArchiveMode.UNARCHIVE.name),
        )
    }

    @Test
    fun terminalStatusesAreStillTerminal() {
        assertFalse(
            PackageInstaller.STATUS_SUCCESS.needsUserConfirmation(PackageArchiveMode.ARCHIVE.name),
        )
        assertFalse(
            PackageInstaller.STATUS_FAILURE.needsUserConfirmation(PackageArchiveMode.ARCHIVE.name),
        )
        assertFalse(
            PackageInstaller.UNARCHIVAL_OK.needsUserConfirmation(PackageArchiveMode.UNARCHIVE.name),
        )
    }
}
