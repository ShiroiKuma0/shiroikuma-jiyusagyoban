package com.opentasker.app

import android.app.Application
import androidx.room.Room
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.theme.ThemeStore
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
        CrashLogHandler.install(this)
        // Seed the black-yellow appearance defaults before any Compose code reads the theme.
        ThemeStore.init(this)
        com.opentasker.core.icons.TaskIconStore.init(this)
        com.opentasker.core.bubbles.FreezeBubbleStore.init(this)
        com.opentasker.widget.TemplateStore.init(this)
        com.opentasker.core.storage.ListSortStore.init(this)
        com.opentasker.core.storage.RunLogSeenStore.init(this)
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
        // Warm the persistent-variable cache (super- and project-globals) before any task runs.
        com.opentasker.core.engine.variables.PersistentGlobalScope.init(requireNotNull(_db).variableDao())
        // Expose the running build as %APPVER so a task can flash it (catch stale installs).
        com.opentasker.core.engine.variables.PersistentGlobalScope.set(0L, "APPVER", com.opentasker.app.BuildConfig.VERSION_NAME)

        RunLogPruneWorker.enqueue(this)
    }
}
