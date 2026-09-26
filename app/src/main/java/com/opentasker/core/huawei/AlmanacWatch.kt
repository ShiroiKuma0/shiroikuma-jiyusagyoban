package com.opentasker.core.huawei

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.core.huawei.pgnss.PgnssFetcher
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Waits for a newer almanac, and says so the moment one appears.
 *
 * ## Why waiting is the right answer here
 *
 * 2026-09-25. A build at 07:30 UTC could not reach a current Galileo almanac, walked back three
 * days the way it is designed to, and shipped one published on the 22nd — whose worst satellites
 * sat **2 227 km** from where that same set's ephemeris put them, against 96 km for a current one.
 * The set went to the band, the band searched the wrong sky, and 白い熊 got no fix before the walk.
 * By the time anyone looked, **the source had today's file**: GSSC publishes daily and had simply
 * not published yet, or had blinked, at half past seven in the morning.
 *
 * Nothing was broken except the timing, and a person cannot be asked to poll a Belgian web server.
 * So the panel offers this instead of Close: leave it watching, and it asks every so often whether
 * something newer has appeared. When it has, it says so — with the rebuild one tap away.
 *
 * ## What it does and does not do
 *
 * It **checks and tells**. It does not rebuild by itself and it does not touch the band: a build
 * takes ten minutes of radio and arithmetic and then wants 白い熊 at the band to press 更新, so
 * starting one behind their back would be the wrong kind of helpful. The notification carries the
 * task, and the tap runs it.
 *
 * Alarms rather than WorkManager, for the reason [GnssForecastReminder] gives: EMUI dozes hard, and
 * a check that arrives two hours late is a check that arrives after the walk.
 */
object AlmanacWatch {

    /** How often to ask. Half an hour: the source publishes once a day, so this is already eager. */
    const val EVERY_MINUTES = 30L

    /** How long to keep asking before giving up quietly. A day covers any publication slot. */
    const val GIVE_UP_HOURS = 24L

    private const val TAG = "AlmanacWatch"
    private const val NOTIFICATION_ID = 7312
    private const val REQUEST = 13111
    private const val REQUEST_STOP = 13112

    internal const val ACTION_CHECK = "com.opentasker.huawei.ALMANAC_CHECK"

    /**
     * The way out, and it has to be on the notification itself.
     *
     * A background search that can only be called off from a panel three taps away is a background
     * search nobody can call off (白い熊, 2026-09-25). The ongoing notification is where it is
     * visible from, so that is where the stop lives.
     */
    internal const val ACTION_STOP = "com.opentasker.huawei.ALMANAC_STOP"

    /**
     * What the panel reads to know whether a search is running: the vintage being beaten, or blank.
     *
     * Written here rather than by the action, because the action is only ONE of the four ways this
     * ends — the notification's stop button, the give-up and the arrival itself all run on an
     * alarm's thread with no [com.opentasker.core.engine.ActionContext] anywhere near them. A state
     * only the starting path could clear would go on claiming a search that had finished hours ago,
     * which is the same class of lie as the stale banner 白い熊 met on 2026-09-25.
     */
    internal const val STATE_VAR = "HUAWEI_PgnssWatching"
    internal const val EXTRA_HAVE = "have"
    internal const val EXTRA_UNTIL = "until"
    internal const val EXTRA_TASK = "task"

    /**
     * Start watching for something newer than [haveVintage] (an ISO date, the one we shipped).
     *
     * Starting twice replaces the first watch rather than running two.
     */
    fun start(context: Context, haveVintage: String, runTask: String) {
        val app = context.applicationContext
        val until = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(GIVE_UP_HOURS)
        schedule(app, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(EVERY_MINUTES), haveVintage, until, runTask)
        GnssForecastReminder.watchingForAlmanac(app, haveVintage, GIVE_UP_HOURS, EVERY_MINUTES)
        publish(haveVintage)
        AppLogger.info(TAG, "Searching for an almanac newer than $haveVintage, until ${stamp(until)}")
    }

    /** Stop asking — from the task, or from the notification's own button. */
    fun stop(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pending(app, "", 0L, ""))
        GnssForecastReminder.almanacWatchStopped(app)
        publish("")
        AppLogger.info(TAG, "Almanac search cancelled")
    }

    /** The notification's stop button, so the search can be called off where it is announced. */
    internal fun stopIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_STOP,
            Intent(context, AlmanacWatchReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** One check: is there anything newer than what we shipped? */
    internal fun check(context: Context, have: String, until: Long, runTask: String) {
        val newer = newestAvailable(have)
        if (newer != null) {
            GnssForecastReminder.almanacArrived(context, have, newer, runTask)
            publish("")
            AppLogger.info(TAG, "A newer almanac is published: $newer (we shipped $have)")
            return
        }
        if (System.currentTimeMillis() >= until) {
            AppLogger.info(TAG, "Gave up searching for an almanac newer than $have")
            GnssForecastReminder.almanacGaveUp(context, have, GIVE_UP_HOURS)
            publish("")
            return
        }
        schedule(
            context,
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(EVERY_MINUTES),
            have, until, runTask,
        )
    }

    /**
     * The newest Galileo almanac the source will admit to, or null if nothing beats what we have.
     *
     * Galileo only: it is the one whose staleness was measured to cost thousands of kilometres, and
     * it is the one published under a dated name that can be asked for without downloading it. A
     * HEAD would be tidier; GSSC answers 200 to a GET and has been seen to answer a **404 page of
     * 57 kB**, so the body is sniffed rather than trusted — the same lesson every fetch in this
     * project learned separately.
     */
    internal fun newestAvailable(have: String): String? {
        val had = runCatching { LocalDate.parse(have) }.getOrNull() ?: return null
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
        val today = LocalDate.now(ZoneOffset.UTC)
        var day = today
        while (day.isAfter(had)) {
            val date = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val ok = runCatching {
                client.newCall(Request.Builder().url("$GSSC/$date.xml").build()).execute().use { r ->
                    r.isSuccessful && PgnssFetcher.looksLikeGalileoAlmanac(
                        r.body?.byteStream()?.readNBytes(4096) ?: ByteArray(0),
                    )
                }
            }.getOrDefault(false)
            if (ok) return date
            day = day.minusDays(1)
        }
        return null
    }

    private fun schedule(context: Context, atMs: Long, have: String, until: Long, runTask: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(context, have, until, runTask)
        when (ExactAlarmSupport.schedulePrecision(context)) {
            AlarmSchedulePrecision.Exact -> try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } catch (error: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
            AlarmSchedulePrecision.InexactFallback ->
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
    }

    private fun pending(context: Context, have: String, until: Long, runTask: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, AlmanacWatchReceiver::class.java)
                .setAction(ACTION_CHECK)
                .putExtra(EXTRA_HAVE, have)
                .putExtra(EXTRA_UNTIL, until)
                .putExtra(EXTRA_TASK, runTask),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** The one write, in one place, so no exit from the search can forget to make it. */
    private fun publish(state: String) =
        PersistentGlobalScope.set(SUPER_GLOBAL_PROJECT_ID, STATE_VAR, state)

    private fun stamp(ms: Long) = GnssForecastReminder.utc(ms)

    private const val GSSC = "https://www.gsc-europa.eu/sites/default/files/sites/all/files"
}

/** Wakes for each check [AlmanacWatch] schedules. Network, so the work leaves the main thread. */
class AlmanacWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == AlmanacWatch.ACTION_STOP) {
            AlmanacWatch.stop(app)
            return
        }
        if (intent.action != AlmanacWatch.ACTION_CHECK) return
        val have = intent.getStringExtra(AlmanacWatch.EXTRA_HAVE).orEmpty()
        val until = intent.getLongExtra(AlmanacWatch.EXTRA_UNTIL, 0L)
        val task = intent.getStringExtra(AlmanacWatch.EXTRA_TASK).orEmpty()
        if (have.isEmpty()) return
        val pending = goAsync()
        Thread {
            try {
                AlmanacWatch.check(app, have, until, task)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
