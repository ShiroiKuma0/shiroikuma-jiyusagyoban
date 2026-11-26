package com.opentasker.core.scripting

import com.sysadmindoc.ruledeck.core.scripting.*
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.sysadmindoc.ruledeck.core.apps.TargetSignerGuard
import com.sysadmindoc.ruledeck.core.apps.TargetSignerMismatchException
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.core.platform.ForeignParcels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

internal object TermuxCommandBroker {
    private const val TAG = "TermuxCommandBroker"
    private const val RESULT_ACTION = "com.opentasker.action.TERMUX_COMMAND_RESULT"
    private const val EXTRA_REQUEST_ID = "com.opentasker.extra.TERMUX_REQUEST_ID"
    private const val MAX_PENDING_COMMANDS = 32
    private val pending = ConcurrentHashMap<String, CompletableDeferred<TermuxCommandResult>>()

    suspend fun execute(context: Context, request: TermuxCommandRequest): TermuxCommandResult =
        execute(TargetSignerGuard.forContext(context), request) { dispatch(context, it) }

    /**
     * The pinned-signer check runs before any callback is registered or intent built, so a Termux
     * whose signing certificate changed is sent nothing. It throws [TargetSignerMismatchException]
     * (never a SecurityException, which the action reports as a permission problem). A Termux
     * that isn't installed passes through and fails the way it always has.
     */
    internal suspend fun execute(
        signerGuard: TargetSignerGuard,
        request: TermuxCommandRequest,
        dispatch: suspend (TermuxCommandRequest) -> TermuxCommandResult,
    ): TermuxCommandResult {
        signerGuard.requireTrusted(TermuxScriptBackend.TERMUX_PACKAGE)
        return dispatch(request)
    }

    private suspend fun dispatch(context: Context, request: TermuxCommandRequest): TermuxCommandResult {
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        synchronized(pending) {
            check(pending.size < MAX_PENDING_COMMANDS) { "Too many pending Termux commands" }
            pending[requestId] = deferred
        }

        // The answer comes back as a SERVICE start, not a broadcast. Android hands a process its
        // manifest broadcasts one at a time, so a task that was itself started by a broadcast still
        // being worked on (the adb bridge, a sister app's request, the restart after an update)
        // never saw Termux's answer: it sat in the queue behind the very broadcast that was waiting
        // for it, and the action ran into its time limit (found on Slave 1, 2026-11-24).
        val callbackIntent = Intent(context, TermuxResultService::class.java).apply {
            action = RESULT_ACTION
            data = Uri.parse("opentasker://termux-result/$requestId")
            setPackage(context.packageName)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getService(
            context,
            requestId.hashCode(),
            callbackIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
        val commandIntent = Intent().apply {
            component = ComponentName(
                TermuxScriptBackend.TERMUX_PACKAGE,
                "com.termux.app.RunCommandService",
            )
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, request.executable)
            putExtra(EXTRA_ARGUMENTS, request.arguments.toTypedArray())
            putExtra(EXTRA_BACKGROUND, !request.foreground)
            // "0": switch to the new session and open Termux, when it runs in a session at all.
            if (request.foreground) putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_PENDING_INTENT, callback)
            request.workingDirectory?.let { putExtra(EXTRA_WORKDIR, it) }
            request.stdin?.let { putExtra(EXTRA_STDIN, it) }
        }

        return try {
            context.startForegroundService(commandIntent)
            withTimeout(request.timeoutMs) { deferred.await() }
        } finally {
            pending.remove(requestId)
            callback.cancel()
        }
    }

    internal fun deliver(intent: Intent) {
        if (intent.action != RESULT_ACTION) return
        val requestId = intent.data?.lastPathSegment
        // Each way out says why: a result that is dropped here looks, from the task, exactly like a
        // Termux that never answered (Slave 1, 2026-11-24).
        if (requestId == null) {
            AppLogger.warn(TAG, "Termux result without a request id in its data: ${intent.data}")
            return
        }
        val result = readResult(intent, requestId)
        if (result == null) {
            AppLogger.warn(
                TAG,
                "Termux result for $requestId not accepted: id extra " +
                    (if (runCatching { intent.getStringExtra(EXTRA_REQUEST_ID) }.getOrNull() == requestId) "matches" else "differs or is missing") +
                    ", keys ${runCatching { intent.extras?.keySet()?.joinToString() }.getOrNull()}",
            )
            return
        }
        val waiting = pending.remove(requestId)
        if (waiting == null) AppLogger.warn(TAG, "Termux result for $requestId arrived, but nothing waits for it (${pending.size} pending)")
        waiting?.complete(result)
    }

    /**
     * Termux's answer to [requestId], or null when the intent names another request or can't be
     * read. One that can't be read is left to time out, the same as a result that never came.
     */
    internal fun readResult(intent: Intent, requestId: String): TermuxCommandResult? {
        ForeignParcels.guard(intent)
        return ForeignParcels.read({
            if (intent.getStringExtra(EXTRA_REQUEST_ID) != requestId) return null
            parseResult(intent.getBundleExtra(EXTRA_RESULT_BUNDLE))
        }) { e ->
            AppLogger.warn(TAG, "Discarded a Termux result that couldn't be read", e)
            null
        }
    }

    internal fun parseResult(bundle: Bundle?): TermuxCommandResult {
        if (bundle == null) {
            return TermuxCommandResult(
                stdout = "",
                stderr = "",
                exitCode = -1,
                stdoutOriginalLength = 0,
                stderrOriginalLength = 0,
                errorCode = null,
            )
        }
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle.getString(RESULT_STDERR).orEmpty()
        return TermuxCommandResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = bundle.getInt(RESULT_EXIT_CODE, -1),
            stdoutOriginalLength = parseOriginalLength(bundle, RESULT_STDOUT_ORIGINAL_LENGTH),
            stderrOriginalLength = parseOriginalLength(bundle, RESULT_STDERR_ORIGINAL_LENGTH),
            errorCode = bundle.getInt(RESULT_ERROR_CODE, TermuxCommandResult.ERROR_CODE_ABSENT),
            errorMessage = TermuxScriptPolicy.sanitizeErrorMessage(bundle.getString(RESULT_ERROR_MESSAGE)),
        )
    }

    private fun parseOriginalLength(bundle: Bundle, key: String): Int =
        bundle.getString(key)?.toIntOrNull()?.takeIf { it >= 0 } ?: -1

    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val EXTRA_RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERROR_CODE = "err"
    private const val RESULT_ERROR_MESSAGE = "errmsg"
    private const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    private const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.info("TermuxCommandBroker", "Termux result broadcast received")
        TermuxCommandBroker.deliver(intent)
    }
}

/** Takes Termux's answer to a RUN_COMMAND and hands it to the action that waits for it. */
class TermuxResultService : android.app.Service() {
    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) TermuxCommandBroker.deliver(intent)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
