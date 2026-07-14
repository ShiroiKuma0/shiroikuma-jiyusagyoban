package com.opentasker.app

import android.app.Application
import android.os.StrictMode
import androidx.room.Room
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.DatabaseMigrations
import com.opentasker.core.storage.PendingRestoreApplyResult
import com.opentasker.core.diagnostics.CrashLogHandler
import com.opentasker.core.engine.RunLogPruneWorker

// Application singleton keeps startup deterministic while Hilt is not active.
class OpenTaskerApp_NoHilt : Application() {
    companion object {
        private var _db: AppDatabase? = null
        
        val db: AppDatabase
            get() {
                if (_db == null) {
                    throw IllegalStateException("Database not initialized.")
                }
                return requireNotNull(_db)
            }
    }

    override fun onCreate() {
        super.onCreate()
        installStrictModeInDebug()
        CrashLogHandler.install(this)
        registerActionMetadata()
        registerCoreRuntime()
         
        if (_db == null) {
            when (val restoreResult = DatabaseBackupManager.applyPendingRestoreIfPresent(this)) {
                is PendingRestoreApplyResult.Applied -> {
                    AppLogger.info("OpenTasker", "Applied pending database restore from ${restoreResult.databaseFile.name}")
                }
                is PendingRestoreApplyResult.Failed -> {
                    AppLogger.error("OpenTasker", "Pending database restore failed", restoreResult.exception)
                }
                PendingRestoreApplyResult.NoPending -> Unit
            }

            _db = Room.databaseBuilder(
                this,
                AppDatabase::class.java,
                "opentasker.db"
            )
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                .build()
        }

        RunLogPruneWorker.enqueue(this)
    }

    /**
     * In debug builds, surface accidental main-thread disk/network I/O and leaked closeables or
     * receivers/services to logcat (never crashes the app). Helps keep the automation engine's
     * work off the UI thread as the codebase evolves.
     */
    private fun installStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }
}
