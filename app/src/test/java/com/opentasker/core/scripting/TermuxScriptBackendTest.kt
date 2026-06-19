package com.opentasker.core.scripting

import com.opentasker.core.actions.TermuxScriptDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxScriptBackendTest {
    @Test
    fun statusForReportsBridgeReadiness() {
        val missingTermux = TermuxScriptBackend.statusFor(termuxInstalled = false, taskerPluginInstalled = false)
        val missingPlugin = TermuxScriptBackend.statusFor(termuxInstalled = true, taskerPluginInstalled = false)
        val installed = TermuxScriptBackend.statusFor(termuxInstalled = true, taskerPluginInstalled = true)

        assertEquals(TermuxScriptState.TermuxMissing, missingTermux.state)
        assertFalse(missingTermux.bridgeInstalled)
        assertEquals(TermuxScriptState.TaskerPluginMissing, missingPlugin.state)
        assertFalse(missingPlugin.bridgeInstalled)
        assertEquals(TermuxScriptState.PluginInstalled, installed.state)
        assertTrue(installed.bridgeInstalled)
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

    @Test
    fun frequencyCapAllowsFirstDispatch() {
        assertTrue(TermuxScriptDispatch.checkFrequencyCap("/path/to/unique_test_script.sh"))
    }

    @Test
    fun frequencyCapBlocksRapidRedispatch() {
        TermuxScriptDispatch.recordDispatch("/test/rapid.sh")
        assertFalse(TermuxScriptDispatch.checkFrequencyCap("/test/rapid.sh"))
    }

    @Test
    fun scriptHashProducesConsistentSha256() {
        val content = "#!/bin/bash\necho hello".toByteArray()
        val hash1 = TermuxScriptDispatch.hashScript(content)
        val hash2 = TermuxScriptDispatch.hashScript(content)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
    }

    @Test
    fun scriptHashDiffersForDifferentContent() {
        val hash1 = TermuxScriptDispatch.hashScript("script1".toByteArray())
        val hash2 = TermuxScriptDispatch.hashScript("script2".toByteArray())
        assertTrue(hash1 != hash2)
    }
}
