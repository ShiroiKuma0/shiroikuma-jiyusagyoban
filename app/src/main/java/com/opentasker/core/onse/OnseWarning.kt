package com.opentasker.core.onse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger

/**
 * The notification raised when 白い熊 音声 cannot be made to render — with a button that opens it,
 * because the remedy is a setting that only 音声's own screen (or its App info) can reach.
 *
 * Measured 2026-09-30 on the Mate XT: a cold 音声 wakes fine when its data door is called, and then
 * Android refuses its render service a foreground start from the background
 * (`ERROR:no-foreground-start`). A Shizuku temporary allowance did not help; the permanent
 * "don't optimise" battery exemption is what 自由作業盤 and 応用管理 already carry, and it is what
 * 音声 needs. 音声 checks for it on every start and asks for it — so opening 音声 IS the fix,
 * and the notification says so rather than leaving a failed task to be read in a run log.
 */
object OnseWarning {

    private const val TAG = "OnseWarning"
    private const val CHANNEL_ID = "opentasker.urgent"
    private const val CHANNEL_NAME = "白い熊 自由作業盤 urgent"
    private const val NOTIFICATION_ID = 0x0A5E
    private const val REQUEST_OPEN = 0x0A5E

    /** Is [result] one of the failures a battery exemption (or 音声 being reachable at all) fixes? */
    fun needsAttention(result: String): Boolean =
        result.startsWith("ERROR:音声 did not answer") ||
            result.startsWith("ERROR:no-foreground-start") ||
            result.contains("nothing came back for the render")

    fun post(context: Context, result: String) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.warn(TAG, "No notification permission — 音声's failure went unreported: $result")
            return
        }
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
        )
        val text = buildString {
            append("白い熊 音声 を裏から起動できませんでした。\n")
            append("下の「音声を開く」で 白い熊 音声 を開き、電池の最適化から外してください")
            append("（設定 → アプリ → 白い熊 音声 → 電池 → 最適化しない）。")
            append("アプリ起動管理で「手動管理」にして全部 ON にもしてください。\n\n")
            append("白い熊 音声 could not be started from the background. Open it with the button ")
            append("below and exempt it from battery optimisation (Settings → Apps → 白い熊 音声 → ")
            append("Battery → don't optimise); in App launch, set it to manage manually with every ")
            append("switch on.\n\n")
            append(result.removePrefix("ERROR:"))
        }
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(app, R.color.notification_accent))
            .setContentTitle("音声：電池の最適化から外してください / exempt 白い熊 音声")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        app.packageManager.getLaunchIntentForPackage(OnseRender.PACKAGE)?.let { launch ->
            val open = PendingIntent.getActivity(
                app, REQUEST_OPEN, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(open)
            builder.addAction(0, "音声を開く / Open 白い熊 音声", open)
        } ?: AppLogger.warn(TAG, "白い熊 音声 has no launcher entry — the notification cannot open it")
        nm.notify(NOTIFICATION_ID, builder.build())
    }
}
