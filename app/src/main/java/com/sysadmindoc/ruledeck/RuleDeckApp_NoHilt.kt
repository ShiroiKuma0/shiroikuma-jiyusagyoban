package com.sysadmindoc.ruledeck

import android.app.Application
import android.os.Build
import android.os.StrictMode
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.contexts.CompanionDeviceAssociation
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.ui.theme.ThemeStore
import com.sysadmindoc.ruledeck.core.storage.AppDatabase
import com.sysadmindoc.ruledeck.core.text.countOf
import com.sysadmindoc.ruledeck.ui.screens.LibraryEditTimes
import com.sysadmindoc.ruledeck.widget.TaskShortcutHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.sysadmindoc.ruledeck.core.storage.DatabaseBackupManager
import com.sysadmindoc.ruledeck.core.storage.DatabaseMigrations
import com.sysadmindoc.ruledeck.core.storage.PendingRestoreApplyResult
import com.sysadmindoc.ruledeck.core.storage.VariableRepository
import com.sysadmindoc.ruledeck.core.diagnostics.CrashLogHandler
import com.sysadmindoc.ruledeck.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.engine.RunLogPruneWorker
import com.opentasker.core.engine.EngineWatchdogWorker
import com.sysadmindoc.ruledeck.core.engine.reconcileExecutionJournal
import com.sysadmindoc.ruledeck.core.engine.DirectBootTriggerStore
import com.sysadmindoc.ruledeck.core.engine.variables.BuiltInVariables
import com.sysadmindoc.ruledeck.core.platform.AppVisibilityTracker
import com.sysadmindoc.ruledeck.core.platform.builtInReading
import com.sysadmindoc.ruledeck.core.plugins.locale.LocaleConditionGrantStore
import com.sysadmindoc.ruledeck.core.power.ShizukuPowerBackend
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Application singleton keeps startup deterministic while Hilt is not active.
/** The steps of preparing the database that can take long enough to see. */
enum class StartupStage {
    Opening,
    Restoring,
    Encrypting,
}

class RuleDeckApp_NoHilt : Application() {
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

        /** The database if preparation has finished, without waiting. */
        val readyDb: AppDatabase?
            get() = _db

        private val _startupStage = MutableStateFlow(StartupStage.Opening)

        /** What preparation is doing, so the launch screen can say so instead of just spinning. */
        val startupStage: StateFlow<StartupStage> = _startupStage.asStateFlow()

        /**
         * Suspends until the database is ready, without blocking the caller's thread.
         *
         * UI must use this rather than [db]: the blocking getter waits up to 30 seconds, and the
         * launch that pays that cost is exactly the one after staging a restore - a full-file copy
         * of up to 100 MB plus a possible cipher migration - so composing against [db] parked the
         * main thread through the slowest start the app has, and threw if the deadline expired.
         */
        suspend fun awaitDb(): AppDatabase {
            _db?.let { return it }
            return withContext(Dispatchers.IO) { db }
        }

        private const val DATABASE_READY_TIMEOUT_SECONDS = 30L
    }

    @Volatile
    private var unlockedInitialized = false

    /** A restore replaced the database at this start; set before the database is published. */
    @Volatile
    private var restoredAtStart = false

    override fun onCreate() {
        super.onCreate()
        // The app's own language (UI page → App language), before anything draws or builds a text —
        // in every process, the player's included.
        com.sysadmindoc.ruledeck.i18n.AppLanguage.install(this)
        // Whether root may stand in for Shizuku on this phone — asked from places that hold no Context.
        com.sysadmindoc.ruledeck.core.shizuku.RootAccess.init(this)
        // 言語島's player runs in a process of its own (`:gengoshima`) so a player fault cannot
        // take the automation engine down with it. Everything below belongs to the MAIN process
        // only: run in the player's, it would open a second Room instance, reconcile the execution
        // journal against rows the running engine is still writing — marking live tasks as
        // interrupted — and schedule the workers twice. The player needs none of it.
        if (isSideProcess()) return
        // A recorder dies with its process, so a "recording" notification still up now is a lie.
        com.opentasker.core.actions.RecordingNotification.clear(this)
        installStrictModeInDebug()
        // Reading these needs no unlocked storage, so a task that runs before unlock gets them too.
        BuiltInVariables.installDevice { name -> builtInReading(this, name) }
        if (DirectBootTriggerStore.isUserUnlocked(this)) {
            initializeAfterUnlock()
        }
    }

    /** True in any process but the app's main one — today only 言語島's player (`:gengoshima`). */
    private fun isSideProcess(): Boolean {
        val name = if (android.os.Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrNull()
        }
        return name != null && name.contains(':')
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
            com.sysadmindoc.ruledeck.core.icons.TaskIconStore.init(this)
            com.sysadmindoc.ruledeck.core.bubbles.FreezeBubbleStore.init(this)
            // One line naming the device-policy powers this app actually holds. Freezing and thawing
            // both depend on a delegation that lives in another app and is invisible to dumpsys on
            // this phone, so without this the difference between "revoked" and "broken" is unreadable.
            com.sysadmindoc.ruledeck.core.policy.DevicePolicyBridge.logScopes(this)
            com.sysadmindoc.ruledeck.core.bubbles.FlashBubbleStore.init(this)
            com.sysadmindoc.ruledeck.core.share.ShareRelayStore.init(this)
            com.sysadmindoc.ruledeck.widget.TemplateStore.init(this)
            com.sysadmindoc.ruledeck.core.storage.ListSortStore.init(this)
            com.sysadmindoc.ruledeck.core.storage.RunLogSeenStore.init(this)
            registerActionMetadata()
            registerCoreRuntime()

            applicationScope.launch {
                try {
                    if (_db == null) prepareDatabase()
                } finally {
                    databaseReady.countDown()
                }
                // Started once the database is ready rather than waiting on it, which gives up after
                // 30 seconds; a restore with a cipher migration can take longer.
                launch { keepTaskShortcutsCurrent() }

                // Warm the persistent-variable cache (super- and project-globals) before any task
                // runs, and expose the running build as %APPVER so a task can flash it (catch stale
                // installs). Both moved inside this coroutine when upstream took database
                // preparation off the main thread: out in initializeAfterUnlock, _db is still null.
                // The protection marks first: nothing may be written before they are known.
                com.sysadmindoc.ruledeck.core.engine.variables.VariableGuard.init(this@RuleDeckApp_NoHilt)
                com.sysadmindoc.ruledeck.core.engine.variables.PersistentGlobalScope.init(requireNotNull(_db).variableDao())
                com.sysadmindoc.ruledeck.core.engine.variables.PersistentGlobalScope.set(0L, "APPVER", com.sysadmindoc.ruledeck.BuildConfig.VERSION_NAME)

                runCatching {
                    val recovery = reconcileExecutionJournal(db, processStartedAtMs = processStartedAtMs)
                    if (recovery.interrupted > 0 || recovery.logsWritten > 0) {
                        AppLogger.warn(
                            "RuleDeck",
                            "Recovered ${countOf(recovery.interrupted, "interrupted execution")}; " +
                                "wrote ${countOf(recovery.logsWritten, "durable run-log record")}",
                        )
                    }
                }.onFailure { error ->
                    AppLogger.error("RuleDeck", "Execution journal recovery failed", error)
                }
                runCatching {
                    VariableRepository(db.variableDao()).migrateLegacySensitiveVariables()
                }.onFailure { error ->
                    AppLogger.error("RuleDeck", "Legacy secret migration failed", error)
                }
            }
            // Devices paired before presence was watched (or by an older version) get watched too.
            applicationScope.launch {
                runCatching {
                    CompanionDeviceAssociation.observePresence(this@RuleDeckApp_NoHilt, CompanionDeviceAssociation.list(this@RuleDeckApp_NoHilt))
                }.onFailure { error ->
                    AppLogger.error("RuleDeck", "Watching paired devices failed", error)
                }
            }

            RunLogPruneWorker.enqueue(this)
            // Upstream's scheduled configuration snapshots are not wired in: this fork removed the
            // UI that sets the policy, so enqueueIfEnabled could only ever read a disabled policy
            // and cancel work that was never scheduled. See the note in PermissionOnboardingScreen.
            // Upstream's opt-in release check polls SysAdminDoc/RuleDeck and compares its tag to
            // appVersionName. This fork ships its own build tail on top of that name, so upstream's
            // newest tag is never the newest 自由作業盤 — the check is not wired in here.
            // The Hume band is retired and its rows are now history the 健康 report keeps showing, so the
            // 400-day prune would start eating it (from August 2027). Cancelled, never enqueued again.
            com.opentasker.core.engine.BandPruneWorker.cancel(this)
            EngineWatchdogWorker.enqueue(this)
            unlockedInitialized = true
        }
    }

    /**
     * Shortcuts hold a task id and the name it had. Every rename, delete, undo, import and restore
     * changes the task list, so following it keeps them all current for the life of the process.
     */
    private suspend fun keepTaskShortcutsCurrent() {
        val database = readyDb ?: return // Preparing it failed, and that's logged.
        val watcher = TaskShortcutHelper.Watcher(afterRestore = restoredAtStart) { names, state ->
            TaskShortcutHelper.reconcile(this, names, state)
        }
        while (true) {
            try {
                watcher.watch(
                    database.taskDao().observeNames()
                        .map { rows -> rows.associate { it.id to it.name } }
                        .distinctUntilChanged(),
                )
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.error("RuleDeck", "Keeping task shortcuts current stopped; trying again in a minute", error)
                delay(60_000L)
            }
        }
    }

    /** Applies any staged restore, migrates a legacy plaintext file, then publishes the database. */
    private fun prepareDatabase() {
        if (DatabaseBackupManager.pendingRestoreFile(this).exists()) _startupStage.value = StartupStage.Restoring
        DatabaseBackupManager.discardLeftoverRestoreCandidate(this)
        // Plugin condition grants name profile positions in the database they were given in, and the
        // Last edited times name item ids, which the restored items may reuse. A restore that
        // replaces the database drops both. Only the grants keep the restore's journal until they're
        // gone from disk: a stale time just dates an item wrongly, and holding the journal for it
        // would clear grants the user gave again after the restore on every later start.
        val restoreResult = DatabaseBackupManager.applyPendingRestoreIfPresent(this) {
            runCatching { runBlocking { LibraryEditTimes.clear(this@RuleDeckApp_NoHilt) } }
                .onFailure { AppLogger.warn("RuleDeck", "Could not clear the Last edited times a restore left; some items may show an old time", it) }
            LocaleConditionGrantStore(this).revokeAllContextGrants().also { cleared ->
                if (!cleared) AppLogger.warn("RuleDeck", "Could not clear the plugin condition grants a restore left; the next start tries again")
            }
        }
        restoredAtStart = restoreResult is PendingRestoreApplyResult.Applied ||
            restoreResult == PendingRestoreApplyResult.StoppedAfterSwap
        when (restoreResult) {
            is PendingRestoreApplyResult.Applied -> {
                AppLogger.info(
                    "RuleDeck",
                    "Applied pending database restore to ${restoreResult.databaseFile.name}; " +
                        "the database it replaced is kept as ${restoreResult.previousBackup?.name ?: "nothing (none existed or it failed validation)"}",
                )
            }
            is PendingRestoreApplyResult.Failed -> {
                AppLogger.error("RuleDeck", "Pending database restore failed", restoreResult.exception)
            }
            PendingRestoreApplyResult.StoppedAfterSwap -> {
                AppLogger.warn("RuleDeck", "A database restore stopped after its database went in; the plugin condition grants and Last edited times it left were due to be cleared this start")
            }
            PendingRestoreApplyResult.NoPending -> Unit
        }
        _startupStage.value = StartupStage.Opening

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
