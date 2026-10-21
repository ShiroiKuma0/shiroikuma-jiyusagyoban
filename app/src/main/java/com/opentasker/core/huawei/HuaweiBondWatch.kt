package com.opentasker.core.huawei

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
import com.opentasker.app.R
import com.opentasker.core.actions.NotificationActionReceiver
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.shizuku.ShizukuShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notices, within a minute, that Android has forgotten the watch — and keeps the evidence of why.
 *
 * ## Why this exists — 2026-10-20
 *
 * The 16:00 sync worked; at 20:00 the phone no longer listed the watch as bonded, and the next four
 * syncs failed on it unseen until 10:21 the next morning. The watch itself still knew the phone
 * (re-pairing took the short path), our app had not removed it (時計解除 is the only way it does, behind
 * a confirmation), and Health is not installed. What would have said WHO removed it — the Bluetooth
 * stack's "Bond Events" table in `dumpsys bluetooth_manager`, which names `btif_dm_remove_bond` for an
 * app or Settings asking and a bare `bond_state_changed` for the stack dropping it on its own — lives
 * only in memory, and the Bluetooth off/on before the re-pair wiped it. logcat had rotated too.
 *
 * So on the minute the bond is seen gone this saves both, through Shizuku (uid 2000 may read them),
 * into `bond-lost_<stamp>.txt` beside `keygrab-log.txt` in the app's external files directory, and
 * posts a notification that runs `時計接続（D3） -- [727]` when tapped. With the evidence on disk,
 * turning Bluetooth off and on afterwards costs nothing.
 *
 * ## What counts
 *
 * - Watched only while our HiChain bind exists: 時計解除 clears the bind BEFORE it removes the bond,
 *   so a deliberate unpair never reads as a loss.
 * - Read only while the adapter is on: Android answers BOND_NONE for every device while it is off.
 * - BONDING is not a loss (re-pairing passes through it); only BOND_NONE is, and it is read again
 *   after [CONFIRM_MS] before anything is written.
 * - The last state is kept in preferences, so a bond lost while the engine was down is still caught
 *   at the first tick after, with the span it happened in.
 *
 * Driven by the engine's per-minute exact alarm ([com.opentasker.automation.receiver.TimeEventReceiver]),
 * because the engine holds no wakelock and a timer loop would stall in Doze.
 */
object HuaweiBondWatch {

    const val RECONNECT_TASK = "時計接続（D3） -- [727]"
    const val LOG_NAME = "bond-log.txt"
    private const val LOG_LINES = 300

    /** The second read that turns a missing bond into a loss. */
    private const val CONFIRM_MS = 5_000L

    /** How far before the last bonded sighting the saved logcat starts. */
    private const val LOGCAT_BEFORE_MS = 2 * 60_000L
    private const val LOGCAT_MAX_LINES = 8_000

    private const val TAG = "HuaweiBondWatch"
    private const val PREFS = "huawei_bond_watch"
    private const val KEY_STATE = "bond_state"
    private const val KEY_SEEN = "seen_ms"
    private const val KEY_LOST = "lost_ms"
    private const val NOTIFICATION_ID = 7312
    private const val CHANNEL_ID = "opentasker.urgent"
    private const val CHANNEL_NAME = "白い熊 自由作業盤 urgent"
    private const val REQUEST_TASK = 13131

    enum class State { BONDED, LOST, UNWATCHED }

    sealed class Change {
        /** Seen gone at [noticedMs]; last seen bonded at [seenMs] (0 when never seen). */
        data class Lost(val address: String, val seenMs: Long, val noticedMs: Long) : Change()
        data class Back(val lostMs: Long, val backMs: Long) : Change()
        /** Watching began (first tick with a bind and a bond) — one line, so the log shows it runs. */
        data class Watching(val address: String) : Change()
    }

    /**
     * The decision, kept free of Android so it is the part under test.
     *
     * @param bond null when it cannot be read (adapter off), else the Android bond state.
     * @return the new state, and the change to act on if there is one.
     */
    internal fun step(state: State, watched: Boolean, bond: Int?): Pair<State, ChangeKind?> = when {
        !watched -> State.UNWATCHED to null
        bond == null -> state to null
        bond == HuaweiRfcommClient.BOND_BONDED -> State.BONDED to when (state) {
            State.LOST -> ChangeKind.BACK
            State.UNWATCHED -> ChangeKind.WATCHING
            State.BONDED -> null
        }
        bond == HuaweiRfcommClient.BOND_NONE -> when (state) {
            State.BONDED -> State.LOST to ChangeKind.LOST
            else -> state to null
        }
        else -> state to null // BONDING: a pairing in progress, not a verdict
    }

    internal enum class ChangeKind { LOST, BACK, WATCHING }

    /**
     * The cheap half, run on every tick: one bond-state read. Returns a change for [record] to act
     * on, which is the slow half and runs off the receiver's thread.
     */
    fun check(context: Context, now: Long = System.currentTimeMillis()): Change? {
        val app = context.applicationContext
        val p = prefs(app)
        val address = HuaweiSettings.address(app)
        val watched = address.isNotEmpty() && HuaweiSettings.isBound(app, address)
        val state = runCatching { State.valueOf(p.getString(KEY_STATE, null) ?: "") }
            .getOrDefault(State.UNWATCHED)
        val bond = if (watched) bondState(app, address) else null
        val (next, kind) = step(state, watched, bond)
        val seen = p.getLong(KEY_SEEN, 0L)
        p.edit().apply {
            putString(KEY_STATE, next.name)
            if (next == State.BONDED) putLong(KEY_SEEN, now)
            if (kind == ChangeKind.LOST) putLong(KEY_LOST, now)
        }.apply()
        return when (kind) {
            ChangeKind.LOST -> Change.Lost(address, seen, now)
            ChangeKind.BACK -> Change.Back(p.getLong(KEY_LOST, 0L), now)
            ChangeKind.WATCHING -> Change.Watching(address)
            null -> null
        }
    }

    /** The slow half: confirm, save the evidence, say so. Blocking — never on the main thread. */
    fun record(context: Context, change: Change) {
        val app = context.applicationContext
        when (change) {
            is Change.Watching -> log(app, "watching ${change.address} — paired; checked every minute")
            is Change.Back -> {
                log(app, "paired again at ${local(change.backMs)} (lost since ${local(change.lostMs)})")
                app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }
            is Change.Lost -> {
                Thread.sleep(CONFIRM_MS)
                if (bondState(app, change.address) != HuaweiRfcommClient.BOND_NONE) {
                    // Put the state back so the next real loss is still a transition.
                    prefs(app).edit().putString(KEY_STATE, State.BONDED.name).apply()
                    log(app, "bond read as NONE at ${local(change.noticedMs)} but was back ${CONFIRM_MS / 1000} s later — ignored")
                    return
                }
                val file = capture(app, change)
                log(
                    app,
                    "LOST — noticed ${local(change.noticedMs)}, last seen paired " +
                        (if (change.seenMs > 0) local(change.seenMs) else "never") +
                        " · evidence ${file?.name ?: "NOT saved"}",
                )
                notifyLost(app, change, file)
            }
        }
    }

    private fun bondState(context: Context, address: String): Int? =
        HuaweiRfcommClient.bondStateOf(context, address)

    /** `dumpsys bluetooth_manager`, `dumpsys companiondevice` and the logcat around the loss, in one file. */
    private fun capture(context: Context, change: Change.Lost): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val file = File(dir, "bond-lost_${STAMP_FILE.format(Date(change.noticedMs))}.txt")
        val from = (if (change.seenMs > 0) change.seenMs else change.noticedMs) - LOGCAT_BEFORE_MS
        val text = buildString {
            append("Watch bond lost — noticed ${local(change.noticedMs)} local\n")
            append("last seen paired: ${if (change.seenMs > 0) local(change.seenMs) else "never"}\n")
            append("address: ${change.address}\n")
            append("bonded devices now: ${bondedNames(context)}\n\n")
            append("Read \"Bond Events\": btif_dm_remove_bond = an app or Settings asked; ")
            append("bond_state_changed → NONE alone = the stack dropped it.\n\n")
            if (!ShizukuShell.available()) {
                append("Shizuku not available — dumpsys and logcat could not be read.\n")
            } else {
                section("dumpsys bluetooth_manager", "dumpsys bluetooth_manager")
                section("dumpsys companiondevice", "dumpsys companiondevice")
                val since = SimpleDateFormat("MM-dd HH:mm:ss.000", Locale.ROOT).format(Date(from))
                section(
                    "logcat -b all since $since",
                    "timeout 30 logcat -b all -d -v time -t '$since' | " +
                        "grep -v -E 'snet_event|ScanManager|HwBluetoothLeRanging' | tail -n $LOGCAT_MAX_LINES",
                )
            }
        }
        return runCatching { file.writeText(text); file }
            .onFailure { AppLogger.error(TAG, "Could not save the bond evidence", it) }
            .getOrNull()
    }

    private fun StringBuilder.section(title: String, command: String) {
        append("===== ").append(title).append(" =====\n")
        runCatching { ShizukuShell.exec(command) }
            .onSuccess { r ->
                append(r.stdout)
                if (r.stderr.isNotBlank()) append("[stderr] ").append(r.stderr)
            }
            .onFailure { append("[failed] ").append(it.message) }
        append("\n\n")
    }

    private fun bondedNames(context: Context): String = HuaweiRfcommClient.bondedDevices(context)

    private fun notifyLost(context: Context, change: Change.Lost, file: File?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.warn(TAG, "No notification permission — the lost bond went unreported")
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
        )
        val seen = if (change.seenMs > 0) hhmm(change.seenMs) else "—"
        val at = hhmm(change.noticedMs)
        val text = buildString {
            append("$at に時計の接続が外れているのに気づきました（最後に確認できたのは $seen）。")
            append("同期はできません。押すと「$RECONNECT_TASK」が走ります。\n")
            append(if (file != null) "記録は ${file.name} に保存済み — Bluetooth を切り替えても大丈夫です。\n\n"
            else "記録は保存できませんでした。\n\n")
            append("The watch was found unpaired at $at (last seen paired $seen). No sync can run until ")
            append("it is paired again — tapping this runs 「$RECONNECT_TASK」.\n")
            append(if (file != null) "The evidence is saved in ${file.name}, so toggling Bluetooth loses nothing."
            else "The evidence could not be saved.")
        }
        val tap = PendingIntent.getBroadcast(
            context,
            REQUEST_TASK,
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_NOTIFICATION_BUTTON)
                .putExtra(NotificationActionReceiver.EXTRA_TASK_NAME, RECONNECT_TASK)
                .putExtra(NotificationActionReceiver.EXTRA_BUTTON_LABEL, RECONNECT_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle("時計の接続が外れました（$at）/ Watch unpaired")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun log(context: Context, message: String) {
        AppLogger.warn(TAG, message)
        val dir = context.getExternalFilesDir(null) ?: return
        runCatching {
            val f = File(dir, LOG_NAME)
            val lines = (if (f.isFile) f.readLines() else emptyList()) + "${STAMP.format(Date())}  $message"
            f.writeText(lines.takeLast(LOG_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val STAMP get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val STAMP_FILE get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
    private fun local(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
    private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))
}
