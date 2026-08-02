package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
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
}
