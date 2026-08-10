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
import com.opentasker.core.storage.DatabaseSecurity
import com.opentasker.core.storage.PendingRestoreApplyResult
import com.opentasker.core.storage.VariableRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.opentasker.core.diagnostics.CrashLogHandler
import com.opentasker.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.engine.RunLogPruneWorker
import com.opentasker.core.engine.EngineWatchdogWorker
import com.opentasker.core.engine.DirectBootTriggerStore
import com.opentasker.core.platform.AppVisibilityTracker
import com.opentasker.core.power.ShizukuPowerBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Application singleton keeps startup deterministic while Hilt is not active.
class OpenTaskerApp_NoHilt : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    @Volatile
    private var unlockedInitialized = false

    override fun onCreate() {
        super.onCreate()
        installStrictModeInDebug()
        if (DirectBootTriggerStore.isUserUnlocked(this)) {
            initializeAfterUnlock()
        }
    }

    /** Initializes credential-protected runtime state after the user has unlocked the device. */
    fun initializeAfterUnlock() {
        if (!DirectBootTriggerStore.isUserUnlocked(this)) return
        synchronized(this) {
            if (unlockedInitialized) return

            CrashLogHandler.install(this)
            AdvancedProtectionReader.start(this)
            AppVisibilityTracker.register(this)
            ShizukuPowerBackend.initialize(this)
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

                val databaseKey = DatabaseSecurity.prepareEncryptedDatabase(this, DatabaseBackupManager.DATABASE_NAME)
                _db = Room.databaseBuilder(
                    this,
                    AppDatabase::class.java,
                    DatabaseBackupManager.DATABASE_NAME,
                )
                    .addMigrations(*DatabaseMigrations.getManualMigrations())
                    .openHelperFactory(SupportOpenHelperFactory(databaseKey.copyOf()))
                    .build()
            }

            applicationScope.launch {
                runCatching {
                    VariableRepository(db.variableDao()).migrateLegacySensitiveVariables()
                }.onFailure { error ->
                    AppLogger.error("OpenTasker", "Legacy secret migration failed", error)
                }
            }

            RunLogPruneWorker.enqueue(this)
            EngineWatchdogWorker.enqueue(this)
            unlockedInitialized = true
        }
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
                .apply {
                    // Flags unsafe intent launches (the classic intent-redirection sink) in debug;
                    // available from Android 12 (API 31).
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        detectUnsafeIntentLaunch()
                    }
                }
                .penaltyLog()
                .build(),
        )
    }
}
