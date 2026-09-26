package com.opentasker.core.huawei

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.actions.NotificationActionReceiver
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.AlarmSchedulePrecision
import com.opentasker.core.scheduling.ExactAlarmSupport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Says how long the band's forecast is good for, and comes back to say it again before it runs out.
 *
 * ## Why this exists — 2026-09-21, and it is the third time
 *
 * 白い熊 walked on a set whose last block was 2026-09-21 11:59 UTC, five hours after it had run out.
 * Nothing on either device said so. The phone's last BUILD had failed two days earlier on a DNS
 * lookup and left its reason in a variable nobody reads; the transfer that put the set on the band
 * predates the record that would have remembered its window; and the band's own screen said
 * **eighteen hours left**.
 *
 * **The band's countdown is not our window.** Measured twice that evening — 18 h at 17:05 UTC and
 * 16 h at 19:00 UTC — it resolves to **our last block plus exactly 24 hours**. So the band will
 * wear a forecast for a full day after the last element set in it, reporting health the whole way,
 * and a receiver running on extrapolated orbits fixes the slow way or not at all. The file format
 * holds exactly [com.opentasker.core.huawei.pgnss.Records.BLOCKS] two-hour blocks — 72 hours — so
 * that fourth day is not something we can supply. It is something 白い熊 has to be told about.
 *
 * So this is the one place that knows when the forecast really ends, and it does three things with
 * that knowledge: says it **loudly** the moment the band accepts a set, comes back **before** the
 * end with time to act, and comes back **again** at the end to contradict the band's own screen.
 *
 * ## Alarms, not WorkManager
 *
 * A reminder that arrives late is a reminder that arrives after the walk. EMUI dozes hard — this
 * repository has a memory devoted to the engine being frozen in it — so the schedule goes through
 * `AlarmManager` with `setExactAndAllowWhileIdle`, falling back the way [com.opentasker.automation.scheduler.TimeEventScheduler]
 * does when exact alarms are not permitted.
 */
object GnssForecastReminder {

    /** How long before the forecast ends the first reminder fires. */
    const val WARN_BEFORE_MS = 12 * 60 * 60 * 1000L

    /**
     * The band's own countdown: **72 hours from the moment it TOOK the set**, not from our window.
     *
     * Measured exactly on 2026-09-21. The band accepted a set at 19:52 UTC and its screen read
     * **2 d 23 h** at 20:11 — which is 19:52 + 72 h to the minute. The earlier reading that evening
     * looked like "our last block + 24 h" and it was the same rule all along: that set had been
     * built on the 18th and only reached the band on the 19th, so counting from receipt put its
     * expiry a whole day past the data's.
     *
     * **So the band over-claims by exactly the delay between building a set and handing it over.**
     * Build and transfer in one run and it is under two hours; build one day and transfer the next
     * and the band promises a day of forecast that does not exist. That is worth saying out loud
     * rather than treating as the band being wrong.
     */
    const val BAND_WINDOW_HOURS = 72

    private const val TAG = "GnssForecastReminder"
    private const val NOTIFICATION_ID = 7311
    private const val CHANNEL_ID = "opentasker.urgent"
    private const val CHANNEL_NAME = "白い熊 自由作業盤 urgent"
    private const val REQUEST_WARN = 13101
    private const val REQUEST_END = 13102
    private const val REQUEST_DIALOG = 13103
    private const val REQUEST_TASK = 13104

    internal const val ACTION_WARN = "com.opentasker.huawei.GNSS_FORECAST_WARN"
    internal const val ACTION_END = "com.opentasker.huawei.GNSS_FORECAST_END"
    internal const val EXTRA_UNTIL_MS = "until_ms"

    /**
     * When the band took the set, so its own countdown can be predicted rather than guessed at.
     *
     * Kept in the process only: a reminder that fires after a restart simply omits the comparison
     * rather than carrying a stale one, and the transfer that rearms it writes it again.
     */
    private var received: Long = 0L

    /**
     * The band has just accepted a forecast ending at [untilMs]: say so, and arrange to say it again.
     *
     * Re-arming replaces whatever was scheduled — a second transfer the same evening must not leave
     * the first one's reminder standing over a window that no longer exists.
     */
    fun arm(context: Context, untilMs: Long, receivedAtMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        cancelAlarms(app)
        received = receivedAtMs
        notify(
            app,
            title = "衛星：バンドの予測暦 ${leftLabel(untilMs)}",
            text = statusText(untilMs),
            ongoing = false,
            dialogFor = untilMs,
        )
        schedule(app, untilMs - WARN_BEFORE_MS, ACTION_WARN, REQUEST_WARN, untilMs)
        schedule(app, untilMs, ACTION_END, REQUEST_END, untilMs)
    }

    /**
     * DAYS AND HOURS, never a bare hour count.
     *
     * 白い熊, 2026-09-21: *"it must be x days x hours — so it can be compared with what the band
     * itself is saying."* The band counts in days and hours on its own screen, and "67 hours" cannot
     * be held up against "2 days 19 hours" without arithmetic nobody does while getting ready to
     * walk. Past the end it counts the other way, because "ran out 5 hours ago" is the fact then.
     */
    internal fun leftLabel(untilMs: Long, now: Long = System.currentTimeMillis()): String {
        val totalHours = (untilMs - now) / 3_600_000L
        val over = totalHours < 0
        val h = kotlin.math.abs(totalHours)
        val ja = when {
            h < 24 -> "$h 時間"
            h % 24 == 0L -> "${h / 24} 日"
            else -> "${h / 24} 日 ${h % 24} 時間"
        }
        val en = when {
            h < 24 -> "$h h"
            h % 24 == 0L -> "${h / 24} d"
            else -> "${h / 24} d ${h % 24} h"
        }
        return if (over) "切れて $ja / OUT for $en" else "残り $ja / $en left"
    }

    /** The one text every notification and the dialog are built from, so they cannot disagree. */
    internal fun statusText(untilMs: Long, now: Long = System.currentTimeMillis()): String {
        val bandClaims = bandClaimsUntil(now)
        val dead = untilMs <= now
        return buildString {
            if (dead) {
                append("予測暦は ${utc(untilMs)} UTC（${local(untilMs)}）で切れました。")
                append("歩く前に「衛星 生成」を。\n")
            } else {
                append("バンドの予測暦は ${utc(untilMs)} UTC（${local(untilMs)}）まで。")
                append("${leftLabel(untilMs, now)}。切れる前に「衛星 生成」を。\n")
            }
            if (bandClaims != null) {
                append("バンド自身の画面は ${leftLabel(bandClaims, now)} と言います — ")
                append("受け取った時刻から $BAND_WINDOW_HOURS 時間を数えているためで、")
                append("資料そのものの窓ではありません。\n\n")
            } else {
                append("\n")
            }
            if (dead) {
                append("The forecast ENDED ${utc(untilMs)} UTC (${local(untilMs)}). ")
                append("Run 衛星 生成 before you walk.\n")
            } else {
                append("The band's forecast runs to ${utc(untilMs)} UTC (${local(untilMs)}) — ")
                append("${leftLabel(untilMs, now)}. Rebuild with 衛星 生成 before then.\n")
            }
            if (bandClaims != null) {
                append("The band's own screen says ${leftLabel(bandClaims, now)} — it counts ")
                append("$BAND_WINDOW_HOURS h from the moment it TOOK the set, not the window the data has.")
            }
        }
    }

    /**
     * The same thing again, big and loud, for the dialog the notification opens.
     *
     * 白い熊, 2026-09-21: *"clicking it does nothing … it must display this info LOUDLY — so we see
     * it in color — bold."* `dialog.text` in markup mode at FULL size is the app's own way to put a
     * page in front of someone; the headings take the theme's yellow and `**` carries the numbers.
     */
    internal fun statusMarkup(untilMs: Long, now: Long = System.currentTimeMillis()): String {
        val bandClaims = bandClaimsUntil(now)
        val dead = untilMs <= now
        return buildString {
            append(if (dead) "# 予測暦は切れています\n" else "# バンドの予測暦\n")
            append("\n")
            append("## **${leftLabel(untilMs, now)}**\n")
            append("\n")
            append("${utc(untilMs)} UTC まで — ${local(untilMs)}\n")
            append("to ${utc(untilMs)} UTC — ${local(untilMs)}\n")
            append("\n")
            if (bandClaims != null) {
                append("---\n")
                append("\n")
                append("## バンドの画面は **${leftLabel(bandClaims, now)}**\n")
                append("\n")
                append("バンドは **受け取った時刻から $BAND_WINDOW_HOURS 時間** を数えます — ")
                append("${utc(received)} UTC から。資料そのものの窓ではないので、")
                append("作ってから渡すまでの間だけ長く出ます。\n")
                append("The band counts **$BAND_WINDOW_HOURS h from when it TOOK the set** ")
                append("(${utc(received)} UTC), not the window the data has — so it over-claims by ")
                append("exactly the delay between building a set and handing it over.\n")
                append("\n")
            }
            append("---\n")
            append("\n")
            append(if (dead) "## いま「衛星 生成」を\n" else "## 切れる前に「衛星 生成」を\n")
            append("\n")
            append(if (dead) "Run 衛星 生成 now.\n" else "Rebuild with 衛星 生成 before it runs out.\n")
        }
    }

    /** Drop the reminders — nothing is owed when no forecast was accepted. */
    fun disarm(context: Context) = cancelAlarms(context.applicationContext)

    /**
     * A build that did not produce a set, said where 白い熊 will actually see it.
     *
     * The reason has always been written down — `%HUAWEI_PgnssAlert` and `built-log.txt` both carry
     * it — and both live behind a panel you have to open. On 2026-09-19 a build died on
     * `Unable to resolve host "download.aiub.unibe.ch"`, the phone went on serving the set from the
     * day before, and that string sat unread until the walk on the 21st failed. A build that fails
     * leaves the band wearing something older than it thinks; that is worth a notification.
     */
    fun buildFailed(context: Context, why: String) {
        notify(
            context.applicationContext,
            title = "衛星：一式を作り直せませんでした",
            text = buildString {
                append(why).append("\n\n")
                append("バンドは前の一式を着けたままです。期限が切れていても、バンドの画面は")
                append("「受け取ってから $BAND_WINDOW_HOURS 時間」を数え続けるので、まだ有効に見えます。\n\n")
                append("The set was NOT rebuilt — the band still wears the previous one, and its own ")
                append("screen goes on counting $BAND_WINDOW_HOURS h from when it TOOK that one, ")
                append("so it will still look healthy.")
            },
            ongoing = true,
        )
    }

    /**
     * Nothing was handed over, and the band is still wearing what it had.
     *
     * The quiet version of this is what did the damage: a refusal that only a variable knows about
     * reads exactly like a success to someone who is about to go for a walk.
     */
    fun refusedStaleSet(context: Context, why: String) {
        notify(
            context.applicationContext,
            title = "衛星：バンドには渡していません",
            text = buildString {
                append(why).append("\n\n")
                append("古い一式を新しいふりをして渡すことはしません。バンドは前のものを着けたままです。\n")
                append("「衛星 生成」をやり直してください。\n\n")
                append("Nothing was handed to the band — an old set is not passed off as a new one. ")
                append("It still wears the previous forecast. Run 衛星 生成 again.")
            },
            ongoing = true,
        )
    }

    /**
     * The search is running: said plainly, with its cadence and its way out.
     *
     * 白い熊, 2026-09-25: *"'Wait for a new almanac' is not a good description — it lends itself to a
     * wait-and-do-nothing interpretation."* It does not wait; it goes and looks, every half hour,
     * in the background. And a search that cannot be called off from where it announces itself is a
     * search nobody can call off — hence the button.
     */
    fun watchingForAlmanac(context: Context, have: String, hours: Long, everyMinutes: Long) {
        notify(
            context.applicationContext,
            title = "衛星：概略暦を探しています（$everyMinutes 分ごと）",
            text = buildString {
                append("いま積んでいるのは $have の概略暦です。\n")
                append("背景で $everyMinutes 分ごとに発行元を見に行き、新しいものが出た時点で知らせます")
                append("（最長 $hours 時間、その後は自分で止まります）。\n")
                append("やめるときは下の「探索をやめる」。\n\n")
                append("SEARCHING in the background: the publisher is checked every $everyMinutes ")
                append("minutes for something newer than $have, for up to $hours h, and you are told ")
                append("the moment one appears. Stop it with the button below.")
            },
            ongoing = true,
            stopWatch = true,
        )
    }

    /** Called off. Said once, so the ongoing notification cannot linger over a search that stopped. */
    fun almanacWatchStopped(context: Context) {
        notify(
            context.applicationContext,
            title = "衛星：概略暦の探索をやめました",
            text = "もう探しません。必要になったら画面からまた始められます。\n\n" +
                "The search has stopped. Start it again from the panel whenever you want it.",
            ongoing = false,
        )
    }

    /** It arrived. This is the notification the whole watch exists to post. */
    fun almanacArrived(context: Context, have: String, newer: String, runTask: String) {
        notify(
            context.applicationContext,
            title = "衛星：新しい概略暦が出ました（$newer）",
            text = buildString {
                append("$have → $newer。いま作り直せば、バンドは正しい空を探します。\n")
                append("この通知を押すと「$runTask」が走ります。\n\n")
                append("A newer almanac is published: $have → $newer. Rebuild now and the band will ")
                append("search the right sky. Tapping this runs 「$runTask」.")
            },
            ongoing = true,
            runTask = runTask,
        )
    }

    /** Nothing appeared in the window. Said once rather than left hanging. */
    fun almanacGaveUp(context: Context, have: String, hours: Long) {
        notify(
            context.applicationContext,
            title = "衛星：新しい概略暦は出ませんでした",
            text = "$hours 時間待ちましたが、$have より新しいものは出ていません。監視をやめます。\n\n" +
                "Nothing newer than $have appeared in $hours h; the watch has stopped.",
            ongoing = false,
        )
    }

    /** A build that shipped a stale almanac — loud, because the fix will be slow and nothing else says so. */
    fun staleAlmanac(context: Context, which: String) {
        notify(
            context.applicationContext,
            title = "衛星：概略暦が古いまま作りました",
            text = buildString {
                append(which).append("\n")
                append("概略暦は「どの衛星をどのあたりで探すか」を決めるので、測位は遅くなります。\n")
                append("画面の「概略暦を探し続ける」を押しておけば、背景で ")
                append(AlmanacWatch.EVERY_MINUTES)
                append(" 分ごとに発行元を見に行き、出た時点で知らせます。\n\n")
                append("The set was built on a stale almanac. It decides which satellites to look ")
                append("for and roughly where, so the fix will be slow. Press 概略暦を探し続ける on ")
                append("the panel and the publisher is checked every ")
                append(AlmanacWatch.EVERY_MINUTES)
                append(" minutes in the background until a current one appears.")
            },
            ongoing = true,
        )
    }

    /** The reminder itself, posted when one of the two alarms fires. */
    internal fun remind(context: Context, expired: Boolean, untilMs: Long) {
        notify(
            context.applicationContext,
            title = if (expired) "衛星：予測暦が切れました / forecast ENDED"
            else "衛星：予測暦 ${leftLabel(untilMs)}",
            text = statusText(untilMs),
            ongoing = expired,
            dialogFor = untilMs,
        )
    }

    private fun schedule(context: Context, atMs: Long, action: String, request: Int, untilMs: Long) {
        // A reminder whose moment has already passed is not scheduled — but the END one still fires
        // immediately when the set is handed over already dead, because that is precisely the case
        // worth shouting about.
        if (atMs <= System.currentTimeMillis()) {
            if (action == ACTION_END) remind(context, expired = true, untilMs = untilMs)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, action, request, untilMs)
        when (ExactAlarmSupport.schedulePrecision(context)) {
            AlarmSchedulePrecision.Exact -> try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            } catch (error: SecurityException) {
                AppLogger.warn(TAG, "Exact-alarm access changed while scheduling; used the Doze-capable fallback")
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
            AlarmSchedulePrecision.InexactFallback ->
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
        AppLogger.info(TAG, "Forecast reminder $action at ${utc(atMs)} UTC")
    }

    private fun cancelAlarms(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pendingIntent(context, ACTION_WARN, REQUEST_WARN, 0L))
        alarms.cancel(pendingIntent(context, ACTION_END, REQUEST_END, 0L))
    }

    private fun pendingIntent(context: Context, action: String, request: Int, untilMs: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, GnssForecastReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_UNTIL_MS, untilMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notify(
        context: Context,
        title: String,
        text: String,
        ongoing: Boolean,
        dialogFor: Long? = null,
        runTask: String? = null,
        stopWatch: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.warn(TAG, "No notification permission — the forecast window went unreported")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            // The collapsed line is the PUNCHLINE, not the first sentence of a paragraph: a
            // notification whose text is cut off mid-word says nothing at all (白い熊, 2026-09-21).
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
        // AND TAPPING IT OPENS THE WHOLE THING, big and in colour. A notification that does nothing
        // when pressed is a dead end at the exact moment someone wants to know more.
        // A notification that can ACT. The almanac watch's whole point is that the rebuild is one
        // tap from the news, at whatever hour the news arrives.
        runTask?.takeIf { it.isNotBlank() }?.let { name ->
            // Blocking on purpose and safely: this runs on an alarm's background thread, never on
            // the main one, and it is a single indexed lookup.
            val id = runCatching {
                kotlinx.coroutines.runBlocking {
                    OpenTaskerApp_NoHilt.readyDb?.taskDao()?.getByName(name)?.id
                }
            }.getOrNull()
            if (id != null) {
                builder.setContentIntent(
                    PendingIntent.getBroadcast(
                        context,
                        REQUEST_TASK,
                        Intent(context, NotificationActionReceiver::class.java)
                            .setAction(NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON)
                            .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, id)
                            .putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, name),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                AppLogger.warn(TAG, "No task named \"$name\" — the notification cannot run it")
            }
        }
        dialogFor?.let { until ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_DIALOG,
                    Intent(context, DialogActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_TEXT)
                        .putExtra(DialogActivity.EXTRA_TITLE, "衛星 / Satellite forecast")
                        .putExtra(DialogActivity.EXTRA_TEXT, statusMarkup(until))
                        .putExtra(DialogActivity.EXTRA_MARKUP, true)
                        .putExtra(DialogActivity.EXTRA_SIZE, "full")
                        .putExtra(DialogActivity.EXTRA_TEXT_SCALE, 1.35f)
                        .putExtra(DialogActivity.EXTRA_OK, "閉じる / Close"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        // The way out lives ON the notification, because that is where the search is visible from.
        if (stopWatch) {
            builder.addAction(0, "探索をやめる / Stop searching", AlmanacWatch.stopIntent(context))
        }
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    /** What the band's own screen will be counting down to, or null when the receipt is unknown. */
    internal fun bandClaimsUntil(now: Long = System.currentTimeMillis()): Long? =
        received.takeIf { it > 0L }?.plus(BAND_WINDOW_HOURS * 3_600_000L)

    /** UTC, because every satellite record in this project is written in it and never in local time. */
    internal fun utc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    /** …and the same instant in 白い熊's own clock, which is the one they walk by. */
    internal fun local(ms: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.US).format(Date(ms))
}

/** Wakes for the two reminders [GnssForecastReminder] schedules. */
class GnssForecastReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val until = intent.getLongExtra(GnssForecastReminder.EXTRA_UNTIL_MS, 0L)
        if (until == 0L) return
        GnssForecastReminder.remind(
            context.applicationContext,
            expired = intent.action == GnssForecastReminder.ACTION_END,
            untilMs = until,
        )
    }
}
