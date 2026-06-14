package com.opentasker.app

import android.app.Application
import android.util.Log
import androidx.room.Room
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.ui.theme.ThemeStore
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.DatabaseMigrations
import com.opentasker.core.storage.PendingRestoreApplyResult

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
        // Seed the black-yellow appearance defaults before any Compose code reads the theme.
        ThemeStore.init(this)
        registerActionMetadata()
        registerCoreRuntime()
         
        if (_db == null) {
            when (val restoreResult = DatabaseBackupManager.applyPendingRestoreIfPresent(this)) {
                is PendingRestoreApplyResult.Applied -> {
                    Log.i("OpenTasker", "Applied pending database restore from ${restoreResult.databaseFile.name}")
                }
                is PendingRestoreApplyResult.Failed -> {
                    Log.e("OpenTasker", "Pending database restore failed", restoreResult.exception)
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
    }
}
