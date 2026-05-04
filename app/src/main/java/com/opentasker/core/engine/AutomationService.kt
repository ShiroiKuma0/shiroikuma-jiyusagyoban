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

/**
 * Foreground service that hosts the trigger engine.
 *
 * Subscribes to context sources, evaluates active profiles, and dispatches tasks.
 * A foreground service is required on modern Android (Doze/App Standby) for the
 * automation engine to evaluate triggers reliably.
 */
class AutomationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        // TODO: load enabled profiles + subscribe to ContextSources
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
