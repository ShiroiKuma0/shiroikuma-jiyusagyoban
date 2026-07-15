package com.opentasker.core.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.MainActivity
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.automation.app.AppUsageMonitor
import com.opentasker.automation.network.ConnectivityMonitor
import com.opentasker.automation.network.WiFiNetworkMonitor
import com.opentasker.automation.sensor.ShakeDetector
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.contexts.BluetoothContextEvents
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.contexts.CameraMicContextEvents
import com.opentasker.core.contexts.PackageContextEvents
import com.opentasker.core.contexts.PluginConditionSubscription
import com.opentasker.core.contexts.PluginConditionSubscriptions
import com.opentasker.core.contexts.TimeContextEvents
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.platform.AudioForegroundServiceEligibility
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.minimumTimestamp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.opentasker.core.storage.ProfileEntity
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
    private val job = Job()
    // Engine orchestration (context matching, dispatch) runs off the main thread. Room suspend DAOs
    // dispatch to Room's own executor, and task execution hops to Dispatchers.IO inside
    // executeAndLogTask, so no automation work blocks the UI thread.
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val db by lazy { OpenTaskerApp_NoHilt.db }
    private val timeEventScheduler by lazy { TimeEventScheduler(this) }
    private val wifiNetworkMonitor by lazy { WiFiNetworkMonitor(this) }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val appUsageMonitor by lazy { AppUsageMonitor(this) }
    private val shakeDetector by lazy { ShakeDetector(this) }
    private val runLogRetentionSettings by lazy { RunLogRetentionSettings(this) }
    private val engineHeartbeatStore by lazy { EngineHeartbeatStore(this) }
    private val contextMonitorLifecycle by lazy {
        ContextMonitorLifecycle(
            mapOf(
                ContextMonitor.WIFI to ContextMonitorHandle(
                    start = { wifiNetworkMonitor.start() },
                    stop = { wifiNetworkMonitor.stop() },
                ),
                ContextMonitor.CONNECTIVITY to ContextMonitorHandle(
                    start = { connectivityMonitor.start() },
                    stop = { connectivityMonitor.stop() },
                ),
                ContextMonitor.APP_USAGE to ContextMonitorHandle(
                    start = { appUsageMonitor.start(scope) },
                    stop = { appUsageMonitor.stop() },
                ),
                ContextMonitor.SHAKE to ContextMonitorHandle(
                    start = { shakeDetector.start() },
                    stop = { shakeDetector.stop() },
                ),
                ContextMonitor.CAMERA_MIC to ContextMonitorHandle(
                    start = { CameraMicContextEvents.start(this) },
                    stop = { CameraMicContextEvents.stop(this) },
                ),
                ContextMonitor.PACKAGE_EVENTS to ContextMonitorHandle(
                    start = {
                        ContextCompat.registerReceiver(
                            this,
                            PackageContextEvents.receiver,
                            PackageContextEvents.intentFilter(),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        true
                    },
                    stop = { unregisterReceiver(PackageContextEvents.receiver) },
                ),
                ContextMonitor.BLUETOOTH_EVENTS to ContextMonitorHandle(
                    start = {
                        ContextCompat.registerReceiver(
                            this,
                            BluetoothContextEvents.receiver,
                            BluetoothContextEvents.intentFilter(),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        true
                    },
                    stop = { unregisterReceiver(BluetoothContextEvents.receiver) },
                ),
            ),
        )
    }
    
    private val cooldownStore by lazy { CooldownStore(this) }
    private val matchers = Collections.synchronizedMap(mutableMapOf<Long, ProfileMatcher>())
    private val profileCooldowns = Collections.synchronizedMap(mutableMapOf<Long, Long>()) // profileId -> cooldownUntilMs
    private val matcherJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>()) // Track jobs for cleanup
    private val profileTaskJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>())
    private val queuedProfileTasks = Collections.synchronizedMap(mutableMapOf<Long, ArrayDeque<Task>>())
    private val profileReloadMutex = Mutex()
    private var lastRunLogPruneAt = 0L
    private var audioForegroundServiceEligibility = AudioForegroundServiceEligibility.BACKGROUND_STARTED
    @Volatile private var engineLoaded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        timeEventScheduler.scheduleNextMinute()
        engineHeartbeatStore.recordAlive()
        scope.launch {
            while (isActive) {
                engineHeartbeatStore.recordAlive()
                delay(ENGINE_HEARTBEAT_INTERVAL_MS)
            }
        }
        profileCooldowns.putAll(cooldownStore.loadAll())
        scope.launch { pruneRunLogs(force = true) }
        observeProfileRegistry()
    }

    /**
     * Keeps the running engine in sync with the profiles table. Creating, editing, enabling,
     * disabling, or deleting a profile changes the reconcile signature and triggers a matcher and
     * plugin-subscription rebuild without a service restart. The initial snapshot is dropped because
     * [onStartCommand] performs the first load, so the initial snapshot is dropped and only
     * subsequent engine-relevant changes reconcile. Identical signatures are coalesced by
     * [distinctUntilChanged], and [reloadProfiles] is idempotent, so rapid edits are safe.
     *
     * In-flight task runs are intentionally left running — reconciling re-subscribes matchers but
     * does not cancel a task that is already executing.
     */
    private fun observeProfileRegistry() {
        scope.launch {
            db.profileDao().getAllAsFlow()
                .map { profileRegistrySignature(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    AppLogger.info(TAG, "Profile registry changed; reconciling matchers")
                    reloadProfiles()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_STARTED_FROM_VISIBLE_UI, false) == true) {
            audioForegroundServiceEligibility = AudioForegroundServiceEligibility.WHILE_IN_USE
        }
        val bootCompletedTrigger = intent?.action == ACTION_BOOT_COMPLETED_TRIGGER
        val timeTickTrigger = intent?.action == ACTION_TIME_TICK_TRIGGER
        timeEventScheduler.scheduleNextMinute()
        engineHeartbeatStore.recordAlive()
        scope.launch {
            if (!timeTickTrigger || !engineLoaded) reloadProfiles()
            if (bootCompletedTrigger) {
                BootContextEvents.publishBootCompleted()
            }
            if (timeTickTrigger) {
                TimeContextEvents.publish()
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warn(TAG, "Foreground service timeout (startId=$startId, fgsType=$fgsType); stopping cleanly")
        engineHeartbeatStore.recordStopped()
        timeEventScheduler.scheduleRecovery()
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
        engineHeartbeatStore.recordStopped()
        PluginConditionSubscriptions.clear()
        job.cancel()
        logContextMonitorTransition(contextMonitorLifecycle.stopAll())
        super.onDestroy()
    }

    private suspend fun reloadProfiles() = profileReloadMutex.withLock {
        val oldJobs = matcherJobs.values.toList()
        matcherJobs.clear()
        matchers.clear()
        oldJobs.forEach { it.cancel() }
        oldJobs.joinAll()

        val profileEntities = db.profileDao().getAllEnabled()
        val profiles = profileEntities.mapNotNull { entity ->
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { issue ->
                AppLogger.error(
                    TAG,
                    "Profile ${entity.id} is corrupt and was not registered: ${issue.message}",
                )
                return@mapNotNull null
            }
            decoded.value
        }
        val activeIds = profiles.map { it.id }.toSet()
        cooldownStore.pruneDeleted(activeIds)
        synchronized(queuedProfileTasks) {
            queuedProfileTasks.keys.removeAll { it !in activeIds }
        }
        registerPluginSubscriptions(profiles)
        for (domain in profiles) {
            val matcher = ProfileMatcher(this, domain)

            val matcherJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    matcher.stateChanges().collect { change ->
                        try {
                            when (change) {
                                is ProfileStateChange.Activated -> onProfileActivated(domain)
                                is ProfileStateChange.Deactivated -> onProfileDeactivated(domain)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error(TAG, "Failed handling state change for ${domain.name}", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Profile matcher stopped for ${domain.name}", e)
                }
            }
            
            matcherJobs[domain.id] = matcherJob
            matchers[domain.id] = matcher
        }
        val subscriptionsReady = withTimeoutOrNull(MONITOR_SUBSCRIPTION_TIMEOUT_MS) {
            matchers.values.toList().forEach { it.awaitMonitorSubscriptions() }
            true
        } == true
        if (!subscriptionsReady) {
            AppLogger.warn(TAG, "Context monitor subscription barrier timed out; starting required sources in degraded mode")
        }
        logContextMonitorTransition(contextMonitorLifecycle.reconcile(profiles))
        engineLoaded = true
    }

    private fun logContextMonitorTransition(transition: ContextMonitorTransition) {
        val counts = contextMonitorLifecycle.currentReferenceCounts()
        transition.started.forEach { monitor ->
            AppLogger.info(TAG, "Context monitor $monitor started for ${counts.getOrDefault(monitor, 0)} enabled profile(s)")
        }
        transition.stopped.forEach { monitor ->
            AppLogger.info(TAG, "Context monitor $monitor stopped after its final enabled profile was removed")
        }
        transition.failedToStart.forEach { monitor ->
            AppLogger.warn(TAG, "Context monitor $monitor could not start; it will retry on the next profile reconcile")
        }
        transition.failedToStop.forEach { monitor ->
            AppLogger.warn(TAG, "Context monitor $monitor could not stop cleanly")
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

    private suspend fun onProfileActivated(profile: com.opentasker.core.model.Profile) {
        if (profile.enterTaskId <= 0) return

        val task = db.taskDao().getById(profile.enterTaskId)
        if (task == null) {
            AppLogger.warn(TAG, "Enter task ${profile.enterTaskId} not found for profile ${profile.name}")
            return
        }
        val decoded = task.toDomainDecodeResult()
        if (decoded.issue != null) {
            AppLogger.error(TAG, "Enter task ${profile.enterTaskId} is corrupt for profile ${profile.name}: ${decoded.issue.message}")
            logProfileSkippedRun(profile, decoded.value, "Enter task data is corrupt (${decoded.issue.fieldName}); recover it before it can run.")
            return
        }
        dispatchTask(profile, decoded.value)
    }

    private suspend fun onProfileDeactivated(profile: com.opentasker.core.model.Profile) {
        if (profile.exitTaskId == null || profile.exitTaskId <= 0) return
        val task = db.taskDao().getById(profile.exitTaskId)
        if (task == null) {
            AppLogger.warn(TAG, "Exit task ${profile.exitTaskId} not found for profile ${profile.name}")
            return
        }
        val decoded = task.toDomainDecodeResult()
        if (decoded.issue != null) {
            AppLogger.error(TAG, "Exit task ${profile.exitTaskId} is corrupt for profile ${profile.name}: ${decoded.issue.message}")
            logProfileSkippedRun(profile, decoded.value, "Exit task data is corrupt (${decoded.issue.fieldName}); recover it before it can run.")
            return
        }
        dispatchTask(profile, decoded.value)
    }

    private fun dispatchTask(
        profile: Profile,
        task: Task,
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
                profileTaskJobs[profile.id] = launchTrackedTask(profile, task)
            }

            AutomationMode.RESTART -> {
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    logCooldownSkip(profile, task, reservation.remainingMs)
                    return
                }
                profileTaskJobs[profile.id]?.cancel()
                profileTaskJobs[profile.id] = launchTrackedTask(profile, task)
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
                        queue.add(task)
                    }
                    AppLogger.info(TAG, "Profile ${profile.id} queued retrigger")
                    return
                }
                profileTaskJobs[profile.id] = launchQueuedTasks(profile, task)
            }

            AutomationMode.PARALLEL -> {
                val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                if (!reservation.accepted) {
                    logCooldownSkip(profile, task, reservation.remainingMs)
                    return
                }
                scope.launch { runTask(task, profile) }
            }
        }
    }

    private fun launchTrackedTask(profile: Profile, task: Task): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                runTask(task, profile)
            } finally {
                synchronized(profileTaskJobs) {
                    if (profileTaskJobs[profile.id] == thisJob) {
                        profileTaskJobs.remove(profile.id)
                    }
                }
            }
        }

    private fun launchQueuedTasks(profile: Profile, firstTask: Task): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            var nextTask: Task? = firstTask
            try {
                while (isActive && nextTask != null) {
                    runTask(requireNotNull(nextTask), profile)
                    nextTask = synchronized(queuedProfileTasks) {
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
    ) {
        val result = executeAndLogTask(
            appContext = this,
            db = db,
            task = task,
            source = "Profile: ${profile.name}",
            metadata = profileRunMetadata(profile),
            audioForegroundService = audioForegroundServiceEligibility,
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

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "OpenTasker engine", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(channel)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("OpenTasker is running")
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
        const val ACTION_TIME_TICK_TRIGGER = "com.opentasker.action.TIME_TICK_TRIGGER"
        const val EXTRA_STARTED_FROM_VISIBLE_UI = "com.opentasker.extra.STARTED_FROM_VISIBLE_UI"
        private const val CHANNEL = "opentasker.engine"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUED_TASKS = 50
        private const val MONITOR_SUBSCRIPTION_TIMEOUT_MS = 2_000L
        private const val ENGINE_HEARTBEAT_INTERVAL_MS = 60_000L
        private val RUN_LOG_PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}

/**
 * Reconcile signature for the running profile registry. Two profile-table snapshots produce the
 * same signature when nothing the engine depends on has changed, so purely cosmetic edits (name,
 * group) do not thrash matcher rebuilds while enable/disable, context, task-wiring, mode, and
 * cooldown changes do. Disabled profiles are excluded because the engine only runs enabled ones.
 */
internal fun profileRegistrySignature(profiles: List<ProfileEntity>): List<String> =
    profiles.asSequence()
        .filter { it.enabled && !it.requiresRiskAcknowledgement }
        .sortedBy { it.id }
        .map { p ->
            listOf(
                p.id,
                p.enterTaskId,
                p.exitTaskId,
                p.cooldownSec,
                p.automationMode,
                p.contextsJson,
            ).joinToString("|")
        }
        .toList()

private data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
)
