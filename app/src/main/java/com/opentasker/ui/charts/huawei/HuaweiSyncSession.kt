package com.opentasker.ui.charts.huawei

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.app.R
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.core.huawei.HuaweiSyncState
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.Loc
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sync dialog's run, held by the app rather than by the window that shows it.
 *
 * 白い熊, 2026-11-10: the dialog needs Cancel, and while a sync runs its only other button is Hide —
 * the sync keeps going, and a notification, the report's Sync or the board's Sync brings the dialog
 * back onto it. A run owned by the window could do neither: closing the window cancelled the scope
 * that fed the dialog and restarted a cut sync, so the run went on unseen and stopped restarting.
 *
 * One run at a time. [state] is what the dialog shows — the steps, the bars, the red lines of a cut
 * link and the result — and it stays after the run ends, so a dialog reopened from the notification
 * shows how it went.
 */
object HuaweiSyncSession {

    private val _state = MutableStateFlow(BoardSync())
    val state: StateFlow<BoardSync> = _state.asStateFlow()

    private var run: Job? = null

    val running: Boolean get() = _state.value.running != null

    /** Start [job] unless one is already running; the dialog shows whichever is. */
    fun start(context: Context, job: SyncJob) {
        if (running) return
        val app = context.applicationContext
        val startedAt = System.currentTimeMillis()
        _state.value = BoardSync(
            running = job, phase = "starting", lines = listOf(SyncLine(0, "starting")), startedAtMs = startedAt,
        )
        notifyRunning(app)
        run = HuaweiSyncRunner.scope.launch {
            val summary = try {
                when (job) {
                    SyncJob.NORMAL, SyncJob.FULL -> syncWithRestarts(app, job, startedAt)
                    SyncJob.STATE -> {
                        runTask(app, SYNC_STATE_TASK)
                        syncStateReadout(app)
                    }
                    SyncJob.WORKOUTS -> workouts(app, startedAt)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                end(app, job, startedAt, CANCELLED[lang(app)])
                throw e
            }
            end(app, job, startedAt, summary)
        }
    }

    /**
     * Stop the run. The task is cancelled where it stands — the sync's own `finally` closes the
     * Bluetooth link — and a restart that was waiting never starts.
     */
    fun cancel() {
        run?.cancel()
    }

    private fun lang(context: Context) = BandLanguage.parse(HuaweiSettings.language(context))

    private fun at(startedAt: Long) = ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    private fun end(context: Context, job: SyncJob, startedAt: Long, summary: String) {
        _state.update {
            it.copy(
                running = null, last = job, phase = "done", percent = null, count = "",
                summary = summary, lines = it.lines + SyncLine(at(startedAt), "done"),
            )
        }
        notifyDone(context, summary)
    }

    /** A step as a new line — only when it is a new step; the runner re-announces with every record. */
    private fun step(startedAt: Long, phase: String, message: String = "") = _state.update { s ->
        val last = s.lines.lastOrNull()
        // A full sync walks many windows, each counting then reading: one line for each of the two,
        // with the window number in the counter, rather than two lines per window.
        val repeating = phase in REPEATING_PHASES && s.lines.any { it.phase == phase }
        if (repeating || (last != null && last.phase == phase && last.message == message)) s.copy(phase = phase)
        else s.copy(phase = phase, message = message, lines = s.lines + SyncLine(at(startedAt), phase, message))
    }

    /**
     * The sync, run again in the same dialog whenever the link is cut, with the cut in red (白い熊,
     * 2026-11-10: “restart the sync, so we keep it running there and see what happens”). Cancel is
     * what stops it; [MAX_ATTEMPTS] is only the floor under a phone whose Bluetooth never returns.
     */
    private suspend fun syncWithRestarts(context: Context, job: SyncJob, startedAt: Long): String {
        val task = if (job == SyncJob.NORMAL) BOARD_TILES.first { it.key == "sync" }.task else SYNC_FULL_TASK
        val lang = lang(context)
        var attempt = 1
        while (true) {
            val since = System.currentTimeMillis()
            HuaweiSyncState.dismissTrouble()
            val watch = HuaweiSyncRunner.scope.launch { follow(context, since, startedAt) }
            try {
                runTask(context, task)
            } finally {
                watch.cancel()
            }
            val summary = PersistentGlobalScope.snapshotAll()["HUAWEI_Summary"].orEmpty()
            val trouble = HuaweiSyncState.trouble.value?.takeIf { it.atMillis >= since }
            HuaweiSyncState.dismissTrouble()
            val cut = trouble != null &&
                (trouble.lost.isNotEmpty() || trouble.message.contains("Broken pipe", ignoreCase = true))
            if (!cut || attempt >= MAX_ATTEMPTS) return summary
            val lost = trouble!!.lost.joinToString(" · ") { phaseLabel(it)[lang] }
            _state.update { s ->
                s.copy(
                    cuts = s.cuts + 1, phase = "starting", percent = null, count = "",
                    lines = s.lines + SyncLine(
                        at(startedAt), "cut",
                        CUT_LINE[lang].format(
                            if (lost.isEmpty()) CUT_ALL[lang] else CUT_LOST[lang].format(lost),
                            RESTART_DELAY_MS / 1000, attempt + 1,
                        ),
                        alarm = true,
                    ),
                )
            }
            notifyRunning(context)
            awaitBluetooth(context)
            delay(RESTART_DELAY_MS)
            attempt++
        }
    }

    /**
     * Follow the runner's own progress while the task runs. Only emissions from a sync that began
     * after [since] count: until the runner calls `begin`, the flow still holds the previous sync's
     * finished state, and showing that as this run's progress would be a lie.
     */
    private suspend fun follow(context: Context, since: Long, startedAt: Long) {
        HuaweiSyncState.progress.collect { p ->
            if (!p.running || p.startedAtMillis < since - 1_000) return@collect
            step(startedAt, p.phase, p.message)
            val reading = p.phase == "reading" && p.recordCount > 0
            val lang = lang(context)
            _state.update {
                it.copy(
                    percent = if (reading) p.percent else null,
                    count = listOfNotNull(
                        p.windowCount.takeIf { n -> n > 1 && p.windowIndex > 0 }
                            ?.let { n -> Loc("window %d / %d", "窓 %d / %d")[lang].format(p.windowIndex, n) },
                        if (reading) Loc("record %d / %d", "記録 %d / %d")[lang].format(p.recordIndex, p.recordCount) else null,
                        p.samples.takeIf { n -> n > 0 }?.let { n -> Loc("%d samples", "標本 %d")[lang].format(n) },
                    ).joinToString(" · "),
                )
            }
        }
    }

    private suspend fun workouts(context: Context, startedAt: Long): String {
        val now = System.currentTimeMillis() / 1000
        val result = HuaweiSyncRunner.fetchWorkouts(
            context,
            HuaweiSettings.address(context),
            now - HuaweiBoardActivity.WORKOUT_DAYS * 86_400L,
            now,
            OpenTaskerApp_NoHilt.db.huaweiWorkoutDao(),
            onPhase = { step(startedAt, it) },
            onProgress = { done, total ->
                if (done == 0) step(startedAt, "workouts")
                _state.update {
                    it.copy(percent = if (total > 0) done * 100 / total else 100, count = "$done / $total")
                }
            },
        )
        val lang = lang(context)
        return result.fold(
            onSuccess = { fetched ->
                HuaweiWorkoutStore.Kind.entries.joinToString(" · ") { kind ->
                    "${HuaweiText.titleFor(kind)[lang]} ${fetched.count { kind.matches(it.summary.type) }}"
                }
            },
            onFailure = { it.message ?: it::class.java.simpleName },
        )
    }

    /** In THIS coroutine, so cancelling the run cancels the task — and the sync inside it. */
    private suspend fun runTask(context: Context, name: String) {
        val db = OpenTaskerApp_NoHilt.db
        // By name, and decoded the way every other caller decodes: a task whose stored JSON no
        // longer parses is skipped rather than thrown from inside the runner.
        val decoded = db.taskDao().getByName(name)?.toDomainDecodeResult() ?: return
        if (decoded.issue != null) return
        executeAndLogTask(
            appContext = context, db = db, task = decoded.value,
            source = "健康 board", logTag = "HuaweiBoard",
        )
    }

    /**
     * Until Bluetooth is on again, at most [BLUETOOTH_WAIT_MS]. A crashed stack takes about six
     * seconds to come back (08:45:25 → 08:45:31 on 2026-11-10), and a sync started into it fails at
     * once and would only add a second red line for the same cut.
     */
    private suspend fun awaitBluetooth(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter ?: return
        val until = System.currentTimeMillis() + BLUETOOTH_WAIT_MS
        while (System.currentTimeMillis() < until && !runCatching { adapter.isEnabled }.getOrDefault(true)) {
            delay(500)
        }
    }

    /** What 同期状態 decided, said in the dialog too — the task itself only raises or clears a notification. */
    private fun syncStateReadout(context: Context): String {
        val g = PersistentGlobalScope.snapshotAll()
        val lang = lang(context)
        val last = g["HUAWEI_LastSuccess"].orEmpty()
        if (last.isBlank()) return Loc("No sync has ever succeeded.", "一度も同期に成功していません。")[lang]
        return listOfNotNull(
            Loc("Last success %s", "最後の成功 %s")[lang].format(last),
            g["HUAWEI_AgeHours"]?.takeIf { it.isNotBlank() }?.let { Loc("%s h ago", "%s 時間前")[lang].format(it) },
            g["Huawei_WarnAfterHours"]?.takeIf { it.isNotBlank() && !it.startsWith("%") }
                ?.let { Loc("warns after %s h", "警告 %s 時間後")[lang].format(it) },
            g["HUAWEI_BatteryPct"]?.takeIf { it.isNotBlank() }?.let { Loc("battery %s %%", "電池 %s %%")[lang].format(it) },
            g["HUAWEI_SyncCount"]?.takeIf { it.isNotBlank() }?.let { Loc("%s syncs", "同期 %s 回")[lang].format(it) },
        ).joinToString(" · ")
    }

    // --- the notification: how a hidden run is found again -------------------------------------

    private fun manager(context: Context): NotificationManager? {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun reopen(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, HuaweiBoardActivity::class.java)
            .putExtra(HuaweiBoardActivity.EXTRA_SHOW_SYNC, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notifyRunning(context: Context) {
        val nm = manager(context) ?: return
        val lang = lang(context)
        val cuts = _state.value.cuts
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(NOTE_RUNNING[lang])
                .setContentText(if (cuts > 0) NOTE_CUTS[lang].format(cuts) else NOTE_TAP[lang])
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(reopen(context))
                .build(),
        )
    }

    private fun notifyDone(context: Context, summary: String) {
        val nm = manager(context) ?: return
        val lang = lang(context)
        val cuts = _state.value.cuts
        val text = listOfNotNull(NOTE_CUTS[lang].format(cuts).takeIf { cuts > 0 }, summary.takeIf { it.isNotBlank() })
            .joinToString("\n")
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(NOTE_DONE[lang])
                .setContentText(text.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(reopen(context))
                .build(),
        )
    }

    /** Gone once the dialog has shown the result — it has nothing left to bring back. */
    fun clearNotification(context: Context) {
        if (running) return
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private val REPEATING_PHASES = setOf("counting", "reading")

    /** Tries in one run, the first included. */
    const val MAX_ATTEMPTS = 10
    const val RESTART_DELAY_MS = 10_000L
    const val BLUETOOTH_WAIT_MS = 60_000L

    private const val CHANNEL_ID = "opentasker.huawei.sync"
    private const val CHANNEL_NAME = "健康 sync"
    private const val NOTIFICATION_ID = 727_001

    private val CUT_LINE = Loc(
        "BLUETOOTH LINK CUT (Broken pipe) — %s. Restarting the sync in %d s (try %d).",
        "BLUETOOTH 接続切断（Broken pipe）— %s。%d 秒後に同期をやり直します（%d 回目）。",
    )
    private val CUT_ALL = Loc("nothing was read", "何も読めませんでした")
    private val CUT_LOST = Loc("not read: %s", "読めなかったもの: %s")
    private val CANCELLED = Loc("Cancelled.", "中止しました。")
    private val NOTE_RUNNING = Loc("健康: sync running", "健康: 同期中")
    private val NOTE_DONE = Loc("健康: sync finished", "健康: 同期が終わりました")
    private val NOTE_TAP = Loc("Tap to watch it.", "押すと進み具合を表示します。")
    private val NOTE_CUTS = Loc("Bluetooth link cut %d× — restarted.", "Bluetooth 接続が %d 回切れ、やり直しました。")
}
