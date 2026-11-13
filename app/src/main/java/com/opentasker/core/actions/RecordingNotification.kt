package com.opentasker.core.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger

/**
 * The ongoing notification shown for as long as [AudioRecorderManager] holds the microphone.
 *
 * It exists because nothing else on this phone says a recording is running: EMUI draws no
 * microphone indicator, and the recorder's only sign of life was one buzz at the start. A stray
 * long press of volume-down on 2026-11-13 therefore recorded for nine hours unnoticed.
 *
 * A tap stops the recording and saves it, exactly as `audio.record.stop` does. The channel is
 * silent (a sound at the start would land in the recording) but of default importance, so its
 * icon stays in the status bar.
 */
object RecordingNotification {
    private const val TAG = "OpenTasker"
    private const val CHANNEL_ID = "opentasker.recording"
    private const val NOTIFICATION_ID = 63_360
    const val ACTION_STOP = "com.opentasker.action.STOP_RECORDING"

    fun show(app: Context) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "録音中 · Recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
            val stop = PendingIntent.getBroadcast(
                app,
                NOTIFICATION_ID,
                Intent(app, RecordingStopReceiver::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(app, R.color.notification_accent))
                .setContentTitle("録音中 · Recording")
                .setContentText("タップで停止して保存 · Tap to stop and save")
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setContentIntent(stop)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }.onFailure { AppLogger.warn(TAG, "Recording notification could not be shown: ${it.message}") }
    }

    /** Also called at process start: a recorder never survives its process, so one left over is stale. */
    fun clear(app: Context) {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}

/** The tap on [RecordingNotification]: stop and save, off the main thread (the move may shell out). */
class RecordingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RecordingNotification.ACTION_STOP) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                AudioRecorderManager.stop(app)
                    .onSuccess { AppLogger.info("OpenTasker", "Recording stopped from its notification → $it") }
                    .onFailure {
                        AppLogger.warn("OpenTasker", "Recording stop from its notification failed: ${it.message}")
                        RecordingNotification.clear(app)
                    }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
