package com.opentasker.core.scheduling

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class AlarmSchedulePrecision {
    Exact,
    InexactFallback,
}

object ExactAlarmSupport {
    const val PERMISSION_STATE_CHANGED_ACTION = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

    internal fun settingsAction(sdkInt: Int): String =
        if (sdkInt >= 31) Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
        else Settings.ACTION_APPLICATION_DETAILS_SETTINGS

    internal fun settingsPackageUri(packageName: String): String = "package:$packageName"

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun schedulePrecision(context: Context): AlarmSchedulePrecision =
        if (canScheduleExactAlarms(context)) AlarmSchedulePrecision.Exact else AlarmSchedulePrecision.InexactFallback

    fun settingsIntent(context: Context): Intent =
        Intent(
            settingsAction(Build.VERSION.SDK_INT),
            Uri.parse(settingsPackageUri(context.packageName)),
        )
}
