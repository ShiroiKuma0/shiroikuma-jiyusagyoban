package com.opentasker.core.scheduling

import com.sysadmindoc.ruledeck.core.scheduling.*
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.opentasker.core.engine.AutomationService
import com.sysadmindoc.ruledeck.core.engine.EngineShutdown
import com.sysadmindoc.ruledeck.core.engine.executeAndLogTask
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A task run at one moment by its own exact alarm (`task.alarm.set` / `task.alarm.cancel`).
 *
 * Why not a `Wait`, and why not a minute profile: the engine holds no wakelock, so on an unplugged
 * phone with its screen off a `Wait` stands still while the CPU sleeps, and the minute pulse is a
 * coroutine that sees a minute boundary only if the CPU happens to be awake for it — a slot can be
 * late or skipped. An alarm set with `setExactAndAllowWhileIdle` wakes the phone at the second, in
 * Doze too, and costs nothing until then.
 *
 * - **One alarm per key.** Setting a key again replaces its alarm; cancelling forgets it.
 * - **Kept across a restart.** The orders live in SharedPreferences. Android drops an app's alarms
 *   at a reboot, a force-stop and an update, so [sweep] — called from the engine's per-minute tick —
 *   sets them again once per process and runs whatever is overdue.
 * - **One shot.** The order is removed as it fires, before the task starts, so that the task may set
 *   the same key again for its next turn.
 * - The receiver holds a short wakelock of its own across the gap between the alarm and the task's
 *   first step; a task that has to finish in real time asks for `power.keep_awake` itself.
 */
object TaskAlarms {
    private const val TAG = "TaskAlarms"
    private const val PREFS = "task_alarms"
    internal const val ACTION_FIRE = "com.opentasker.scheduling.TASK_ALARM"
    internal const val EXTRA_KEY = "key"
    private const val REQUEST = 13_021
    private const val BRIDGE_LOCK_MS = 30_000L
    private const val LOCK_TAG = "opentasker:task-alarm"

    /** An alarm that is this late is run by [sweep]: its own alarm was lost, or is not coming. */
    internal const val LATE_MS = 10_000L

    /** Never fire an order before its time: an alarm left over from a replaced order arrives early. */
    internal const val EARLY_TOLERANCE_MS = 1_000L

    data class Order(
        val key: String,
        val task: String,
        val project: String,
        val atMs: Long,
        val params: Map<String, String>,
    ) {
        fun toJson(): String = JSONObject()
            .put("task", task)
            .put("project", project)
            .put("at", atMs)
            .put("params", JSONObject(params))
            .toString()

        companion object {
            fun parse(key: String, raw: String): Order? = runCatching {
                val json = JSONObject(raw)
                val params = json.optJSONObject("params") ?: JSONObject()
                Order(
                    key = key,
                    task = json.getString("task"),
                    project = json.optString("project"),
                    atMs = json.getLong("at"),
                    params = params.keys().asSequence().associateWith { params.getString(it) },
                )
            }.getOrNull()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var rearmed = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun set(context: Context, order: Order) {
        val app = context.applicationContext
        synchronized(lock) {
            // commit(), not apply(): the alarm may be the next thing this process lives to see.
            prefs(app).edit().putString(order.key, order.toJson()).commit()
        }
        arm(app, order)
        AppLogger.info(TAG, "Alarm '${order.key}' → '${order.task}' in ${(order.atMs - System.currentTimeMillis()) / 1000} s")
    }

    /** True when there was an order under [key]. */
    fun cancel(context: Context, key: String): Boolean {
        val app = context.applicationContext
        val had = synchronized(lock) {
            val prefs = prefs(app)
            prefs.contains(key).also { if (it) prefs.edit().remove(key).commit() }
        }
        app.getSystemService(AlarmManager::class.java)?.cancel(pending(app, key))
        return had
    }

    fun all(context: Context): List<Order> = synchronized(lock) {
        prefs(context).all.mapNotNull { (key, raw) -> (raw as? String)?.let { Order.parse(key, it) } }
    }

    /**
     * The per-minute tick's part: run what is overdue, and — once per process — set every waiting
     * alarm again, since a reboot, a force-stop or an update has dropped them.
     */
    fun sweep(context: Context) {
        val app = context.applicationContext
        val orders = all(app)
        if (orders.isEmpty()) {
            rearmed = true
            return
        }
        val now = System.currentTimeMillis()
        val (late, waiting) = splitLate(orders, now)
        if (!rearmed) {
            rearmed = true
            waiting.forEach { arm(app, it) }
        }
        late.forEach { fire(app, it.key, "late") }
    }

    internal fun splitLate(orders: List<Order>, nowMs: Long): Pair<List<Order>, List<Order>> =
        orders.partition { it.atMs + LATE_MS <= nowMs }

    /** The order under [key] if its time has come, removed as it is read; null otherwise. */
    private fun take(app: Context, key: String, nowMs: Long): Order? = synchronized(lock) {
        val prefs = prefs(app)
        val order = prefs.getString(key, null)?.let { Order.parse(key, it) } ?: return null
        if (!isDue(order, nowMs)) return null
        prefs.edit().remove(key).commit()
        order
    }

    internal fun isDue(order: Order, nowMs: Long): Boolean = order.atMs <= nowMs + EARLY_TOLERANCE_MS

    internal fun fire(context: Context, key: String, how: String) {
        val app = context.applicationContext
        if (EngineShutdown.isStopped(app)) {
            AppLogger.info(TAG, "Alarm '$key' not run — the engine is stopped; it stays for the next start")
            return
        }
        val now = System.currentTimeMillis()
        val order = take(app, key, now) ?: return
        // Bridges the gap until the task's own first step; the task asks for more itself.
        val bridge = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
                .apply { setReferenceCounted(false); acquire(BRIDGE_LOCK_MS) }
        }.getOrNull()
        scope.launch {
            try {
                (app as? RuleDeckApp_NoHilt)?.initializeAfterUnlock() ?: error("application unavailable")
                if (!AutomationService.isRunning) runCatching { AutomationService.start(app) }
                val db = RuleDeckApp_NoHilt.db
                val projectId = if (order.project.isEmpty()) null else
                    db.projectDao().getAll().firstOrNull { it.name.equals(order.project, ignoreCase = true) }?.id
                val matches = db.taskDao().getAll().filter {
                    it.name.equals(order.task, ignoreCase = true) && (projectId == null || it.projectId == projectId)
                }
                if (matches.size != 1) {
                    AppLogger.error(TAG, "Alarm '$key': ${matches.size} tasks are named '${order.task}' — not run", null)
                    return@launch
                }
                executeAndLogTask(
                    appContext = app,
                    db = db,
                    task = matches.single().toDomain(),
                    source = "task alarm ($how)",
                    initialVariables = order.params + mapOf(
                        "alarm_key" to key,
                        "alarm_due" to order.atMs.toString(),
                        "alarm_late" to ((now - order.atMs) / 1000).coerceAtLeast(0).toString(),
                    ),
                    logTag = TAG,
                )
            } catch (error: Exception) {
                AppLogger.error(TAG, "Alarm '$key' failed", error)
            } finally {
                runCatching { bridge?.takeIf { it.isHeld }?.release() }
            }
        }
    }

    private fun arm(app: Context, order: Order) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(app, order.key)
        when (ExactAlarmSupport.schedulePrecision(app)) {
            AlarmSchedulePrecision.Exact -> try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, order.atMs, pending)
            } catch (error: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, order.atMs, pending)
            }
            AlarmSchedulePrecision.InexactFallback ->
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, order.atMs, pending)
        }
    }

    /** One PendingIntent per key: the key is in the data URI, which is what tells two intents apart. */
    private fun pending(app: Context, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            REQUEST,
            Intent(app, TaskAlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(Uri.Builder().scheme("opentasker-task-alarm").authority("k").appendPath(key).build())
                .putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** The alarm of one [TaskAlarms] order. Not exported: only our own PendingIntent reaches it. */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskAlarms.ACTION_FIRE) return
        val key = intent.getStringExtra(TaskAlarms.EXTRA_KEY) ?: return
        TaskAlarms.fire(context, key, "on time")
    }
}
