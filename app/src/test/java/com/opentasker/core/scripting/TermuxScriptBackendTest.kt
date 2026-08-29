package com.opentasker.core.scripting

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TermuxScriptBackendTest {
    private val script = "~/.termux/tasker/backup.sh"
    private val approvedHash = "a".repeat(64)

    @Test
    fun statusForReportsPackageAndPermissionReadiness() {
        val missingTermux = TermuxScriptBackend.statusFor(termuxInstalled = false, permissionGranted = false)
        val oldVersion = TermuxScriptBackend.statusFor(
            termuxInstalled = true,
            permissionGranted = true,
            resultProtocolSupported = false,
        )
        val missingPermission = TermuxScriptBackend.statusFor(termuxInstalled = true, permissionGranted = false)
        val ready = TermuxScriptBackend.statusFor(termuxInstalled = true, permissionGranted = true)

        assertEquals(TermuxScriptState.TermuxMissing, missingTermux.state)
        assertFalse(missingTermux.termuxInstalled)
        assertFalse(missingTermux.isReady)
        assertEquals(TermuxScriptState.VersionUnsupported, oldVersion.state)
        assertEquals(TermuxScriptState.PermissionRequired, missingPermission.state)
        assertTrue(missingPermission.termuxInstalled)
        assertFalse(missingPermission.isReady)
        assertEquals(TermuxScriptState.Ready, ready.state)
        assertTrue(ready.isReady)
    }

    @Test
    fun actionHintOnlyCoversTermuxScriptAction() {
        assertNotNull(TermuxScriptBackend.hintForAction(TermuxScriptBackend.ACTION_ID))
        assertNull(TermuxScriptBackend.hintForAction("plugin.locale.fire"))
    }

    @Test
    fun packageAndPermissionConstantsMatchTermuxContracts() {
        assertEquals("com.termux", TermuxScriptBackend.TERMUX_PACKAGE)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxScriptBackend.RUN_COMMAND_PERMISSION)
        assertEquals("~/.termux/tasker", TermuxScriptBackend.SCRIPT_DIRECTORY)
        assertFalse(TermuxScriptBackend.supportsResultProtocol("0.108.0"))
        assertTrue(TermuxScriptBackend.supportsResultProtocol("0.109.0-beta.1"))
        assertTrue(TermuxScriptBackend.supportsResultProtocol("0.118.3"))
    }

    @Test
    fun coordinatorDeniesExecutionBeforeCallingTermuxWhenPermissionIsMissing() = runBlocking {
        var calls = 0
        val result = coordinator().execute(
            ready = false,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                commandResult()
            },
        )

        assertEquals(TermuxScriptRejectionReason.PERMISSION_DENIED, (result as TermuxScriptExecutionResult.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test
    fun coordinatorRejectsUnapprovedAndTraversalPathsWithoutDispatch() = runBlocking {
        var calls = 0
        val unapproved = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { null },
            commandRunner = {
                calls++
                commandResult()
            },
        )
        val traversal = coordinator().execute(
            ready = true,
            invocation = invocation(executable = "~/.termux/tasker/../escape.sh"),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                commandResult()
            },
        )

        assertEquals(TermuxScriptRejectionReason.NOT_APPROVED, (unapproved as TermuxScriptExecutionResult.Rejected).reason)
        assertEquals(TermuxScriptRejectionReason.INVALID_INPUT, (traversal as TermuxScriptExecutionResult.Rejected).reason)
        assertEquals(0, calls)
    }

    @Test
    fun coordinatorVerifiesHashBeforeRunningAndPreservesBoundedInputs() = runBlocking {
        val requests = mutableListOf<TermuxCommandRequest>()
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(
                argumentText = "--label 'daily backup'",
                stdin = "payload",
            ),
            approvedHashFor = { approvedHash },
            commandRunner = { request ->
                requests += request
                if (requests.size == 1) hashResult() else verifiedCommandResult(stdout = "done")
            },
        )

        assertTrue(result is TermuxScriptExecutionResult.Completed)
        result as TermuxScriptExecutionResult.Completed
        assertEquals("done", result.command.stdout)
        assertEquals(4, result.command.stdoutOriginalLength)
        assertEquals(TermuxScriptPolicy.HASH_EXECUTABLE, requests[0].executable)
        assertTrue(requests[0].arguments[1].contains("\${1#\\~/}"))
        assertEquals(TermuxScriptPolicy.HASH_EXECUTABLE, requests[1].executable)
        assertTrue(requests[1].arguments[1].contains("\${2#\\~/}"))
        assertEquals("opentasker-run", requests[1].arguments[2])
        assertEquals(approvedHash, requests[1].arguments[3])
        assertEquals(script, requests[1].arguments[4])
        assertEquals(listOf("--label", "daily backup"), requests[1].arguments.drop(5))
        assertEquals("payload", requests[1].stdin)
    }

    @Test
    fun coordinatorAcceptsTheResultOkErrorCodeRealTermuxReturns() = runBlocking {
        // Termux reports "no internal error" as Activity.RESULT_OK (-1), not 0. Issue #14: treating
        // anything but 0 as a failure rejected every real preflight before the hash was compared.
        var calls = 0
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                if (calls == 1) {
                    hashResult(errorCode = TermuxCommandResult.ERROR_CODE_OK)
                } else {
                    verifiedCommandResult(stdout = "ran", errorCode = TermuxCommandResult.ERROR_CODE_OK)
                }
            },
        )

        assertTrue("err = -1 must be read as success", result is TermuxScriptExecutionResult.Completed)
        assertEquals("ran", (result as TermuxScriptExecutionResult.Completed).command.stdout)
        assertEquals(2, calls)
    }

    @Test
    fun coordinatorStillAcceptsTheZeroErrorCodeOlderTermuxBundlesOmit() = runBlocking {
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = { request ->
                if (request.arguments.getOrNull(2) == "opentasker-hash") {
                    hashResult(errorCode = TermuxCommandResult.ERROR_CODE_ABSENT)
                } else {
                    verifiedCommandResult(errorCode = TermuxCommandResult.ERROR_CODE_ABSENT)
                }
            },
        )

        assertTrue(result is TermuxScriptExecutionResult.Completed)
    }

    @Test
    fun coordinatorRejectsPreflightWhenTermuxDeliveredNoResultBundle() = runBlocking {
        var calls = 0
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                hashResult(errorCode = null).copy(exitCode = -1)
            },
        )

        assertEquals(
            TermuxScriptRejectionReason.HASH_CHECK_FAILED,
            (result as TermuxScriptExecutionResult.Rejected).reason,
        )
        assertEquals("script must not run without a delivered result", 1, calls)
    }

    @Test
    fun coordinatorRejectsPreflightOnARealTermuxErrorCode() = runBlocking {
        var calls = 0
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                hashResult(errorCode = 150)
            },
        )

        assertEquals(
            TermuxScriptRejectionReason.HASH_CHECK_FAILED,
            (result as TermuxScriptExecutionResult.Rejected).reason,
        )
        assertEquals(1, calls)
    }

    @Test
    fun commandResultSeparatesDeliveryFromTermuxSuccess() {
        val undelivered = commandResult(errorCode = null)
        val ok = commandResult(errorCode = TermuxCommandResult.ERROR_CODE_OK)
        val absent = commandResult(errorCode = TermuxCommandResult.ERROR_CODE_ABSENT)
        val failed = commandResult(errorCode = 1)

        assertFalse(undelivered.delivered)
        assertFalse(undelivered.termuxSucceeded)
        assertTrue(ok.delivered)
        assertTrue(ok.termuxSucceeded)
        assertTrue(absent.termuxSucceeded)
        assertTrue(failed.delivered)
        assertFalse(failed.termuxSucceeded)
    }

    @Test
    fun coordinatorRejectsHashMismatchBeforeScriptDispatch() = runBlocking {
        var calls = 0
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                hashResult("b".repeat(64))
            },
        )

        assertEquals(TermuxScriptRejectionReason.HASH_MISMATCH, (result as TermuxScriptExecutionResult.Rejected).reason)
        assertEquals(1, calls)
    }

    @Test
    fun coordinatorRejectsScriptChangedBetweenPreflightAndExecution() = runBlocking {
        var calls = 0
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                if (calls == 1) {
                    hashResult()
                } else {
                    commandResult(stdout = TermuxScriptPolicy.verificationMismatchMarker())
                }
            },
        )

        assertEquals(TermuxScriptRejectionReason.HASH_MISMATCH, (result as TermuxScriptExecutionResult.Rejected).reason)
        assertEquals(2, calls)
    }

    @Test
    fun coordinatorRateLimitsRapidRedispatch() = runBlocking {
        var now = 10_000L
        val coordinator = coordinator { now }
        val runner: suspend (TermuxCommandRequest) -> TermuxCommandResult = { request ->
            if (request.arguments.getOrNull(2) == "opentasker-hash") hashResult() else verifiedCommandResult()
        }
        val first = coordinator.execute(true, invocation(), { approvedHash }, runner)
        val second = coordinator.execute(true, invocation(), { approvedHash }, runner)
        now += 1_000L
        val third = coordinator.execute(true, invocation(), { approvedHash }, runner)

        assertTrue(first is TermuxScriptExecutionResult.Completed)
        assertEquals(TermuxScriptRejectionReason.RATE_LIMITED, (second as TermuxScriptExecutionResult.Rejected).reason)
        assertTrue(third is TermuxScriptExecutionResult.Completed)
    }

    @Test
    fun rateLimitMapEvictsOldestEntriesAtItsBound() {
        var now = 1_000L
        val limiter = TermuxDispatchLimiter(maxEntries = 128, clock = { now++ })

        repeat(500) { index -> assertTrue(limiter.tryAcquire("~/.termux/tasker/script-$index")) }

        assertEquals(128, limiter.size())
    }

    @Test
    fun coordinatorPropagatesTimeoutForTheActionToReport() = runBlocking {
        try {
            coordinator().execute(
                ready = true,
                invocation = invocation(),
                approvedHashFor = { approvedHash },
                commandRunner = {
                    withTimeout(1L) { delay(100L) }
                    hashResult()
                },
            )
            fail("Expected command timeout")
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // The action maps this to a redacted, user-visible timeout failure.
        }
    }

    @Test
    fun coordinatorRejectsOversizedOrTermuxTruncatedOutput() = runBlocking {
        var calls = 0
        val result = coordinator().execute(
            ready = true,
            invocation = invocation(),
            approvedHashFor = { approvedHash },
            commandRunner = {
                calls++
                if (calls == 1) {
                    hashResult()
                } else {
                    verifiedCommandResult(
                        stdout = "partial",
                        stdoutOriginalLength = TermuxScriptPolicy.MAX_OUTPUT_BYTES + 1,
                    )
                }
            },
        )

        assertEquals(TermuxScriptRejectionReason.OUTPUT_TOO_LARGE, (result as TermuxScriptExecutionResult.Rejected).reason)
    }

    @Test
    fun sourceContractKeepsResultChannelPrivateAndLogsOutputAsRedacted() {
        val moduleRoot = listOf(Path.of("."), Path.of("app")).first { Files.isDirectory(it.resolve("src/main")) }
        val manifest = moduleRoot.resolve("src/main/AndroidManifest.xml").toFile().readText()
        val broker = moduleRoot.resolve("src/main/java/com/opentasker/core/scripting/TermuxCommandBroker.kt").toFile().readText()
        val action = moduleRoot.resolve("src/main/java/com/opentasker/core/actions/ScriptActions.kt").toFile().readText()

        assertTrue("RUN_COMMAND permission must be declared", "com.termux.permission.RUN_COMMAND" in manifest)
        assertTrue("Result receiver must be non-exported", Regex("TermuxResultReceiver[\\s\\S]{0,120}android:exported=\"false\"").containsMatchIn(manifest))
        assertTrue("Termux callback must use a one-shot pending intent", "PendingIntent.FLAG_ONE_SHOT" in broker)
        assertTrue("Termux original lengths must parse their documented string encoding", "bundle.getString(key)?.toIntOrNull()" in broker)
        assertTrue("Command output must be redacted from logs", "stdout=<redacted:" in action && "stderr=<redacted:" in action)
        assertFalse("Captured stdout must never be interpolated into a log", "\${result.stdout}" in action)
    }

    @Test
    fun scriptHashProducesStableSha256() {
        val content = "#!/bin/bash\necho hello".toByteArray()
        val hash1 = TermuxScriptDispatch.hashScript(content)
        val hash2 = TermuxScriptDispatch.hashScript(content)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
        assertTrue(hash1 != TermuxScriptDispatch.hashScript("different".toByteArray()))
    }

    private fun coordinator(clock: () -> Long = { 10_000L }): TermuxScriptCoordinator =
        TermuxScriptCoordinator(TermuxDispatchLimiter(clock = clock))

    private fun invocation(
        executable: String = script,
        argumentText: String? = null,
        stdin: String? = null,
    ) = TermuxScriptInvocation(
        executable = executable,
        argumentText = argumentText,
        workingDirectory = null,
        stdin = stdin,
        timeoutMs = TermuxScriptPolicy.DEFAULT_TIMEOUT_MS,
    )

    private fun hashResult(
        hash: String = approvedHash,
        errorCode: Int? = 0,
    ): TermuxCommandResult = commandResult(stdout = "$hash  $script\n", errorCode = errorCode)

    private fun verifiedCommandResult(
        stdout: String = "",
        stderr: String = "",
        stdoutOriginalLength: Int = TermuxScriptPolicy.utf8Size(stdout),
        stderrOriginalLength: Int = TermuxScriptPolicy.utf8Size(stderr),
        exitCode: Int = 0,
        errorCode: Int? = 0,
    ): TermuxCommandResult {
        val marker = TermuxScriptPolicy.verificationSuccessMarker(approvedHash)
        return commandResult(
            stdout = marker + stdout,
            stderr = stderr,
            stdoutOriginalLength = marker.length + stdoutOriginalLength,
            stderrOriginalLength = stderrOriginalLength,
            exitCode = exitCode,
            errorCode = errorCode,
        )
    }

    private fun commandResult(
        stdout: String = "",
        stderr: String = "",
        stdoutOriginalLength: Int = TermuxScriptPolicy.utf8Size(stdout),
        stderrOriginalLength: Int = TermuxScriptPolicy.utf8Size(stderr),
        exitCode: Int = 0,
        errorCode: Int? = 0,
    ) = TermuxCommandResult(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        stdoutOriginalLength = stdoutOriginalLength,
        stderrOriginalLength = stderrOriginalLength,
        errorCode = errorCode,
    )
}
