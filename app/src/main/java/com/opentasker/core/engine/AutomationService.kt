package com.opentasker.core.engine

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.MainActivity
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.automation.app.AppUsageMonitor
import com.opentasker.automation.network.ConnectivityMonitor
import com.opentasker.automation.network.WiFiNetworkMonitor
import com.opentasker.automation.sensor.OrientationDetector
import com.opentasker.automation.sensor.ShakeDetector
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.contexts.BluetoothContextEvents
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.CameraMicContextEvents
import com.opentasker.core.contexts.PackageContextEvents
import com.opentasker.core.contexts.PluginConditionSubscription
import com.opentasker.core.contexts.PluginConditionSubscriptions
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AutoStartSettings
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.minimumTimestamp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Collections

/**
 * Foreground service that hosts the trigger engine.
 *
 * Subscribes to context sources, evaluates active profiles, and dispatches tasks.
 * A foreground service is required on modern Android (Doze/App Standby) for the
 * automation engine to evaluate triggers reliably.
 */
class AutomationService : Service() {
    // SupervisorJob: a failing child coroutine (a matcher, the reload collector, a prune) must NOT cancel
    // the parent and take down every engine coroutine with it — that froze the clock while the process
    // stayed alive. Children now fail in isolation; the heartbeat re-arms anything that does die.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val db by lazy { OpenTaskerApp_NoHilt.db }
    private val timeEventScheduler by lazy { TimeEventScheduler(this) }
    private val wifiNetworkMonitor by lazy { WiFiNetworkMonitor(this) }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val appUsageMonitor by lazy { AppUsageMonitor(this) }
    private val shakeDetector by lazy { ShakeDetector(this) }
    private val orientationDetector by lazy { OrientationDetector(this) }
    private val runLogRetentionSettings by lazy { RunLogRetentionSettings(this) }
    
    private val cooldownStore by lazy { CooldownStore(this) }
    private val matchers = Collections.synchronizedMap(mutableMapOf<Long, ProfileMatcher>())
    private val profileCooldowns = Collections.synchronizedMap(mutableMapOf<Long, Long>()) // profileId -> cooldownUntilMs
    private val matcherJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>()) // Track jobs for cleanup
    private val profileTaskJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>())
    // Each queued run carries its own event snapshot, so a burst of different-source events (e.g.
    // notifications from different apps) each runs with ITS values, not the latest one's.
    private data class QueuedRun(val task: Task, val eventVars: Map<String, String>)
    private val queuedProfileTasks = Collections.synchronizedMap(mutableMapOf<Long, ArrayDeque<QueuedRun>>())
    private var lastRunLogPruneAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStartDone = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        acquireWakeLock()
        timeEventScheduler.scheduleNextMinute()
        wifiNetworkMonitor.start()
        connectivityMonitor.start()
        appUsageMonitor.start(scope)
        shakeDetector.start()
        orientationDetector.start()
        ContextCompat.registerReceiver(
            this,
            PackageContextEvents.receiver,
            PackageContextEvents.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            BluetoothContextEvents.receiver,
            BluetoothContextEvents.intentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        CameraMicContextEvents.start(this)
        profileCooldowns.putAll(cooldownStore.loadAll())
        scope.launch { pruneRunLogs(force = true) }
        // Re-arm matchers (and dynamic receivers like the broadcast trigger) whenever profiles change,
        // so enabling/importing a profile takes effect without relaunching the app. drop(1) skips the
        // initial emission — onStartCommand does the first load.
        scope.launch {
            db.profileDao().getAllAsFlow().drop(1).collect { reloadProfiles() }
        }
        isRunning = true
        EngineHeartbeat.markEngineStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bootCompletedTrigger = intent?.action == ACTION_BOOT_COMPLETED_TRIGGER
        val rearm = intent?.action == ACTION_REARM
        scope.launch {
            if (rearm) {
                // The per-minute alarm found the tick stale (engine coroutines died while the process
                // lived). Re-arm the matchers, which relaunches the tick loop.
                if (EngineHeartbeat.isStale()) {
                    EngineHeartbeat.markRearm()
                    reloadProfiles()
                }
            } else {
                reloadProfiles()
                if (!autoStartDone) {
                    autoStartDone = true
                    runAutoStartTasks()
                }
                if (bootCompletedTrigger) {
                    BootContextEvents.publishBootCompleted()
                }
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warn(TAG, "Foreground service timeout (startId=$startId, fgsType=$fgsType); stopping cleanly")
        scope.launch {
            val entry = com.opentasker.core.storage.RunLogEntity(
                taskId = 0,
                taskName = "AutomationService",
                timestamp = System.currentTimeMillis(),
                durationMs = 0,
                success = false,
                message = "Service stopped by system foreground-service timeout",
                source = "system",
                sourceLabel = "FGS timeout",
            )
            runCatching { db.runLogDao().insert(entry) }
        }
        stopSelf(startId)
    }

    override fun onDestroy() {
        val matcherJobSnapshot = matcherJobs.values.toList()
        val taskJobSnapshot = profileTaskJobs.values.toList()
        matcherJobSnapshot.forEach { it.cancel() }
        taskJobSnapshot.forEach { it.cancel() }
        matcherJobs.clear()
        matchers.clear()
        profileCooldowns.clear()
        profileTaskJobs.clear()
        queuedProfileTasks.clear()
        timeEventScheduler.cancel()
        wifiNetworkMonitor.stop()
        connectivityMonitor.stop()
        appUsageMonitor.stop()
        shakeDetector.stop()
        orientationDetector.stop()
        runCatching { unregisterReceiver(PackageContextEvents.receiver) }
        runCatching { unregisterReceiver(BluetoothContextEvents.receiver) }
        CameraMicContextEvents.stop(this)
        PluginConditionSubscriptions.clear()
        BroadcastContextEvents.stop(this)
        releaseWakeLock()
        isRunning = false
        job.cancel()
        super.onDestroy()
    }

    private suspend fun reloadProfiles() {
        val oldJobs = matcherJobs.values.toList()
        matcherJobs.clear()
        matchers.clear()
        oldJobs.forEach { it.cancel() }

        val profiles = db.profileDao().getAllEnabled()
        val activeIds = profiles.map { it.id }.toSet()
        cooldownStore.pruneDeleted(activeIds)
        synchronized(queuedProfileTasks) {
            queuedProfileTasks.keys.removeAll { it !in activeIds }
        }
        val domains = profiles.map { it.toDomain() }
        registerPluginSubscriptions(domains)
        // Keep the broadcast (Intent Received) receiver listening for exactly the actions in use.
        val broadcastActions = domains
            .flatMap { it.contexts }
            .filter { it.type == ContextType.EVENT && it.config["event"]?.trim().equals("broadcast", ignoreCase = true) }
            .flatMap { (it.config["action"] ?: it.config["actions"] ?: "").split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        BroadcastContextEvents.setActions(this, broadcastActions)
        for (domain in domains) {
            val matcher = ProfileMatcher(this, domain)

            val matcherJob = scope.launch {
                // Self-healing: if a matcher's flow ever errors, re-collect after a short backoff
                // instead of dying permanently. A dead matcher used to freeze the clock/battery tick and
                // the 電池線 charging state for hours; now it recovers within seconds, independent of the
                // alarm re-arm. (A clean completion — e.g. a profile with no contexts — just stops.)
                while (isActive) {
                    val errored = try {
                        matcher.stateChanges().collect { change ->
                            try {
                                when (change) {
                                    is ProfileStateChange.Activated -> onProfileActivated(domain, change.vars)
                                    is ProfileStateChange.Deactivated -> onProfileDeactivated(domain)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                android.util.Log.e("OpenTasker", "Failed handling state change for ${domain.name}", e)
                            }
                        }
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("OpenTasker", "Profile matcher errored for ${domain.name}; restarting", e)
                        EngineHeartbeat.markMatcherRestart(domain.name)
                        true
                    }
                    if (!errored) break
                    if (isActive) delay(3_000)
                }
            }
            
            matcherJobs[domain.id] = matcherJob
            matchers[domain.id] = matcher
        }
    }

    private fun registerPluginSubscriptions(profiles: List<Profile>) {
        val subs = profiles.flatMap { profile ->
            profile.contexts
                .filter { it.type == com.opentasker.core.model.ContextType.PLUGIN }
                .mapNotNull { spec ->
                    val pkg = spec.config["package"]?.trim().orEmpty()
                    if (pkg.isBlank()) return@mapNotNull null
                    PluginConditionSubscription(
                        packageName = pkg,
                        bundleJson = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" },
                        timeoutMs = spec.config["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000) ?: 5_000,
                    )
                }
        }.toSet()
        PluginConditionSubscriptions.replaceAll(subs)
    }

    /**
     * Resolve a profile's task by NAME first (survives bundle re-imports that re-id tasks), with the id
     * as the fallback. A rename leaves the stored name stale → the name misses → the id fallback still
     * works; a re-import re-ids the task → the id is stale → the name still resolves it.
     */
    private suspend fun resolveTask(name: String, fallbackId: Long): com.opentasker.core.model.Task? {
        val entity = (if (name.isNotBlank()) db.taskDao().getByName(name) else null)
            ?: (if (fallbackId > 0) db.taskDao().getById(fallbackId) else null)
        return entity?.toDomain()
    }

    private suspend fun onProfileActivated(profile: com.opentasker.core.model.Profile, eventVars: Map<String, String>) {
        if (profile.enterTaskName.isBlank() && profile.enterTaskId <= 0) return
        val domain = resolveTask(profile.enterTaskName, profile.enterTaskId)
        if (domain == null) {
            android.util.Log.w("OpenTasker", "Enter task not found for profile ${profile.name} (name='${profile.enterTaskName}', id=${profile.enterTaskId})")
            return
        }
        dispatchTask(profile, domain, eventVars)
    }

    private suspend fun onProfileDeactivated(profile: com.opentasker.core.model.Profile) {
        if (profile.exitTaskName.isBlank() && (profile.exitTaskId == null || profile.exitTaskId <= 0)) return
        val domain = resolveTask(profile.exitTaskName, profile.exitTaskId ?: 0L)
        if (domain == null) {
            android.util.Log.w("OpenTasker", "Exit task not found for profile ${profile.name} (name='${profile.exitTaskName}', id=${profile.exitTaskId})")
            return
        }
        dispatchTask(profile, domain, emptyMap())
    }

    private fun dispatchTask(
        profile: Profile,
        task: Task,
        eventVars: Map<String, String>,
    ) {
        when (profile.automationMode) {
            AutomationMode.SINGLE -> {
                if (profileTaskJobs[profile.id]?.isActive == true) {
                    AppLogger.info(TAG, "Profile ${profile.id} already running; SINGLE mode skipped retrigger")
                    logProfileSkippedRun(profile, task, "Profile is already running in SINGLE mode.")
                    return
                }
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    logCooldownSkip(profile, task, reservation.remainingMs)
                    return
                }
                profileTaskJobs[profile.id] = launchTrackedTask(profile, task, eventVars)
            }

            AutomationMode.RESTART -> {
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    logCooldownSkip(profile, task, reservation.remainingMs)
                    return
                }
                profileTaskJobs[profile.id]?.cancel()
                profileTaskJobs[profile.id] = launchTrackedTask(profile, task, eventVars)
            }

            AutomationMode.QUEUED -> {
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    logCooldownSkip(profile, task, reservation.remainingMs)
                    return
                }
                if (profileTaskJobs[profile.id]?.isActive == true) {
                    synchronized(queuedProfileTasks) {
                        val queue = queuedProfileTasks.getOrPut(profile.id) { ArrayDeque() }
                        if (queue.size >= MAX_QUEUED_TASKS) {
                            AppLogger.warn(TAG, "Profile ${profile.id} queue full ($MAX_QUEUED_TASKS), dropping retrigger")
                            logProfileSkippedRun(profile, task, "Task queue is full ($MAX_QUEUED_TASKS pending).")
                            return
                        }
                        queue.add(QueuedRun(task, eventVars))
                    }
                    AppLogger.info(TAG, "Profile ${profile.id} queued retrigger")
                    return
                }
                profileTaskJobs[profile.id] = launchQueuedTasks(profile, task, eventVars)
            }

            AutomationMode.PARALLEL -> {
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    logCooldownSkip(profile, task, reservation.remainingMs)
                    return
                }
                scope.launch { runTask(task, profile, eventVars) }
            }
        }
    }

    private fun launchTrackedTask(profile: Profile, task: Task, eventVars: Map<String, String>): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                runTask(task, profile, eventVars)
            } finally {
                synchronized(profileTaskJobs) {
                    if (profileTaskJobs[profile.id] == thisJob) {
                        profileTaskJobs.remove(profile.id)
                    }
                }
            }
        }

    private fun launchQueuedTasks(profile: Profile, firstTask: Task, firstEventVars: Map<String, String>): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            var nextRun: QueuedRun? = QueuedRun(firstTask, firstEventVars)
            try {
                while (isActive && nextRun != null) {
                    val run = requireNotNull(nextRun)
                    runTask(run.task, profile, run.eventVars)
                    nextRun = synchronized(queuedProfileTasks) {
                        queuedProfileTasks[profile.id]?.poll()?.also {
                            if (queuedProfileTasks[profile.id]?.isEmpty() == true) {
                                queuedProfileTasks.remove(profile.id)
                            }
                        }
                    }
                }
            } finally {
                synchronized(profileTaskJobs) {
                    if (profileTaskJobs[profile.id] == thisJob) {
                        profileTaskJobs.remove(profile.id)
                    }
                }
            }
        }

    private suspend fun runTask(
        task: Task,
        profile: Profile,
        eventVars: Map<String, String> = emptyMap(),
    ) {
        val result = executeAndLogTask(
            appContext = this,
            db = db,
            task = task,
            source = "Profile: ${profile.name}",
            metadata = profileRunMetadata(profile),
            eventLocals = eventVars,
        )
        if (result.logInserted) {
            pruneRunLogs(force = false)
        }
    }

    private fun logCooldownSkip(profile: Profile, task: Task, remainingMs: Long) {
        logProfileSkippedRun(profile, task, "Cooldown active for ${formatRemainingCooldown(remainingMs)}.")
    }

    private fun logProfileSkippedRun(profile: Profile, task: Task, reason: String) {
        scope.launch {
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = "Profile: ${profile.name}",
                reason = reason,
                metadata = profileRunMetadata(profile),
            )
            if (inserted) pruneRunLogs(force = false)
        }
    }

    private suspend fun pruneRunLogs(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunLogPruneAt < RUN_LOG_PRUNE_INTERVAL_MS) return

        val policy = runLogRetentionSettings.load()
        runCatching {
            db.runLogDao().pruneRetention(
                maxEntries = policy.maxEntries,
                minimumTimestamp = policy.minimumTimestamp(now),
            )
        }
            .onSuccess { deleted ->
                lastRunLogPruneAt = now
                if (deleted > 0) {
                    AppLogger.info(TAG, "Pruned $deleted old run log entries")
                }
            }
            .onFailure { error ->
                AppLogger.error(TAG, "Failed to prune run logs", error)
            }
    }

    private fun reserveCooldown(profileId: Long, cooldownSec: Int): CooldownReservation {
        val now = System.currentTimeMillis()
        synchronized(profileCooldowns) {
            val cooldownUntil = profileCooldowns[profileId] ?: 0
            if (now < cooldownUntil) {
                AppLogger.info(TAG, "Profile $profileId on cooldown, skipping")
                return CooldownReservation(accepted = false, remainingMs = cooldownUntil - now)
            }
            if (cooldownSec > 0) {
                val deadline = now + (cooldownSec * 1000L)
                profileCooldowns[profileId] = deadline
                cooldownStore.set(profileId, deadline)
            }
            return CooldownReservation(accepted = true)
        }
    }

    private fun profileRunMetadata(profile: Profile): List<String> = buildList {
        add("Profile ID: ${profile.id}")
        add("Mode: ${profile.automationMode.name.lowercase()}")
        if (profile.cooldownSec > 0) add("Cooldown: ${profile.cooldownSec}s")
    }

    private fun formatRemainingCooldown(remainingMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs).coerceAtLeast(1)
        return if (seconds == 1L) "1 second" else "$seconds seconds"
    }

    /** Run the user's configured auto-start tasks once per process (after a fresh start / resurrect),
     *  so overlays and state come back without manually running the master "起動" task. */
    private fun runAutoStartTasks() {
        scope.launch {
            delay(2_000) // let the engine + context sources settle before re-establishing state
            for (id in AutoStartSettings.taskIds(this@AutomationService)) {
                val task = db.taskDao().getById(id)?.toDomain() ?: continue
                runCatching { executeAndLogTask(this@AutomationService, db, task, source = "Auto-start") }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        // EMUI freezes/reaps even a foreground service under Doze/standby, which tears down overlays
        // (the battery line) and stalls the per-minute clock pulse. A held partial wakelock keeps the
        // process running so triggers and widget refreshes keep firing with the screen off.
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "白い熊 自由作業盤 engine", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(channel)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("白い熊 自由作業盤 is running")
            .setContentText("Tap to open automation status")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, foregroundServiceTypes())
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasBackgroundLocationForegroundServicePrerequisites()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasBackgroundLocationForegroundServicePrerequisites(): Boolean {
        if (!hasAnyLocationPermission()) return false
        if (Build.VERSION.SDK_INT >= 29 && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return false
        val locationManager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            locationManager.isLocationEnabled
        } else {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
    }

    private fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AutomationService"
        const val ACTION_BOOT_COMPLETED_TRIGGER = "com.opentasker.action.BOOT_COMPLETED_TRIGGER"
        const val ACTION_REARM = "com.opentasker.action.ENGINE_REARM"
        private const val CHANNEL = "opentasker.engine"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUED_TASKS = 50
        private const val WAKELOCK_TAG = "shiroikuma_jiyusagyoban:engine"

        /** True while the engine service is alive in this process — lets the per-minute tick resurrect it if EMUI reaped it. */
        @Volatile
        var isRunning = false
            private set

        /** (Re)start the foreground engine service. Safe to call when it is already running. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutomationService::class.java))
        }

        /** Ask a running engine to re-arm its matchers — used by the heartbeat when the tick went stale. */
        fun rearm(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationService::class.java).setAction(ACTION_REARM),
            )
        }
        private val RUN_LOG_PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}

private data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
)
