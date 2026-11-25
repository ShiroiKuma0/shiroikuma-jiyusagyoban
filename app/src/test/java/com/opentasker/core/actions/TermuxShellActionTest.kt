package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxShellActionTest {

    @Test
    fun withoutTheSwitchTheLineGoesToTermuxUntouched() {
        assertEquals("sshd", TermuxShellAction.commandLine("sshd", profile = false))
    }

    @Test
    fun withTheSwitchTheProfileIsReadFirstAndTheCommandStillRuns() {
        val line = TermuxShellAction.commandLine("sshd && echo up", profile = true)
        assertTrue(line.startsWith("if [ -f \"\$HOME/.profile\" ]; then . \"\$HOME/.profile\"; fi\n"))
        assertTrue(line.endsWith("\nsshd && echo up"))
    }

    @Test
    fun theRootRefusalIsToldApartFromACommandsOwnFailure() {
        assertTrue(LocalShell.rootDenied("su: Permission denied"))
        assertEquals(false, LocalShell.rootDenied("ls: /nope: No such file or directory"))
    }

    @Test
    fun theSessionFooterIsNotPartOfTheOutput() {
        assertEquals("line 5\n10190", TermuxShellAction.withoutSessionFooter("line 5\n10190\n\n[Process completed - press Enter]\n"))
        assertEquals("ok", TermuxShellAction.withoutSessionFooter("ok\n[Process completed (code 3) - press Enter]"))
        assertEquals("plain output", TermuxShellAction.withoutSessionFooter("plain output"))
    }

    @Test
    fun theWrappedLineKeepsTheCommandAndItsExitCode() {
        val wrapped = TermuxLiveOutput.wrap("sshd && echo up", port = 40123, token = "tok")
        assertTrue(wrapped.contains("/dev/tcp/127.0.0.1/40123"))
        assertTrue(wrapped.contains("printf '%s\\n' 'tok' >&9"))
        assertTrue(wrapped.contains("{\nsshd && echo up\n} > >(tee >(cat >&9)) 2> >(tee >(cat >&9) >&2)"))
        assertTrue(wrapped.trimEnd().endsWith("exit \$__jsb_exit"))
    }
}
