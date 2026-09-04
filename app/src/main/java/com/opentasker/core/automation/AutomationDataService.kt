package com.opentasker.core.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.transfer.SettingsBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a data export or import actually runs.
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes. Two hard reasons it cannot be done
 * anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress, and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored (応用管理, 2026-09-04).
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FOREGROUND FIRST, before any decision that can return — including the decision to do
        // nothing. `startForegroundService` has already promised the platform we will go foreground
        // within its window, and the promise is not conditional on our finding work to do: skipping
        // it kills the process with ForegroundServiceDidNotStartInTimeException. So a caller
        // retrying with a stale job id would CRASH the app it is backing up rather than being
        // ignored. Guarded, because the start may itself be refused on API 31+ from a background
        // caller — and a throw here would be the very crash we are avoiding.
        //
        // `importing` is read defensively before the job id for the notification's sake: hoisting
        // the job-id read above this is the natural way to write it, and is exactly how five ports
        // acquired the crash on the null-intent branch. (Found by `shiroikuma-nekokan`; the
        // ordering below is what `shiroikuma-hogu`, `shiroikuma-jinsoningen`, `shiroikuma-denwa`
        // and `shiroikuma-shoruikanri` independently converged on.)
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) ?: false
        val wentForeground = runCatching {
            startForeground(NOTIFICATION_ID, notification(importing))
        }.isSuccess

        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)

        val replied = AtomicBoolean(false)
        // A `val` holding a lambda, NOT a local `fun`. See CountingStream below: a local function
        // and a local-capturing anonymous object in the same method crash AGP's lint analyser
        // ("FirDeclaration was not found for class KtProperty, fir is null"). Do not tidy this back.
        val reply: (String) -> Unit = { result ->
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire. The same guard the broadcast
            // contract has carried since the first sister app.
            if (replied.compareAndSet(false, true)) {
                AutomationJobs.finish(jobId)
                // No package to aim at means nobody can hear it: since API 26 an implicit broadcast
                // reaches no manifest-declared receiver, so `setPackage(null)` is not a wider send,
                // it is no send. Skip it rather than pretending.
                if (!replyAction.isNullOrEmpty() && !replyPackage.isNullOrEmpty()) {
                    sendBroadcast(
                        Intent(replyAction).apply {
                            setPackage(replyPackage)
                    // Without this a caller that has been backgrounded never hears the answer, and
                    // on a clean phone the caller may not have been launched at all.
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                            putExtra(AutomationProvider.KEY_RESULT, result)
                        },
                    )
                }
            }
        }

        // The descriptor has left HANDOVER by now, so if we never made it foreground nothing else
        // would ever close it — and the caller is holding an `OK:<job_id>` for work that cannot
        // run. Answer rather than die quietly: a silent death here shows up only on a phone without
        // the battery-optimisation exemption, which is precisely the clean-phone case.
        if (!wentForeground) {
            runCatching { fd.close() }
            reply("ERROR:cannot go foreground")
            return stop(startId)
        }

        scope.launch {
            try {
                fd.use { open ->
                    if (importing) runImport(open, reply = reply)
                    else runExport(jobId, open, intent.getStringExtra(AutomationProvider.KEY_ITEMS), reply)
                }
            } catch (t: Throwable) {
                reply("ERROR:${t.message ?: t::class.java.simpleName}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runExport(
        jobId: String,
        fd: ParcelFileDescriptor,
        items: String?,
        reply: (String) -> Unit,
    ) {
        val cats = resolve(items) ?: run { reply("ERROR:unknown category in items: $items"); return }
        // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we may not
        // be able to see it at all — it can be an anonymous pipe or a descriptor into a directory
        // this app cannot list.
        val counting = CountingStream(ParcelFileDescriptor.AutoCloseOutputStream(fd))
        counting.use {
            SettingsBackup.export(
                context = this,
                db = OpenTaskerApp_NoHilt.db,
                appVersion = versionName(),
                cats = cats,
                output = it,
                isCancelled = { AutomationJobs.isCancelled(jobId) },
            )
        }
        if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
        else reply("OK:${counting.written}|${cats.size} categories")
    }

    /**
     * Read the whole archive before touching anything.
     *
     * `SettingsBackup.import` wants the bytes, and that is the right shape here for a reason beyond
     * convenience: a partial read that failed halfway would otherwise import half an archive, and
     * a half-restored app is worse than one that refused.
     */
    private suspend fun runImport(fd: ParcelFileDescriptor, reply: (String) -> Unit) {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        if (bytes.isEmpty()) { reply("ERROR:empty archive"); return }
        // Every category the archive actually carries, not every category we know about: asking
        // for one the archive lacks is how a restore ends up reporting success over nothing.
        val present = SettingsBackup.categoriesIn(bytes)
        if (present.isEmpty()) { reply("ERROR:archive carries no categories"); return }
        val result = SettingsBackup.import(
            context = this,
            db = OpenTaskerApp_NoHilt.db,
            bytes = bytes,
            cats = present,
        )
        // The caller force-stops us straight after this. That is deliberate and belongs on its side:
        // a running process writes its cached SharedPreferences back out at orderly shutdown and
        // silently undoes the import that just happened (応用管理 paid for this one already).
        reply("OK:${result.summaryLines.size} restored")
    }

    private fun resolve(items: String?): Set<SettingsBackup.Cat>? {
        if (items.isNullOrBlank()) return SettingsBackup.Cat.entries.filter { it.defaultSelected }.toSet()
        val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val found = wanted.mapNotNull { id -> SettingsBackup.Cat.entries.firstOrNull { it.id == id } }
        return if (found.size == wanted.size) found.toSet() else null
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun notification(importing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (importing) "データを戻しています" else "データを書き出しています")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    /**
     * Leave, having satisfied the promise `startForegroundService` made on our behalf.
     *
     * **Every exit must go foreground first, including the ones with nothing to do.** Once a caller
     * has invoked `startForegroundService`, the platform requires this service to call
     * `startForeground` within its window whatever it then decides — and killing the process with
     * `ForegroundServiceDidNotStartInTimeException` is how it enforces that. So the early returns
     * above (no intent, unknown job, an entry already drained from HANDOVER) are exactly the
     * dangerous ones: **a caller retrying with a stale job id would crash the target app** rather
     * than being quietly ignored. Guarded, because by this point the start may be refused anyway,
     * and a throw here would be the very crash we are avoiding. (`shiroikuma-nekokan`, which found
     * it in this reference as well as its own port.)
     */
    private fun stop(startId: Int): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    /**
     * Counts bytes on their way into the caller's descriptor.
     *
     * A NAMED class holding `written` as its own property, deliberately — not an anonymous
     * `object : OutputStream()` capturing a local `var`. Combined with a local `fun` in the same
     * method that crashes AGP's lint analyser outright: `lintVitalAnalyzeRelease` dies with
     * *"FirDeclaration was not found for class KtProperty, fir is null"*, which takes
     * `assembleRelease` with it AFTER Kotlin has compiled cleanly — so the failure arrives ten
     * minutes in and does not look like a source problem. Found by `shiroikuma-shutokukanri` on this
     * exact file. `StateExportReceiver` has always used this shape and has always linted clean.
     */
    private class CountingStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
        var written: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    companion object {
        private const val CHANNEL = "automation_data"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — the service, which
         * closes it in a `finally`.
         */
        private val HANDOVER = java.util.concurrent.ConcurrentHashMap<String, ParcelFileDescriptor>()

        /** How long a claimed job may sit undelivered before its descriptor is reclaimed. */
        private const val UNDELIVERED_SECONDS = 60L

        private val REAPER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "automation-handover-reaper").apply { isDaemon = true }
            }

        /**
         * Reclaim a descriptor whose service never arrived.
         *
         * A no-op in the normal case: `onStartCommand` removes the entry within milliseconds, so
         * there is nothing left to find. It only fires when the start was accepted and never
         * delivered.
         */
        private fun abandon(jobId: String) {
            val stranded = HANDOVER.remove(jobId) ?: return
            runCatching { stranded.close() }
            AutomationJobs.finish(jobId)
        }

        /**
         * Claim the job and hand the descriptor over.
         *
         * @return null when the service is running, or the `ERROR:` line to answer the caller with.
         *   On a failure the descriptor is **already closed here** — the provider must not close it
         *   a second time.
         */
        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?,
        ): String? {
            HANDOVER[jobId] = fd
            return try {
                context.startForegroundService(intentFor(context, jobId, importing, extras))
                // A start can also be ACCEPTED and never DELIVERED — the system drops it, the
                // process is killed between the two, EMUI decides otherwise. Nothing throws, so
                // neither guard above fires, and the caller's descriptor sits in HANDOVER held open
                // for the life of the process while the caller waits for a reply that cannot come.
                // Bounded rather than unbounded, but a caller cannot checksum a file we still hold.
                // (`shiroikuma-kagiango` found this in its own port and in this reference.)
                REAPER.schedule({ abandon(jobId) }, UNDELIVERED_SECONDS, TimeUnit.SECONDS)
                null
            } catch (e: Exception) {
                // A provider `call()` is a BACKGROUND start, and API 31+ refuses one with
                // `ForegroundServiceStartNotAllowedException` unless the app is exempt from battery
                // optimisation. Left unguarded this strands the caller's open descriptor in
                // HANDOVER with nothing alive to close it, AND throws out of `call()` across the
                // binder as a RuntimeException — which §2a forbids: a refusal is returned, never
                // thrown. The descriptor is closed HERE, so the provider must not close it again.
                HANDOVER.remove(jobId)
                runCatching { fd.close() }
                "ERROR:cannot start data service: ${e.javaClass.simpleName}"
            }
        }

        private fun intentFor(
            context: Context,
            jobId: String,
            importing: Boolean,
            extras: Bundle?,
        ): Intent =
            Intent(context, AutomationDataService::class.java).apply {
                putExtra(EXTRA_JOB, jobId)
                putExtra(EXTRA_IMPORTING, importing)
                putExtra(AutomationProvider.KEY_ITEMS, extras?.getString(AutomationProvider.KEY_ITEMS))
                putExtra(
                    AutomationProvider.KEY_REPLY_ACTION,
                    extras?.getString(AutomationProvider.KEY_REPLY_ACTION),
                )
                putExtra(
                    AutomationProvider.KEY_REPLY_PACKAGE,
                    extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE),
                )
            }
    }
}
