package com.opentasker.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.shizuku.ShizukuShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Export a sister app through its **data door** (§2a) instead of the §1 broadcast — we open the file,
 * the app writes into our descriptor.
 *
 * ## Why this exists (白い熊, 2026-09-05)
 *
 * The §1 path hands the app an absolute **path** and asks it to write there itself, which needs
 * All-Files-Access in every app that is backed up. That grant is exactly what the data door was built
 * to remove: the payload crosses on a `ParcelFileDescriptor` **this** app opens, so the callee needs
 * no storage permission at all and `ERROR:no-storage-access` cannot arise. Demanding the grant app by
 * app was, in 白い熊's words, "all wrong".
 *
 * ## The foreground-start refusal, pre-empted rather than reported
 *
 * A broadcast is a background start, so a callee's `startForegroundService` is refused whenever the
 * app has had no recent foreground allowance — the unattended batch case, and never when the app was
 * just opened by hand. Measured across the nine roster apps on 2026-09-05: one answered the reserved
 * `ERROR:no-foreground-start`, one answered the raw platform exception, and **four died without
 * replying at all** — a 20-second silence that reads as "no receiver" and is nothing of the kind. The
 * same app, brought to the foreground first, exported in 224 ms.
 *
 * So this action grants the target a **temporary** allowance before calling
 * (`cmd deviceidle tempwhitelist -d <ms> <pkg>` through Shizuku) rather than reporting a refusal
 * afterwards. Temporary on purpose: it expires on its own, so a crash here cannot leave a permanent
 * battery-optimisation exemption behind on 白い熊's phone. 応用管理 pre-empts the same refusal the
 * same way. Without Shizuku the call still runs — an app that was recently in the foreground will
 * work — and the reason is logged rather than hidden.
 *
 * ## Correlation
 *
 * **The callee mints the job id**; `OK:<job_id>` from `call()` is the correlation key, never an id we
 * invent (§2a of the contract). A terminal reply can also arrive *before* `call()` returns — a small
 * export finishes on the service thread while the binder call is still unwinding — so replies are
 * parked by id from the moment the receiver is registered and claimed once the id is known.
 *
 * Args: `package` (required), `dir` + `basename` **or** `path`, `items`, `token`, `progress_action`,
 * `timeout` (s, default 600), `store` (variable prefix, default `bk`), `preempt` (`false` to skip the
 * temporary allowance).
 *
 * Writes `<store>_line` (the terminal reply verbatim), `<store>_ok` (`x` on success, empty otherwise),
 * `<store>_path`, `<store>_bytes`, `<store>_size` (display size in §1's grammar), `<store>_job`.
 */
class BackupDoorExportAction : Action {
    override val id = "backup.export"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        fun arg(name: String) = ctx.variables.expand(args[name].orEmpty()).trim()

        val pkg = arg("package")
        if (pkg.isEmpty()) return ActionResult.Failure("package is required")
        val store = arg("store").removePrefix("%").ifEmpty { "bk" }
        val timeoutSec = arg("timeout").toIntOrNull()?.coerceIn(1, 3600) ?: 600

        val target = destinationFor(arg("path"), arg("dir"), arg("basename"), pkg)
            ?: return fail(ctx, store, "ERROR:no destination — give path, or dir (+ basename)")
        runCatching { target.parentFile?.mkdirs() }

        // Temporary foreground-start allowance, valid a little longer than we are willing to wait.
        val allowance = grantTempAllowance(pkg, (timeoutSec + 30) * 1000L)
        if (allowance != null) ctx.logger(allowance)

        val replyAction = "shiroikuma.jiyusagyoban.action.DOOR_REPLY"
        val inbox = ReplyInbox()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val result = intent?.getStringExtra("result") ?: return
                val job = intent.getStringExtra("job_id") ?: intent.getStringExtra("reply_id") ?: ""
                inbox.offer(job, result)
            }
        }
        ContextCompat.registerReceiver(
            ctx.app, receiver, IntentFilter(replyAction), ContextCompat.RECEIVER_EXPORTED,
        )

        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(
                    target,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                )
            }
            val extras = Bundle().apply {
                putParcelable("fd", descriptor)
                putString("reply_action", replyAction)
                putString("reply_package", ctx.app.packageName)
                arg("items").takeIf { it.isNotEmpty() }?.let { putString("items", it) }
                arg("token").takeIf { it.isNotEmpty() }?.let { putString("token", it) }
                arg("progress_action").takeIf { it.isNotEmpty() }?.let { putString("progress_action", it) }
            }

            val answer = withContext(Dispatchers.IO) { callExport(ctx, pkg, extras) }

            if (!answer.startsWith("OK:")) {
                target.delete()
                return fail(ctx, store, answer)
            }
            val jobId = answer.removePrefix("OK:").trim()
            ctx.variables.set("${store}_job", jobId)
            inbox.claim(jobId)

            val terminal = withTimeoutOrNull(timeoutSec * 1000L) { inbox.await() }
            if (terminal == null) {
                // Ask it to stop before giving up, so a still-running export does not keep writing
                // into a descriptor nobody is waiting on any more.
                runCatching {
                    ctx.app.contentResolver.call(
                        Uri.parse("content://$pkg.automation"), "cancel", null,
                        Bundle().apply { putString("job_id", jobId) },
                    )
                }
                target.delete()
                return fail(ctx, store, "ERROR:timeout — no reply in ${timeoutSec}s")
            }
            if (!terminal.startsWith("OK:")) {
                target.delete()
                return fail(ctx, store, terminal)
            }

            // Close before reading it back: the app writes through our descriptor and the bytes are
            // only certainly on disk once both ends are shut.
            descriptor.close()
            descriptor = null
            if (!looksLikeACompleteZip(target)) {
                val kept = target.absolutePath
                target.delete()
                return fail(ctx, store, "ERROR:$pkg reported success but the archive is not a complete ZIP ($kept)")
            }

            val bytes = terminal.removePrefix("OK:").split("|").firstOrNull { it.trim().toLongOrNull() != null }
                ?.trim()?.toLong() ?: target.length()
            ctx.variables.set("${store}_line", terminal)
            ctx.variables.set("${store}_ok", "x")
            ctx.variables.set("${store}_path", target.absolutePath)
            ctx.variables.set("${store}_bytes", bytes.toString())
            ctx.variables.set("${store}_size", humanSize(bytes))
            ctx.logger("Door export $pkg → ${target.name} ($bytes bytes)")
            return ActionResult.Success
        } finally {
            runCatching { descriptor?.close() }
            runCatching { ctx.app.unregisterReceiver(receiver) }
        }
    }

    /**
     * Call `export`, retrying while the authority is still unknown.
     *
     * A package the batch has just thawed (`pm enable` after `pm disable-user`) does not become
     * visible to us the instant the command returns: the provider is republished and our process's
     * package-visibility view catches up a moment later, so the first call answers
     * *Unknown authority*. Measured 2026-09-05 on 人造人間 — frozen, thawed by the task, and still
     * unreachable 3 s later, while a probe from another process at the same moment was answered
     * correctly. Any other failure is returned immediately; there is nothing to wait for.
     */
    private fun callExport(ctx: ActionContext, pkg: String, extras: Bundle): String {
        val uri = Uri.parse("content://$pkg.automation")
        var last = "ERROR:door answered nothing"
        repeat(6) { attempt ->
            val outcome = runCatching {
                ctx.app.contentResolver.call(uri, "export", null, extras)?.getString("result")
            }
            outcome.getOrNull()?.let { return it }
            val error = outcome.exceptionOrNull()
            last = "ERROR:door unreachable — ${error?.message ?: "no answer"}"
            val unknownAuthority = error?.message?.contains("Unknown authority", ignoreCase = true) == true
            if (!unknownAuthority) return last
            Thread.sleep(1500L * (attempt + 1))
        }
        return last
    }

    /**
     * Is this a whole ZIP? — the completeness check, done by content rather than by name.
     *
     * The archive is written straight to its final name: a `.part`-then-rename dance looked safer and
     * was not, because `File.renameTo` on the emulated volume **failed persistently** for a 1.17 GB
     * archive (measured 2026-09-05 on 辞書, ten retries over 16 s), which would have thrown away a
     * finished backup over its name. Reading the end of the file is a stronger guarantee anyway: it
     * catches a callee that reports success over a truncated archive, which a rename never could.
     *
     * A complete ZIP ends with the end-of-central-directory record `50 4b 05 06`, at most 64 KB from
     * the end (the trailing comment is that long at most) — the same signature §1 of the contract
     * names for telling a real backup from a truncated one.
     */
    private suspend fun looksLikeACompleteZip(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val length = file.length()
            if (length < 22) return@runCatching false
            val window = minOf(length, 64L * 1024 + 22)
            val tail = ByteArray(window.toInt())
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - window)
                raf.readFully(tail)
            }
            (0..tail.size - 4).any { i ->
                tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            }
        }.getOrDefault(false)
    }

    private fun fail(ctx: ActionContext, store: String, line: String): ActionResult {
        ctx.variables.set("${store}_line", line)
        ctx.variables.set("${store}_ok", "")
        ctx.variables.set("${store}_path", "")
        ctx.variables.set("${store}_bytes", "0")
        ctx.variables.set("${store}_size", "")
        return ActionResult.Failure(line)
    }

    /**
     * The display size, in §1's own grammar (`4.6 MB`, `1.20 GB`) — the door reports bytes and the
     * progress panel's row shows a size, so the two must read alike whichever transport wrote them.
     */
    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * The file we hand over, named the family way — `<basename>_<yyyy-MM-dd_HH-mm-ss>.zip` — unless
     * an explicit `path` says otherwise. `basename` defaults to the package's last segment prefixed
     * `shiroikuma-`, which is what the sister repos are called.
     */
    private fun destinationFor(path: String, dir: String, basename: String, pkg: String): File? {
        if (path.isNotEmpty()) return File(path)
        if (dir.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val name = basename.ifEmpty { "shiroikuma-" + pkg.substringAfterLast('.') }
        return File(dir, "${name}_$stamp.zip")
    }

    /**
     * Put the target on the temporary power allowlist so its foreground service may start from our
     * background call. Returns a line for the run log, or null when nothing was attempted.
     */
    private fun grantTempAllowance(pkg: String, durationMs: Long): String? {
        if (!ShizukuShell.available()) {
            return "Shizuku unavailable — no foreground-start allowance for $pkg; a refusal is likely " +
                "unless it was recently open"
        }
        val result = runCatching {
            ShizukuShell.exec("cmd deviceidle tempwhitelist -d $durationMs $pkg")
        }.getOrNull() ?: return "could not grant $pkg a temporary foreground-start allowance"
        return if (result.exitCode == 0) null
        else "tempwhitelist refused for $pkg: ${result.stderr.trim().take(120)}"
    }

    /**
     * Terminal replies, parked by job id until the id we are waiting for is known.
     *
     * A fast export answers **before `call()` returns**, so a caller that only starts listening after
     * it has the id loses exactly the quickest jobs. A reply carrying no id at all is accepted too:
     * it can only belong to the one job this action started.
     */
    private class ReplyInbox {
        private val lock = Any()
        private val parked = HashMap<String, String>()
        private val settled = CompletableDeferred<String>()
        private var expected: String? = null

        fun offer(jobId: String, result: String) = synchronized(lock) {
            val want = expected
            if (want != null && (jobId == want || jobId.isEmpty())) settled.complete(result)
            else parked[jobId] = result
            Unit
        }

        fun claim(jobId: String) = synchronized(lock) {
            expected = jobId
            (parked[jobId] ?: parked[""])?.let { settled.complete(it) }
            Unit
        }

        suspend fun await(): String = settled.await()
    }
}
