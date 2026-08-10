package com.opentasker.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opentasker.core.registerCoreRuntime
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.theme.ThemeStore
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.DatabaseMigrations
import com.opentasker.core.storage.PendingRestoreApplyResult
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.diagnostics.CrashLogHandler
import com.opentasker.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.engine.RunLogPruneWorker
import com.opentasker.core.engine.EngineWatchdogWorker
import com.opentasker.core.engine.reconcileExecutionJournal
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

    /**
     * Initializes credential-protected runtime state after the user has unlocked the device.
     *
     * Upstream's Direct Boot split: before first unlock nothing here may run, because Room, the
     * secret store and every DataStore live in credential-protected storage. BootReceiver calls
     * back into this once ACTION_USER_UNLOCKED arrives.
     */
    fun initializeAfterUnlock() {
        if (!DirectBootTriggerStore.isUserUnlocked(this)) return
        synchronized(this) {
            if (unlockedInitialized) return

            CrashLogHandler.install(this)
            AdvancedProtectionReader.start(this)
            AppVisibilityTracker.register(this)
            ShizukuPowerBackend.initialize(this)
            // Seed the black-yellow appearance defaults before any Compose code reads the theme.
            ThemeStore.init(this)
            com.opentasker.core.icons.TaskIconStore.init(this)
            com.opentasker.core.bubbles.FreezeBubbleStore.init(this)
            com.opentasker.core.bubbles.FlashBubbleStore.init(this)
            com.opentasker.core.share.ShareRelayStore.init(this)
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

                // Plain, unencrypted Room: the fork does not take upstream's SQLCipher database (see
                // DatabaseSecurity's fork note), and it registers every migration manually, having
                // never used AutoMigration. Upstream's fresh-install Default-project seed is not
                // taken either — the fork's projects table has different columns and its projectId
                // is nullable, so an unfiled workspace is the fork's own valid starting state.
                _db = Room.databaseBuilder(
                    this,
                    AppDatabase::class.java,
                    DatabaseBackupManager.DATABASE_NAME,
                )
                    .addMigrations(*DatabaseMigrations.getAllMigrations())
                    .build()
            }
            // Warm the persistent-variable cache (super- and project-globals) before any task runs.
            com.opentasker.core.engine.variables.PersistentGlobalScope.init(requireNotNull(_db).variableDao())
            // Expose the running build as %APPVER so a task can flash it (catch stale installs).
            com.opentasker.core.engine.variables.PersistentGlobalScope.set(0L, "APPVER", com.opentasker.app.BuildConfig.VERSION_NAME)

            applicationScope.launch {
                runCatching {
                    val recovery = reconcileExecutionJournal(db)
                    if (recovery.interrupted > 0 || recovery.logsWritten > 0) {
                        AppLogger.warn(
                            "OpenTasker",
                            "Recovered ${recovery.interrupted} interrupted execution(s); " +
                                "wrote ${recovery.logsWritten} durable run-log record(s)",
                        )
                    }
                }.onFailure { error ->
                    AppLogger.error("OpenTasker", "Execution journal recovery failed", error)
                }
                runCatching {
                    VariableRepository(db.variableDao()).migrateLegacySensitiveVariables()
                }.onFailure { error ->
                    AppLogger.error("OpenTasker", "Legacy secret migration failed", error)
                }
            }

            RunLogPruneWorker.enqueue(this)
            com.opentasker.core.engine.BandPruneWorker.enqueue(this)
            EngineWatchdogWorker.enqueue(this)
            unlockedInitialized = true
        }
    }

    /**
     * In debug builds, surface accidental main-thread disk/network I/O and leaked closeables or
     * receivers/services to logcat (never crashes the app). Helps keep the automation engine's
     * work off the UI thread as the codebase evolves.
     */
    @Suppress("NewApi")
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        detectUnsafeIntentLaunch()
                    }
                    // Android 17 (API 37) still supplies implicit URI grants for a few
                    // Sharesheet/camera intents. Surface those call sites in debug before
                    // Android 18 removes the compatibility grant.
                    if (Build.VERSION.SDK_INT >= 37) {
                        detectImplicitUriPermissionGrant()
                    }
                }
                .penaltyLog()
                .build(),
        )
    }
}
