package com.opentasker.core.storage

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opentasker.core.logging.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Takes an encrypted off-device configuration snapshot when the user has opted in.
 *
 * The temporary managed backup inherits [DatabaseBackupManager]'s WAL checkpoint and schema
 * validation. Its portable copy is streamed into the selected SAF tree through the authenticated
 * v2 `.otbackup` format. Nothing here restores anything.
 */
class ConfigurationSnapshotWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        recordingOutcome(ScheduledWorkerId.CONFIGURATION_SNAPSHOT) { runWork() }

    private suspend fun runWork(): Result {
        val settings = ConfigurationSnapshotSettings(applicationContext)
        val policy = settings.load()
        if (!policy.enabled) return Result.success()

        val now = System.currentTimeMillis()
        var passphrase: CharArray? = null
        return try {
            val treeUri = policy.destinationTreeUri?.let(Uri::parse)
                ?: throw SnapshotDestinationUnavailableException(
                    "Choose a snapshot destination folder in Setup.",
                )
            val archiveStore = ConfigurationSnapshotArchiveStore(applicationContext)
            archiveStore.requirePersistedAccess(treeUri)
            passphrase = settings.loadRecoveryPassphrase()

            val manager = DatabaseBackupManager(applicationContext, AppDatabaseProvider.await())
            val managedBackup = manager.backup().getOrThrow()
            var internalRemoved = 0
            val inventory = try {
                val archiveName = configurationSnapshotArchiveName(now)
                val archiveUri = archiveStore.createArchive(treeUri, archiveName)
                try {
                    manager.exportEncryptedBackup(managedBackup, archiveUri, passphrase).getOrThrow()
                } catch (error: Throwable) {
                    runCatching { archiveStore.deleteArchive(treeUri, archiveUri) }
                    throw error
                }
                archiveStore.enforceRetention(treeUri, policy, now)
            } finally {
                internalRemoved = manager.pruneSnapshots(policy, now)
            }

            settings.recordSuccess(now, inventory.snapshotCount, inventory.storageBytes)
            AppLogger.info(
                TAG,
                "Encrypted configuration snapshot created; pruned ${inventory.removedCount} archive(s) " +
                    "and $internalRemoved internal backup(s)",
            )
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            settings.recordFailure(now, error.message)
            AppLogger.error(TAG, "Configuration snapshot failed", error)
            if (
                error is SnapshotDestinationUnavailableException ||
                error is SnapshotRecoveryPassphraseUnavailableException
            ) {
                Result.failure()
            } else {
                // A busy WAL or temporarily unavailable document provider should be retried rather
                // than silently skipping the whole interval.
                Result.retry()
            }
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    companion object {
        private const val TAG = "ConfigurationSnapshotWorker"
        internal const val WORK_NAME = "configuration_snapshot"
        private const val INTERVAL_HOURS = 12L

        /** Schedules or cancels the periodic snapshot to match [policy]. */
        fun sync(context: Context, policy: ConfigurationSnapshotPolicy) {
            val workManager = WorkManager.getInstance(context)
            if (!policy.enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ConfigurationSnapshotWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueIfEnabled(context: Context) {
            sync(context, ConfigurationSnapshotSettings(context).load())
        }
    }
}
