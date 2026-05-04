package com.opentasker.core.contexts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar

/**
 * Schedule context: triggers profiles on a recurring schedule (e.g., daily at 09:00, every Monday at 15:30).
 *
 * Spec format: "daily@09:00" or "weekly@MON,WED,FRI@15:30" or "hourly@:00" (every hour at minute 00)
 *
 * Examples:
 * - "daily@09:00" → triggers every day at 9:00 AM
 * - "weekly@MON,WED@14:30" → triggers on Monday and Wednesday at 2:30 PM
 * - "hourly@:00" → triggers every hour at minute 00
 * - "once@2026-05-03T10:30:00" → one-time trigger (uses AlarmManager exact scheduling)
 *
 * Uses AlarmManager (+ WorkManager fallback) to persist across device sleep.
 * Emits ContextEvent(true) when alarm fires, ContextEvent(false) otherwise.
 */
class ScheduleContextSource : ContextSource {
    override val type: String = "schedule"

    private val alarmManager: AlarmManager? = null
    private val scheduleReceiver = ScheduleReceiver()

    override fun events(app: Context): Flow<ContextEvent> = flow {
        val alarmMgr = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        
        // Register broadcast receiver for alarm firing
        val intentFilter = IntentFilter(ACTION_SCHEDULE_FIRED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(scheduleReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(scheduleReceiver, intentFilter)
        }

        scheduleReceiver.onFire = { schedule ->
            // When alarm fires, emit true (context matched)
            emit(ContextEvent(matched = true, contextType = "schedule", details = mapOf("schedule" to schedule)))
            // Then immediately emit false (context no longer matched)
            emit(ContextEvent(matched = false, contextType = "schedule", details = emptyMap()))
        }

        try {
            while (true) {
                kotlinx.coroutines.delay(1000) // Keep flow alive
            }
        } finally {
            app.unregisterReceiver(scheduleReceiver)
        }
    }

    companion object {
        const val ACTION_SCHEDULE_FIRED = "com.opentasker.SCHEDULE_FIRED"
        const val EXTRA_SCHEDULE = "schedule"

        /**
         * Parse schedule spec and set up AlarmManager alarm.
         * Returns true if alarm was successfully scheduled.
         */
        fun scheduleProfile(context: Context, profileId: String, spec: String): Boolean {
            val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false

            val calendar = Calendar.getInstance()
            val (isValid, nextTriggerTimeMs) = parseScheduleSpec(spec, calendar) ?: return false

            // Set alarm
            val intent = Intent(context, ScheduleReceiver::class.java).apply {
                action = ACTION_SCHEDULE_FIRED
                putExtra(EXTRA_SCHEDULE, spec)
                putExtra("profileId", profileId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, profileId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Use exact alarm if device supports (requires SCHEDULE_EXACT_ALARM permission)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (context.checkSelfPermission(android.Manifest.permission.SCHEDULE_EXACT_ALARM) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        alarmMgr.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerTimeMs,
                            pendingIntent
                        )
                    } else {
                        // Fallback to inexact alarm
                        alarmMgr.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerTimeMs,
                            pendingIntent
                        )
                    }
                } else {
                    alarmMgr.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextTriggerTimeMs,
                        pendingIntent
                    )
                }
                true
            } catch (e: Exception) {
                Log.e("ScheduleContextSource", "Failed to schedule alarm: ${e.message}")
                false
            }
        }

        /**
         * Cancel a scheduled profile alarm.
         */
        fun cancelSchedule(context: Context, profileId: String) {
            val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

            val intent = Intent(context, ScheduleReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, profileId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmMgr.cancel(pendingIntent)
        }

        /**
         * Parse schedule spec and return next trigger time in milliseconds.
         * Returns null if spec is invalid.
         *
         * Formats:
         * - "daily@HH:MM" → every day at HH:MM
         * - "weekly@DAY1,DAY2@HH:MM" → specific days at HH:MM (MON,TUE,WED,etc.)
         * - "hourly@:MM" → every hour at minute MM
         * - "once@YYYY-MM-DDTHH:MM:SS" → one-time at specific time
         */
        private fun parseScheduleSpec(spec: String, now: Calendar): Pair<Boolean, Long>? {
            val parts = spec.split("@")
            if (parts.isEmpty()) return null

            val trigger = now.timeInMillis
            val nextTime = when (parts[0]) {
                "daily" -> {
                    if (parts.size < 2) return null
                    val time = parts[1].split(":")
                    if (time.size != 2) return null
                    val (hour, minute) = time[0].toIntOrNull() to time[1].toIntOrNull()
                    if (hour == null || minute == null) return null

                    val next = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                    }
                    if (next.timeInMillis <= trigger) {
                        next.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    next.timeInMillis
                }
                "weekly" -> {
                    if (parts.size < 3) return null
                    val days = parts[1].split(",")
                    val time = parts[2].split(":")
                    if (time.size != 2) return null
                    val (hour, minute) = time[0].toIntOrNull() to time[1].toIntOrNull()
                    if (hour == null || minute == null) return null

                    val dayOfWeekMap = mapOf(
                        "SUN" to Calendar.SUNDAY,
                        "MON" to Calendar.MONDAY,
                        "TUE" to Calendar.TUESDAY,
                        "WED" to Calendar.WEDNESDAY,
                        "THU" to Calendar.THURSDAY,
                        "FRI" to Calendar.FRIDAY,
                        "SAT" to Calendar.SATURDAY
                    )

                    val targetDays = days.mapNotNull { dayOfWeekMap[it.uppercase()] }
                    if (targetDays.isEmpty()) return null

                    val next = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                    }

                    // Find next matching day
                    for (i in 1..7) {
                        if (next.get(Calendar.DAY_OF_WEEK) in targetDays && next.timeInMillis > trigger) {
                            break
                        }
                        next.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    next.timeInMillis
                }
                "hourly" -> {
                    if (parts.size < 2) return null
                    val minute = parts[1].toIntOrNull() ?: 0

                    val next = Calendar.getInstance().apply {
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                    }
                    if (next.timeInMillis <= trigger) {
                        next.add(Calendar.HOUR_OF_DAY, 1)
                    }
                    next.timeInMillis
                }
                "once" -> {
                    if (parts.size < 2) return null
                    try {
                        val isoTime = parts[1]
                        val next = Calendar.getInstance().apply {
                            timeInMillis = android.text.format.DateFormat.parse(isoTime)?.time ?: 0
                        }
                        next.timeInMillis
                    } catch (e: Exception) {
                        return null
                    }
                }
                else -> return null
            }

            return true to nextTime
        }
    }
}

/**
 * Broadcast receiver that fires when scheduled alarm triggers.
 */
internal class ScheduleReceiver : BroadcastReceiver() {
    var onFire: ((String) -> Unit)? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ScheduleContextSource.ACTION_SCHEDULE_FIRED) {
            val schedule = intent.getStringExtra(ScheduleContextSource.EXTRA_SCHEDULE) ?: return
            onFire?.invoke(schedule)

            // Re-schedule for next occurrence
            val profileId = intent.getStringExtra("profileId")
            if (profileId != null && context != null) {
                ScheduleContextSource.scheduleProfile(context, profileId, schedule)
            }
        }
    }
}
