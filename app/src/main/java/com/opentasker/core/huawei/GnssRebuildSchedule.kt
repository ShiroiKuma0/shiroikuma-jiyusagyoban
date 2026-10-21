package com.opentasker.core.huawei

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.opentasker.core.actions.NotificationActionReceiver
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.scheduling.ExactAlarmSupport
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When the phone rebuilds the predicted set: half an hour before the watch will next ask for one.
 *
 * ## What the watch does (measured 2026-10-18 – 20)
 *
 * The D3 asks for a forecast at the first sync **more than 24 hours after it received** the one it
 * holds — not by the age of the data in it. Health handed it a set on 10-17 at 19:28 local; it asked
 * again at the 20:00 sync on 10-18 (24.5 h) and took ours. On 10-19 the 20:00 sync, exactly 24.0 h
 * later, got no ask although that set's data was already 28 h old; the 00:00 sync on 10-20 (28 h) did.
 *
 * The sync runs on the local four-hour slots of the profile `自動同期（Huawei） -- [727]`
 * ([SYNC_EVERY_MIN]), and the receipt is itself at a slot, so the ask lands on the slot 28 hours
 * after it. The old nightly build at 00:00 ran two minutes AFTER the 00:00 sync and so handed over a
 * set already a day old, and the watch — which counts 72 h from receipt — over-claimed by that day.
 *
 * ## The rule (白い熊, 2026-10-20)
 *
 * Build **[LEAD_MIN] minutes before the first sync slot after 24 hours have passed** since the later
 * of the last forecast the watch took and the last successful build. The build takes about ten
 * minutes, so the watch is handed a set that is about half an hour old.
 * - Counting from the last build too covers a watch that does not ask (off the wrist, out of
 *   range): the set is rebuilt a day later instead of ageing until the sync refuses it.
 * - When the alarm fires, a retry is planned [RETRY_MIN] minutes on at once. A build that
 *   succeeds replaces it with the next real plan; one that fails, is cancelled, or never starts
 *   leaves it standing, so it is tried again.
 * - No build at any other time. Manual builds (衛星 生成) count as builds and move the plan.
 *
 * The plan lives in preferences and is re-armed whenever the engine starts, because an app update or
 * a reboot drops the alarm. A plan whose moment passed while nothing was running fires a minute
 * after the engine is back.
 */
object GnssRebuildSchedule {

    /** The task the alarm runs. It builds and sends nothing; the next sync hands the set over. */
    const val TASK_NAME = "衛星 予定生成 -- [727]"

    /** The sync profile's interval. Slots are local minutes-of-day divisible by it, as `clock_tick` reads them. */
    const val SYNC_EVERY_MIN = 240

    /** The watch asks once this long has passed since it received its set. */
    const val ASK_AFTER_HOURS = 24L

    const val LEAD_MIN = 30L
    const val RETRY_MIN = 30L

    /** One line saying when the next build is planned, beside `built-log.txt`. */
    const val PLAN_NAME = "rebuild-plan.txt"

    private const val TAG = "GnssRebuildSchedule"
    private const val PREFS = "gnss_rebuild"
    private const val KEY_TAKEN = "taken_ms"
    private const val KEY_BUILT = "built_ms"
    private const val KEY_NEXT = "next_ms"
    private const val REQUEST = 13121
    internal const val ACTION_BUILD = "com.opentasker.huawei.GNSS_REBUILD"

    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The watch took a forecast. */
    fun forecastTaken(context: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_TAKEN, atMs).apply()
        replan(context, "the watch took a forecast")
    }

    /** A build succeeded. */
    fun built(context: Context, atMs: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_BUILT, atMs).apply()
        replan(context, "a build finished")
    }

    /** Re-arm on engine start. The first run seeds the receipt time from `band-holds.txt`. */
    fun restore(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_TAKEN) && !p.contains(KEY_BUILT)) {
            val holds = File(HuaweiGnssSupply.storeDir(context), "band-holds.txt")
            if (holds.isFile) p.edit().putLong(KEY_TAKEN, holds.lastModified()).apply()
        }
        val next = p.getLong(KEY_NEXT, 0L)
        val now = System.currentTimeMillis()
        when {
            next == 0L -> replan(context, "first plan")
            next <= now -> arm(context, now + 60_000L, "the planned build was missed while the engine was down")
            else -> arm(context, next, "re-armed on engine start")
        }
    }

    /**
     * Next build for an anchor (the later of taken and built): [LEAD_MIN] minutes before the first
     * local slot strictly after anchor + [ASK_AFTER_HOURS].
     */
    internal fun planFor(anchorMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val due = anchorMs + ASK_AFTER_HOURS * 3_600_000L
        var day = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
        while (true) {
            var minute = 0
            while (minute < 24 * 60) {
                val slot = day.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
                if (slot > due) return slot - LEAD_MIN * 60_000L
                minute += SYNC_EVERY_MIN
            }
            day = day.plusDays(1)
        }
    }

    private fun replan(context: Context, why: String) {
        val p = prefs(context)
        val anchor = maxOf(p.getLong(KEY_TAKEN, 0L), p.getLong(KEY_BUILT, 0L))
        val now = System.currentTimeMillis()
        val at = if (anchor == 0L) now + 60_000L else planFor(anchor).coerceAtLeast(now + 60_000L)
        arm(context, at, why)
    }

    private fun arm(context: Context, atMs: Long, why: String) {
        val app = context.applicationContext
        prefs(app).edit().putLong(KEY_NEXT, atMs).apply()
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        when (ExactAlarmSupport.schedulePrecision(app)) {
            AlarmSchedulePrecision.Exact -> try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } catch (error: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
            AlarmSchedulePrecision.InexactFallback ->
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
        val local = LOCAL.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))
        AppLogger.info(TAG, "Next forecast build $local local — $why")
        runCatching {
            val dir = HuaweiGnssSupply.storeDir(app).apply { mkdirs() }
            File(dir, PLAN_NAME).writeText("$atMs\tnext build $local local · $why\n")
        }
    }

    /** The alarm fired: plan the retry first, then hand the run to the engine service. */
    internal fun fire(context: Context) {
        val app = context.applicationContext
        if (EngineShutdown.refuse(app, "planned forecast build")) return
        arm(app, System.currentTimeMillis() + RETRY_MIN * 60_000L, "retry, in case this build does not finish")
        val intent = Intent(app, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN_NOTIFICATION_TASK
            putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, TASK_NAME)
            putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, TASK_NAME)
        }
        runCatching { ContextCompat.startForegroundService(app, intent) }
            .onFailure { AppLogger.error(TAG, "Could not start the engine for the planned build", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, GnssRebuildReceiver::class.java).setAction(ACTION_BUILD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class GnssRebuildReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GnssRebuildSchedule.ACTION_BUILD) GnssRebuildSchedule.fire(context)
    }
}
