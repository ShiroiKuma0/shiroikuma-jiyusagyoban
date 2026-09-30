package com.opentasker.ui.screens

import android.content.Context
import android.net.Uri
import com.opentasker.app.R
import com.opentasker.core.storage.ConfigurationSnapshotPolicy
import com.opentasker.core.storage.ConfigurationSnapshotSettings
import com.opentasker.core.storage.ConfigurationSnapshotWorker
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.RestoreCandidate
import com.opentasker.core.storage.configureConfigurationSnapshotDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A validated restore candidate awaiting an explicit Stage decision, plus whatever restore it
 * would replace, so the user is never silently overwriting an earlier staged restore.
 */
data class RestoreReviewState(
    val candidate: RestoreCandidate,
    val replacesPending: RestoreCandidate? = null,
)

/**
 * The Setup card's backup lane of [ActiveAutomationViewModel]: backup, export, restore review and
 * staging, rollback, and configuration snapshots. It runs on the view model's scope and reports
 * through the view model's message channel, so it lives and dies with the screen (A-353).
 */
internal class BackupController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val events: SendChannel<UiMessage>,
    private val databaseBackupManager: DatabaseBackupManager,
) {
    // Starts with a cheap placeholder; the real state (which enumerates the filesystem) is
    // loaded off the main thread in init and refreshed after each backup operation.
    private val _backupSetupState = MutableStateFlow(BackupSetupState(busy = false))
    val backupSetupState: StateFlow<BackupSetupState> = _backupSetupState.asStateFlow()

    init {
        scope.launch {
            ConfigurationSnapshotSettings(appContext).changes().collect {
                runCatching { refreshBackupSetupState(busy = false) }
            }
        }
    }

    fun createDatabaseBackup() {
        launchBackupOperation {
            databaseBackupManager.backup()
                .onSuccess { backup ->
                    events.send(uiMessage(R.string.ui_message_backup_created, backup.name))
                }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_backup)) }
        }
    }

    fun exportDatabaseBackup(uri: Uri) {
        launchBackupOperation {
            val backup = databaseBackupManager.backup().getOrElse {
                events.send(loggedUiErrorMessage(it, R.string.ui_error_backup))
                return@launchBackupOperation
            }
            databaseBackupManager.exportBackup(backup, uri)
                .onSuccess { events.send(uiMessage(R.string.ui_message_backup_exported, backup.name)) }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_backup_export)) }
        }
    }

    private val _restoreReview = MutableStateFlow<RestoreReviewState?>(null)
    val restoreReview: StateFlow<RestoreReviewState?> = _restoreReview.asStateFlow()

    /**
     * Validates and summarizes the selected database, then waits for an explicit Stage decision.
     * Nothing is staged here: selection used to replace the pending journal outright, so a user
     * could not inspect the candidate, tell it apart from an earlier staged restore, or back out.
     */
    fun importDatabaseBackup(uri: Uri) {
        launchBackupOperation {
            databaseBackupManager.inspectRestore(uri)
                .onSuccess { candidate ->
                    _restoreReview.value = RestoreReviewState(
                        candidate = candidate,
                        replacesPending = databaseBackupManager.pendingRestoreSummary(),
                    )
                }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_backup_import)) }
        }
    }

    fun confirmStageRestore() {
        launchBackupOperation {
            databaseBackupManager.stageInspectedRestore()
                .onSuccess {
                    _restoreReview.value = null
                    events.send(uiMessage(R.string.ui_message_restore_staged))
                }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_restore_stage)) }
        }
    }

    fun dismissRestoreReview() {
        scope.launch {
            withContext(Dispatchers.IO) { databaseBackupManager.discardInspectedRestore() }
            _restoreReview.value = null
        }
    }

    /** Opens the restore review on the database the last restore replaced (A-323). */
    fun reviewRestoreRollback() {
        launchBackupOperation {
            val rollback = withContext(Dispatchers.IO) { databaseBackupManager.lastRestoreRollback() }
                ?: return@launchBackupOperation events.send(uiMessage(R.string.ui_message_no_restore_rollback))
            databaseBackupManager.inspectManagedBackup(rollback.file)
                .onSuccess { candidate ->
                    _restoreReview.value = RestoreReviewState(candidate, databaseBackupManager.pendingRestoreSummary())
                }
                .onFailure { events.send(loggedUiErrorMessage(it, R.string.ui_error_backup_import)) }
        }
    }

    /** Removes only the validated pending journal; backups and the live database are untouched. */
    fun cancelPendingRestore() {
        launchBackupOperation {
            val cancelled = withContext(Dispatchers.IO) { databaseBackupManager.cancelPendingRestore() }
            events.send(uiMessage(if (cancelled) R.string.ui_message_restore_cancelled else R.string.ui_message_no_staged_restore))
        }
    }

    /**
     * Runs one backup, export, or restore-staging step with the Setup card held busy.
     *
     * Unlike the import/export lanes this one is deliberately **not** cancellable, and the Setup
     * card offers no Stop for it. Every local write stages into a temporary file, validates it, and
     * publishes it atomically, and the handler that removes the staged copy on failure catches
     * cancellation too, so a stopped write would gain nothing over a finished one.
     *
     * Two writes leave app-private storage: this lane's Export button and the scheduled snapshot
     * worker, both into a user-chosen SAF destination. Opening such a destination for writing
     * truncates it before the first byte, and a provider gives no way to put back what was there,
     * so stopping partway is strictly worse than finishing. The snapshot worker at least deletes
     * the archive it created; Export writes into a document the user picked and cannot.
     *
     * The defect this lane actually had was a UI that stayed busy forever when an operation ended
     * early. The `finally` below fixes that, and it refreshes under [NonCancellable] because a
     * cancelled coroutine cannot enter a plain `withContext`, which would have left the busy flag
     * set for exactly the case the `finally` exists to cover.
     *
     * See `docs/DECISIONS.md` and `BackupCancellationContractTest`.
     */
    private fun launchBackupOperation(block: suspend () -> Unit) {
        scope.launch {
            _backupSetupState.value = _backupSetupState.value.copy(busy = true)
            try {
                block()
            } finally {
                withContext(NonCancellable) { refreshBackupSetupState(busy = false) }
            }
        }
    }

    private suspend fun refreshBackupSetupState(busy: Boolean) {
        // Backup enumeration and pending-restore checks hit the filesystem; keep them off
        // the main thread (debug StrictMode flags them otherwise).
        val loaded = withContext(Dispatchers.IO) {
            val settings = ConfigurationSnapshotSettings(appContext)
            BackupSetupState(
                busy = busy,
                latestBackupName = databaseBackupManager.latestBackup()?.name,
                pendingRestore = databaseBackupManager.hasPendingRestore(),
                pendingRestoreSummary = databaseBackupManager.pendingRestoreSummary(),
                lastRestoreRollback = databaseBackupManager.lastRestoreRollback(),
                snapshotPolicy = settings.load(),
                snapshotStatus = settings.loadStatus(),
            )
        }
        _backupSetupState.value = loaded
    }

    /** Persists the snapshot schedule and brings the periodic worker in line with it. */
    fun updateSnapshotPolicy(policy: ConfigurationSnapshotPolicy) {
        launchBackupOperation {
            val saved = withContext(Dispatchers.IO) {
                val settings = ConfigurationSnapshotSettings(appContext)
                settings.save(policy)
                val stored = settings.load()
                ConfigurationSnapshotWorker.sync(appContext, stored)
                stored
            }
            events.send(
                if (saved.enabled) {
                    uiMessage(R.string.ui_message_snapshots_enabled, saved.maxSnapshots, saved.maxAgeDays)
                } else {
                    uiMessage(R.string.ui_message_snapshots_disabled)
                },
            )
        }
    }

    /** Persists the SAF grant and Keystore-wrapped passphrase before enabling the schedule. */
    fun updateSnapshotDestination(uri: Uri, passphrase: CharArray, enableSchedule: Boolean) {
        launchBackupOperation {
            try {
                runCatching {
                    withContext(Dispatchers.IO) {
                        configureConfigurationSnapshotDestination(appContext, uri, passphrase, enableSchedule)
                    }
                }.onSuccess { policy ->
                    events.send(
                        if (policy.enabled) {
                            uiMessage(R.string.ui_message_snapshots_enabled, policy.maxSnapshots, policy.maxAgeDays)
                        } else {
                            uiMessage(R.string.ui_message_snapshot_destination_saved)
                        },
                    )
                }.onFailure { error ->
                    withContext(Dispatchers.IO) {
                        ConfigurationSnapshotSettings(appContext).recordFailure(
                            System.currentTimeMillis(),
                            appContext.getString(R.string.setup_snapshots_destination_save_failed),
                        )
                    }
                    events.send(loggedUiErrorMessage(error, R.string.ui_error_snapshot_destination))
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }
}
