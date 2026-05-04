package com.opentasker.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.opentasker.app.OpenTaskerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private val db by lazy { OpenTaskerApp.db }
    
    private val matchers = mutableMapOf<Long, ProfileMatcher>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            reloadProfiles()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private suspend fun reloadProfiles() {
        val profiles = db.profileDao().getAllEnabled()
        for (profile in profiles) {
            val domain = profile.toDomain()
            val matcher = ProfileMatcher(this, domain)

            scope.launch {
                matcher.stateChanges().collect { change ->
                    when (change) {
                        is ProfileStateChange.Activated -> onProfileActivated(domain)
                        is ProfileStateChange.Deactivated -> onProfileDeactivated(domain)
                        else -> {} // no-op for other states
                    }
                }
            }

            matchers[domain.id] = matcher
        }
    }

    private suspend fun onProfileActivated(profile: com.opentasker.core.model.Profile) {
        if (profile.enterTaskId <= 0) return
        val task = db.taskDao().getById(profile.enterTaskId) ?: return
        val domain = task.toDomain()
        runTask(domain)
    }

    private suspend fun onProfileDeactivated(profile: com.opentasker.core.model.Profile) {
        if (profile.exitTaskId == null || profile.exitTaskId <= 0) return
        val task = db.taskDao().getById(profile.exitTaskId) ?: return
        val domain = task.toDomain()
        runTask(domain)
    }

    private suspend fun runTask(task: com.opentasker.core.model.Task) {
        val variables = VariableStore()
        val ctx = ActionContext(this, variables) { msg ->
            android.util.Log.i("OpenTasker", msg)
        }
        val runner = TaskRunner(ctx)
        val report = runner.run(task)
        android.util.Log.i(
            "OpenTasker",
            "Task ${report.taskName} completed: ${report.success} (${report.durationMs}ms)"
        )
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "OpenTasker engine", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(channel)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("OpenTasker is running")
            .setContentText("Watching profile triggers")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "opentasker.engine"
        private const val NOTIF_ID = 1001
    }
}
