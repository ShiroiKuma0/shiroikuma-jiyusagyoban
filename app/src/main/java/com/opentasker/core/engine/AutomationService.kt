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
import com.opentasker.automation.network.WiFiNetworkMonitor
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.contexts.BootContextEvents
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val db by lazy { OpenTaskerApp_NoHilt.db }
    private val timeEventScheduler by lazy { TimeEventScheduler(this) }
    private val wifiNetworkMonitor by lazy { WiFiNetworkMonitor(this) }
    private val appUsageMonitor by lazy { AppUsageMonitor(this) }
    
    private val matchers = Collections.synchronizedMap(mutableMapOf<Long, ProfileMatcher>())
    private val profileCooldowns = Collections.synchronizedMap(mutableMapOf<Long, Long>()) // profileId -> cooldownUntilMs
    private val matcherJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>()) // Track jobs for cleanup
    private val profileTaskJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>())
    private val queuedProfileTasks = Collections.synchronizedMap(mutableMapOf<Long, ArrayDeque<Task>>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        timeEventScheduler.scheduleNextMinute()
        wifiNetworkMonitor.start()
        appUsageMonitor.start(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bootCompletedTrigger = intent?.action == ACTION_BOOT_COMPLETED_TRIGGER
        scope.launch {
            reloadProfiles()
            if (bootCompletedTrigger) {
                BootContextEvents.publishBootCompleted()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Cancel all matcher collection jobs first
        matcherJobs.values.forEach { it.cancel() }
        matcherJobs.clear()
        matchers.clear()
        profileCooldowns.clear()
        profileTaskJobs.values.forEach { it.cancel() }
        profileTaskJobs.clear()
        queuedProfileTasks.clear()
        timeEventScheduler.cancel()
        wifiNetworkMonitor.stop()
        appUsageMonitor.stop()
        job.cancel()
        super.onDestroy()
    }

    private suspend fun reloadProfiles() {
        // Cancel previous matcher jobs
        matcherJobs.values.forEach { it.cancel() }
        matcherJobs.clear()
        matchers.clear()
        
        val profiles = db.profileDao().getAllEnabled()
        for (profile in profiles) {
            val domain = profile.toDomain()
            val matcher = ProfileMatcher(this, domain)

            val matcherJob = scope.launch {
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
                            android.util.Log.e("OpenTasker", "Failed handling state change for ${domain.name}", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("OpenTasker", "Profile matcher stopped for ${domain.name}", e)
                }
            }
            
            matcherJobs[domain.id] = matcherJob
            matchers[domain.id] = matcher
        }
    }

    private suspend fun onProfileActivated(profile: com.opentasker.core.model.Profile) {
        if (profile.enterTaskId <= 0) return
        
        val task = db.taskDao().getById(profile.enterTaskId)
        if (task == null) {
            android.util.Log.w("OpenTasker", "Enter task ${profile.enterTaskId} not found for profile ${profile.name}")
            return
        }
        val domain = task.toDomain()
        dispatchTask(profile, domain)
    }

    private suspend fun onProfileDeactivated(profile: com.opentasker.core.model.Profile) {
        if (profile.exitTaskId == null || profile.exitTaskId <= 0) return
        val task = db.taskDao().getById(profile.exitTaskId)
        if (task == null) {
            android.util.Log.w("OpenTasker", "Exit task ${profile.exitTaskId} not found for profile ${profile.name}")
            return
        }
        val domain = task.toDomain()
        dispatchTask(profile, domain)
    }

    private fun dispatchTask(
        profile: Profile,
        task: Task,
    ) {
        when (profile.automationMode) {
            AutomationMode.SINGLE -> {
                if (profileTaskJobs[profile.id]?.isActive == true) {
                    android.util.Log.i("OpenTasker", "Profile ${profile.id} already running; SINGLE mode skipped retrigger")
                    logSkippedRun(profile, task, "Profile is already running in SINGLE mode.")
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
                        queuedProfileTasks.getOrPut(profile.id) { ArrayDeque() }.add(task)
                    }
                    android.util.Log.i("OpenTasker", "Profile ${profile.id} queued retrigger")
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
        val variables = VariableStore()
        val ctx = ActionContext(this, variables) { msg ->
            android.util.Log.i("OpenTasker", msg)
        }
        val runner = TaskRunner(ctx)
        val report = runner.run(task)
        
        // Write to run log
        val logEntry = RunLogEntry(
            taskId = task.id,
            taskName = task.name,
            timestamp = report.startedAt,
            durationMs = report.durationMs,
            success = report.success,
            message = runLogMessage(
                source = "Profile: ${profile.name}",
                metadata = profileRunMetadata(profile),
                traces = report.traces,
            ),
        )
        insertRunLog(logEntry)
        
        android.util.Log.i(
            "OpenTasker",
            "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)"
        )
        
    }

    private fun logCooldownSkip(profile: Profile, task: Task, remainingMs: Long) {
        logSkippedRun(profile, task, "Cooldown active for ${formatRemainingCooldown(remainingMs)}.")
    }

    private fun logSkippedRun(profile: Profile, task: Task, reason: String) {
        scope.launch {
            insertRunLog(
                RunLogEntry(
                    taskId = task.id,
                    taskName = task.name,
                    durationMs = 0,
                    success = false,
                    message = skippedRunLogMessage(
                        source = "Profile: ${profile.name}",
                        reason = reason,
                        metadata = profileRunMetadata(profile),
                    ),
                )
            )
        }
    }

    private suspend fun insertRunLog(entry: RunLogEntry) {
        runCatching { db.runLogDao().insert(entry.toEntity()) }
            .onFailure { error ->
                android.util.Log.e("OpenTasker", "Failed to write run log for task ${entry.taskId}", error)
            }
    }

    private fun reserveCooldown(profileId: Long, cooldownSec: Int): CooldownReservation {
        val now = System.currentTimeMillis()
        synchronized(profileCooldowns) {
            val cooldownUntil = profileCooldowns[profileId] ?: 0
            if (now < cooldownUntil) {
                android.util.Log.i("OpenTasker", "Profile $profileId on cooldown, skipping")
                return CooldownReservation(accepted = false, remainingMs = cooldownUntil - now)
            }
            if (cooldownSec > 0) {
                profileCooldowns[profileId] = now + (cooldownSec * 1000L)
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
        const val ACTION_BOOT_COMPLETED_TRIGGER = "com.opentasker.action.BOOT_COMPLETED_TRIGGER"
        private const val CHANNEL = "opentasker.engine"
        private const val NOTIF_ID = 1001
    }
}

private data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
)
