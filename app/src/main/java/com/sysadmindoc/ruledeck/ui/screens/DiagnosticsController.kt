package com.sysadmindoc.ruledeck.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.diagnostics.AdvancedProtectionReader
import com.sysadmindoc.ruledeck.core.diagnostics.CrashLogHandler
import com.sysadmindoc.ruledeck.core.diagnostics.CrashLogRecord
import com.sysadmindoc.ruledeck.core.diagnostics.DiagnosticExport
import com.sysadmindoc.ruledeck.core.diagnostics.EngineHealthReader
import com.sysadmindoc.ruledeck.core.diagnostics.EngineHealthStatus
import com.sysadmindoc.ruledeck.core.engine.ExecutionAdmissionRegistry
import com.sysadmindoc.ruledeck.core.engine.ExecutionAdmissionSnapshot
import com.sysadmindoc.ruledeck.core.logging.AppLogEntry
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.core.storage.AppDatabase
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DiagnosticsUiState(
    val health: EngineHealthStatus? = null,
    val admission: ExecutionAdmissionSnapshot? = null,
    val crashLogs: List<CrashLogRecord> = emptyList(),
    val appLogs: List<AppLogEntry> = emptyList(),
    val loadedAtMillis: Long = 0L,
    /** Resolves admission rows to profile names; they previously showed raw Room ids. */
    val profileNames: Map<Long, String> = emptyMap(),
    /** The last read failed or didn't answer in time; the screen offers Retry instead of "Loading". */
    val loadFailed: Boolean = false,
)

/**
 * The Diagnostics lane of [ActiveAutomationViewModel]: the health read, and copying or sharing the
 * redacted report. It runs on the view model's scope and reports through the view model's
 * message channel, so it lives and dies with the screen.
 */
internal class DiagnosticsController(
    private val db: AppDatabase,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val events: SendChannel<UiMessage>,
    /** The redacted report Copy and Share send. It reads health, prefs and crash-log files. */
    private val buildReport: suspend () -> String = { DiagnosticExport.buildReport(appContext, db) },
) {
    private val _diagnosticsState = MutableStateFlow(DiagnosticsUiState())
    val diagnosticsState: StateFlow<DiagnosticsUiState> = _diagnosticsState.asStateFlow()
    private var diagnosticsRefreshJob: Job? = null

    init {
        refreshDiagnostics()
        scope.launch {
            AdvancedProtectionReader.changes.collect {
                refreshDiagnostics()
            }
        }
    }

    fun refreshDiagnostics() {
        // A read in flight is left to finish unless it has already been given up on. Then Retry
        // starts a fresh one instead of queueing behind a call that may never return (A-360).
        if (diagnosticsRefreshJob?.isActive == true && !_diagnosticsState.value.loadFailed) return
        diagnosticsRefreshJob?.cancel()
        _diagnosticsState.value = _diagnosticsState.value.copy(loadFailed = false)
        diagnosticsRefreshJob = scope.launch {
            // Awaited rather than run inline, so the timeout fires on time even when a blocking
            // call inside the read ignores cancellation.
            val read = async(Dispatchers.IO) {
                runCatching {
                    DiagnosticsUiState(
                        health = EngineHealthReader.read(appContext),
                        admission = ExecutionAdmissionRegistry.snapshot(appContext),
                        crashLogs = CrashLogHandler.listCrashLogs(appContext),
                        appLogs = AppLogger.snapshot().takeLast(100).map { entry ->
                            entry.copy(message = DiagnosticExport.redactSensitive(entry.message))
                        },
                        loadedAtMillis = System.currentTimeMillis(),
                        profileNames = db.profileDao().getAll().associate { it.id to it.name },
                    )
                }
            }
            val result = withTimeoutOrNull(DIAGNOSTICS_READ_TIMEOUT_MS) { read.await() }
                ?: Result.failure(TimeoutException("Diagnostics read took longer than $DIAGNOSTICS_READ_TIMEOUT_MS ms"))
            result.onSuccess { state ->
                _diagnosticsState.value = state
            }.onFailure { error ->
                read.cancel()
                _diagnosticsState.value = _diagnosticsState.value.copy(loadFailed = true)
                events.send(loggedUiErrorMessage(error, R.string.ui_error_diagnostics_refresh))
            }
        }
    }

    /**
     * Puts the same redacted report Share sends onto the clipboard.
     *
     * Share opens a chooser, which is the wrong shape for pasting into a bug report, so issue
     * reports arrived as screenshots of this screen instead of its text.
     */
    fun copyDiagnosticReport() {
        scope.launch {
            try {
                val report = report()
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Clipboard service is unavailable")
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(appContext.getString(R.string.diagnostics_copy), report),
                )
                events.send(uiMessage(R.string.ui_message_diagnostics_copied))
            } catch (ex: Exception) {
                events.send(loggedUiErrorMessage(ex, R.string.ui_error_copy_diagnostics))
            }
        }
    }

    fun shareDiagnosticReport() {
        scope.launch {
            try {
                val report = report()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, appContext.getString(R.string.diagnostics_share_subject))
                    putExtra(Intent.EXTRA_TEXT, report)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(Intent.createChooser(intent, appContext.getString(R.string.diagnostics_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ex: Exception) {
                events.send(loggedUiErrorMessage(ex, R.string.ui_error_share_diagnostics))
            }
        }
    }

    // The scope is the view model's, on the main thread, and the report's reads can block; Refresh
    // already moves the same reads to IO.
    private suspend fun report(): String = withContext(Dispatchers.IO) { buildReport() }
}

private const val DIAGNOSTICS_READ_TIMEOUT_MS = 15_000L
