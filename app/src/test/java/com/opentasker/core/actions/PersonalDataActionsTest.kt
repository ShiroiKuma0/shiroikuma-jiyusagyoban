package com.opentasker.core.actions

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataActionsTest {
    @Test
    fun contactsLookupFailsClosedWhenPermissionIsDenied() = runBlocking {
        val context = object : ContextWrapper(null) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.READ_CONTACTS) PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
        }

        val result = ContactsLookupAction().run(ActionContext(context, VariableStore()), mapOf("query" to "Ada"))

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("READ_CONTACTS"))
    }

    @Test
    fun clipboardSetRejectsOversizedTextBeforeTouchingTheSystemClipboard() = runBlocking {
        val result = ClipboardSetAction().run(
            ActionContext(ContextWrapper(null), VariableStore()),
            mapOf("text" to "x".repeat(64 * 1024 + 1)),
        )

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).message.contains("65536"))
    }
}
