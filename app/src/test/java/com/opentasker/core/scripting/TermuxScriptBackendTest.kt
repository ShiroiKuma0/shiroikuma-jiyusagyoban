package com.opentasker.core.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxScriptBackendTest {
    @Test
    fun statusForReportsBridgeReadinessWithoutClaimingExecution() {
        val missingTermux = TermuxScriptBackend.statusFor(termuxInstalled = false, taskerPluginInstalled = false)
        val missingPlugin = TermuxScriptBackend.statusFor(termuxInstalled = true, taskerPluginInstalled = false)
        val installed = TermuxScriptBackend.statusFor(termuxInstalled = true, taskerPluginInstalled = true)

        assertEquals(TermuxScriptState.TermuxMissing, missingTermux.state)
        assertFalse(missingTermux.bridgeInstalled)
        assertEquals(TermuxScriptState.TaskerPluginMissing, missingPlugin.state)
        assertFalse(missingPlugin.bridgeInstalled)
        assertEquals(TermuxScriptState.PluginInstalled, installed.state)
        assertTrue(installed.bridgeInstalled)
        assertTrue("not enabled" in installed.summary)
    }

    @Test
    fun actionHintOnlyCoversTermuxScriptAction() {
        assertNotNull(TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID))
        assertNull(TermuxScriptBackend.hintForAction("plugin.locale.fire"))
    }

    @Test
    fun packageAndPermissionConstantsMatchTermuxContracts() {
        assertEquals("com.termux", TermuxScriptBackend.TERMUX_PACKAGE)
        assertEquals("com.termux.tasker", TermuxScriptBackend.TERMUX_TASKER_PACKAGE)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxScriptBackend.RUN_COMMAND_PERMISSION)
        assertEquals("~/.termux/tasker", TermuxScriptBackend.SCRIPT_DIRECTORY)
    }
}
