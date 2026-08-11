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
import com.opentasker.core.storage.ConfigurationSnapshotWorker
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Application singleton keeps startup deterministic while Hilt is not active.
class OpenTaskerApp_NoHilt : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        @Volatile
        private var _db: AppDatabase? = null
        private val databaseReady = CountDownLatch(1)

        /**
         * Wall clock at process start, used to tell journal rows this process wrote apart from
         * rows an earlier process left behind.
         */
        internal val processStartedAtMs: Long = System.currentTimeMillis()

        /**
         * Blocks until startup finishes preparing the database.
         *
         * Preparation (applying a staged restore, then a possible plaintext→SQLCipher migration)
         * is seconds of disk I/O and crypto on a large database, so it runs off the main thread —
         * `Application.onCreate` and the 10 s boot-broadcast budget must not pay for it. Callers
         * that genuinely need the database wait here instead; Room already forbids main-thread
         * queries, so that wait belongs on a worker.
         */
        val db: AppDatabase
            get() {
                _db?.let { return it }
                databaseReady.await(DATABASE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return _db ?: throw IllegalStateException("Database not initialized.")
            }

        private const val DATABASE_READY_TIMEOUT_SECONDS = 30L
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

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level == TRIM_MEMORY_COMPLETE) {
            ShizukuPowerBackend.shutdown()
        }
        super.onTrimMemory(level)
    }

    @Suppress("DEPRECATION")
    override fun onTerminate() {
        ShizukuPowerBackend.shutdown()
        super.onTerminate()
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

            applicationScope.launch {
                try {
                    if (_db == null) prepareDatabase()
                } finally {
                    databaseReady.countDown()
                }

                // Warm the persistent-variable cache (super- and project-globals) before any task
                // runs, and expose the running build as %APPVER so a task can flash it (catch stale
                // installs). Both moved inside this coroutine when upstream took database
                // preparation off the main thread: out in initializeAfterUnlock, _db is still null.
                com.opentasker.core.engine.variables.PersistentGlobalScope.init(requireNotNull(_db).variableDao())
                com.opentasker.core.engine.variables.PersistentGlobalScope.set(0L, "APPVER", com.opentasker.app.BuildConfig.VERSION_NAME)

                runCatching {
                    val recovery = reconcileExecutionJournal(db, processStartedAtMs = processStartedAtMs)
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
            ConfigurationSnapshotWorker.enqueueIfEnabled(this)
            // Upstream's opt-in release check polls SysAdminDoc/OpenTasker and compares its tag to
            // appVersionName. This fork ships its own build tail on top of that name, so upstream's
            // newest tag is never the newest 自由作業盤 — the check is not wired in here.
            com.opentasker.core.engine.BandPruneWorker.enqueue(this)
            EngineWatchdogWorker.enqueue(this)
            unlockedInitialized = true
        }
    }

    /** Applies any staged restore, migrates a legacy plaintext file, then publishes the database. */
    private fun prepareDatabase() {
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
        // DatabaseSecurity's fork note), and it registers every migration manually, having never
        // used AutoMigration. Upstream's fresh-install Default-project seed is not taken either —
        // the fork's projects table has different columns and its projectId is nullable, so an
        // unfiled workspace is the fork's own valid starting state.
        _db = Room.databaseBuilder(
            this,
            AppDatabase::class.java,
            DatabaseBackupManager.DATABASE_NAME,
        )
            .addMigrations(*DatabaseMigrations.getAllMigrations())
            .build()
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
