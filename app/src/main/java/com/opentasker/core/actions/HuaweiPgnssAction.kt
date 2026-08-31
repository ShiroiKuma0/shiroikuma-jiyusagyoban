package com.opentasker.core.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.pgnss.PgnssBuildConfig
import com.opentasker.core.huawei.pgnss.PgnssBuildResult
import com.opentasker.core.huawei.pgnss.PgnssCancelledException
import com.opentasker.core.huawei.pgnss.PgnssProgress
import com.opentasker.core.huawei.pgnss.PgnssStep
import com.opentasker.core.huawei.pgnss.PredictedSet
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * `Huawei Band predicted ephemeris` — build the band's 72-hour satellite forecast on the phone.
 *
 * The band fixes in about twenty seconds when it holds a predicted-ephemeris set and takes minutes
 * when it does not. Huawei's own endpoint for that set is signed with a credential issued at
 * runtime to Health's package and certificate, so it cannot be fetched; the set is therefore MADE
 * here, from free orbit products, and written into the same store [HuaweiGnssAction] serves from.
 *
 * All of the arithmetic lives in [PredictedSet] and the package below it. This action is the
 * Android half: a battery gate, a wake lock, a progress contract, and the store path.
 *
 * ## It always downloads
 * There is no cache and no metered-connection special case — 白い熊 decided that explicitly. A
 * cached orbit product is a WRONG orbit product a day later, and the failure that causes is a build
 * that succeeds and produces a plausible file whose window has already closed. That exact failure
 * shipped an expired BeiDou file on 2026-08-30 while reporting success, which is why nothing in
 * this path is allowed to fall back to anything.
 *
 * ## The progress contract
 * Every variable this publishes is specified in `docs/huawei-pgnss-progress-contract.md` and the
 * 衛星 panel binds to those names. Do not rename one without changing both.
 */
class HuaweiPgnssAction : Action {
    override val id = "huawei.pgnss"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val panel = Panel(ctx, prefix)
        panel.start()

        // `http.request` confines output_file to the app's own user_files, MIRRORING the path
        // underneath it, and [HuaweiGnssAction] reads that mirror in preference to anything on
        // shared storage. So this writes exactly where that action looks, and the two halves of the
        // feature name the same folder with the same string.
        val dir = args["dir"]?.trim()?.ifEmpty { null } ?: DEFAULT_DIR
        val outDir = File(File(ctx.app.filesDir, "user_files"), dir.trimStart('/', '\\'))
        val workDir = args["work"]?.trim()?.ifEmpty { null }?.let { File(it) }
            ?: File(ctx.app.cacheDir, "pgnss")

        val force = args["force"]?.trim()?.lowercase() in TRUE_WORDS
        val battery = batteryPercent(ctx.app)
        if (!force && battery in 0 until MIN_BATTERY_PERCENT) {
            // Refused on charge, never on CHARGING: this is a ten-minute job on all but two cores
            // and it should not be started on a nearly flat phone by an unattended profile. It does
            // not need a charger, and demanding one would make the refresh a chore that never gets
            // done.
            return panel.fail(
                store, 1,
                "the battery is at $battery % and this is several minutes of every core — " +
                    "charge it, or tick “Run it anyway”",
            )
        }

        val cancelVar = args["cancel_var"]?.trim()?.ifEmpty { null }
        cancelVar?.let { ctx.variables.set(it, "0") }

        val power = ctx.app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // A PARTIAL wake lock, and nothing more: the screen is irrelevant here. Without one the
        // build is a CPU-bound job with no foreground component, so EMUI suspends it the moment the
        // screen goes off and it resumes minutes later having lost nothing except the fresh window
        // it was building. `acquire(ms)` rather than the bare form, so a crash that skipped the
        // `finally` cannot hold the CPU awake until the next reboot.
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(WAKE_LOCK_MS)
        try {
            val result = coroutineScope {
                // PgnssElapsed is the one value that ticks on a clock rather than on progress: a
                // 25 MB download over a slow FTP mirror reports nothing for minutes and a panel
                // with no moving part in it reads as a hang.
                val ticker = launch { while (isActive) { panel.tick(); delay(TICK_MS) } }
                try {
                    // Our own ceiling, below the engine's, so the message the panel shows is this
                    // one and not `TaskRunner` killing the action where it stands.
                    withTimeout(MAX_RUN_MS) {
                        PredictedSet.build(
                            workDir = workDir,
                            outDir = outDir,
                            config = PgnssBuildConfig(),
                            dispatcher = Dispatchers.Default,
                            cancelled = {
                                cancelVar != null && ctx.variables.get(cancelVar)?.trim() == "1"
                            },
                            progress = panel::publish,
                        )
                    }
                } finally {
                    ticker.cancel()
                }
            }
            panel.finish(result)
            store?.let { ctx.variables.set(it, result.summary) }
            ctx.logger("Huawei predicted ephemeris: ${result.summary}")
            for (note in result.notes) ctx.logger("  $note")
            return ActionResult.Success
        } catch (cancelled: PgnssCancelledException) {
            return panel.fail(store, panel.step, "cancelled")
        } catch (timeout: TimeoutCancellationException) {
            return panel.fail(
                store, panel.step,
                "gave up after ${MAX_RUN_MS / 60_000} minutes — ${panel.phase.ifEmpty { "no progress" }}",
            )
        } catch (stopped: CancellationException) {
            // The engine or the user stopped the task. Propagate: a coroutine that swallows its own
            // cancellation goes on running as though nothing happened. The `finally` below is what
            // actually matters, and it must not hop dispatchers to do its work.
            throw stopped
        } catch (error: Throwable) {
            return panel.fail(store, panel.step, error.message ?: error::class.java.simpleName)
        } finally {
            // NonCancellable, for the reason spelled out in HuaweiSessionGuard: `withContext` calls
            // `ensureActive()` BEFORE it runs anything, so cleanup that hops dispatchers throws
            // instead of cleaning up once the coroutine has been cancelled — silently, when it is
            // wrapped in runCatching as cleanup usually is.
            withContext(NonCancellable) {
                runCatching { wakeLock.release() }
                // The scratch is deleted every time, success or failure. There is no cache here on
                // purpose, and a 25 MB input left behind is one a future run might be tempted to
                // reuse — which is the whole failure this feature exists to stop.
                runCatching { workDir.deleteRecursively() }
            }
        }
    }

    /**
     * The four steps and ten variables of `docs/huawei-pgnss-progress-contract.md`.
     *
     * Steps 3 (On the band) and 4 (Transferred) belong to `huawei.gnss` and stay `wait` here; this
     * action never moves them, and it never moves a step backwards either.
     */
    internal class Panel(private val ctx: ActionContext, private val prefix: String) {
        private val startedAt = System.currentTimeMillis()
        private val lines = ArrayDeque<String>()

        /** 1 = download, 2 = build. Read by the failure path to mark the right step `fail`. */
        var step: Int = 1
            private set
        var phase: String = ""
            private set

        private var detail = ""
        private var done = 0
        private var total = 0
        private var fraction = 0.0
        private var bytes = 0L
        private var lastPublish = 0L
        private var dirty = false
        private var finished = false

        fun start() {
            // The clock belongs to the RUN, not to this action. Steps 3 and 4 are served by
            // `huawei.gnss` in the same task, and it reads this to keep counting from where the
            // build left off instead of restarting the elapsed time at zero halfway through.
            set("PgnssStartedAt", startedAt.toString())
            set("PgnssFailed", "")
            set("PgnssResult", "")
            set("PgnssLog", "")
            set("PgnssDetail", "")
            set("PgnssCount", "")
            set("PgnssEta", "")
            set("PgnssPct", "0")
            phase = "Starting"
            publishNow()
        }

        /**
         * A report from the build. Throttled to about two seconds, because every variable write
         * bumps a revision AND queues a Room upsert, and a scene `vars` change reloads the whole
         * WebView — a 25 MB download reporting every chunk would spend more time redrawing than
         * fetching.
         */
        @Synchronized
        fun publish(p: PgnssProgress) {
            val wanted = if (p.step == PgnssStep.DOWNLOAD) 1 else 2
            val advanced = wanted > step
            if (advanced) step = wanted
            // A blank phase is a report that had nothing new to say about it, not an instruction
            // to clear the line the panel is showing.
            if (p.phase.isNotEmpty()) phase = p.phase
            detail = p.detail
            done = p.done
            total = p.total
            // Clamped monotonic here as well as in the build's own reporter. Workers finish out of
            // order and a bar that goes backwards reads as a failure, so neither side is trusted to
            // be the only one that noticed.
            fraction = maxOf(fraction, p.fraction)
            bytes = p.bytes
            p.line?.let { line ->
                lines.addLast(line)
                while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            }
            dirty = true
            // A step boundary and a log line are worth the write immediately; a tick is not.
            if (advanced || p.line != null) publishNow() else throttled()
        }

        @Synchronized
        fun tick() {
            set("PgnssHeartbeat", System.currentTimeMillis().toString())
            set("PgnssElapsed", hms((System.currentTimeMillis() - startedAt) / 1000))
            eta()?.let { set("PgnssEta", it) }
            if (dirty) throttled()
        }

        @Synchronized
        fun finish(result: PgnssBuildResult) {
            step = 2
            finished = true
            phase = "Built"
            detail = ""
            fraction = 1.0
            for (note in result.notes) {
                lines.addLast(note)
                while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            }
            set("PgnssResult", result.summary)
            publishNow()
            // After publishNow, not before: it writes PgnssSteps and PgnssEta itself, and setting
            // them first left the finished run showing step 2 still running.
            set("PgnssEta", "")
        }

        @Synchronized
        fun fail(store: String?, atStep: Int, why: String): ActionResult {
            val steps = List(4) { i ->
                when {
                    i + 1 < atStep -> "done"
                    i + 1 == atStep -> "fail"
                    else -> "wait"
                }
            }
            set("PgnssSteps", steps.joinToString(","))
            set("PgnssFailed", "${STEP_NAMES[atStep - 1]}: $why")
            set("PgnssEta", "")
            set("PgnssPhase", "Failed")
            set("PgnssElapsed", hms((System.currentTimeMillis() - startedAt) / 1000))
            lines.addLast(why)
            while (lines.size > MAX_LOG_LINES) lines.removeFirst()
            set("PgnssLog", lines.joinToString("\n"))
            store?.let { ctx.variables.set(it, why) }
            ctx.logger("Huawei predicted ephemeris failed: $why")
            return ActionResult.Failure(why)
        }

        private fun throttled() {
            val now = System.currentTimeMillis()
            if (now - lastPublish < THROTTLE_MS) return
            publishNow()
        }

        private fun publishNow() {
            lastPublish = System.currentTimeMillis()
            dirty = false
            // A HEARTBEAT, so the panel can tell a live run from a corpse.
            //
            // The panel is a window onto variables and nothing clears them, so after a run was
            // force-stopped it opened showing "3m 4s · Downloading WUM0MGX…" from a build that had
            // not existed for hours (白い熊, 2026-08-30). Clearing the variables when the panel
            // opens is the wrong fix — the whole point is that it can be closed and come back to a
            // run in progress. A timestamp settles it without either side knowing about the other:
            // progress is shown only while this keeps moving, and a killed process stops moving by
            // definition.
            set("PgnssHeartbeat", System.currentTimeMillis().toString())
            set("PgnssSteps", List(4) { i ->
                when {
                    i + 1 < step || (finished && i + 1 == step) -> "done"
                    i + 1 == step -> "run"
                    else -> "wait"
                }
            }.joinToString(","))
            set("PgnssPhase", phase)
            set("PgnssDetail", detail)
            set("PgnssCount", if (total > 0) "$done/$total" else "")
            // Scaled to BUILD_CEILING: steps 3 and 4 are the band's, and a bar that reads 100 %
            // while it is still waiting for 白い熊 to press Update is simply lying.
            set("PgnssPct", (fraction * BUILD_CEILING * 100).toInt().coerceIn(0, 100).toString())
            set("PgnssElapsed", hms((System.currentTimeMillis() - startedAt) / 1000))
            set("PgnssLog", lines.joinToString("\n"))
            eta()?.let { set("PgnssEta", it) }
        }

        /**
         * Time left, or null while it would be a guess.
         *
         * The contract asks for it to stay blank until it can be honest, so nothing is offered
         * until a twentieth of the run is behind us — before that the elapsed time is dominated by
         * whichever host answered first and the extrapolation is nonsense.
         */
        private fun eta(): String? {
            if (fraction < 0.05) return null
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = (elapsed / fraction - elapsed).toLong()
            if (remaining <= 0 || remaining > 6 * 3600_000L) return null
            return hms(remaining / 1000)
        }

        private fun set(name: String, value: String) = ctx.variables.set("$prefix$name", value)

        private fun hms(sec: Long) = when {
            sec >= 3600 -> "${sec / 3600}h ${(sec % 3600) / 60}m"
            sec >= 60 -> "${sec / 60}m ${sec % 60}s"
            else -> "${sec}s"
        }
    }

    internal companion object {
        /** The store `huawei.gnss` reads by default. */
        const val DEFAULT_DIR = "gnss"

        /** Enough to see what happened, few enough to stay readable on a phone panel. */
        const val MAX_LOG_LINES = 26

        /**
         * How much of the progress bar the download and the build are allowed to fill.
         *
         * The rest belongs to `huawei.gnss` — telling the band, and the transfer itself. Measured
         * rather than chosen: the build is about ten minutes and the band's part is under one.
         */
        const val BUILD_CEILING = 0.9

        /** The contract's four steps, for a failure message that names the one that broke. */
        val STEP_NAMES = listOf("Download", "Build", "On the band", "Transferred")

        /**
         * The action's own ceiling, below `TaskRunner`'s budget so this one is what fires.
         *
         * A full run used to be about six minutes of downloading — 368 s for 21.3 MB measured
         * 2026-08-30, 299 s of it the BeiDou orbits over Wuhan's FTP at 18 KB/s. Taking those from
         * IGN's mirror instead cut that leg from minutes to seconds, so the download is no longer
         * the long pole; the solve is.
         *
         * Fifty-five minutes stays anyway, and deliberately. The budget exists for the fits, which
         * are the part that cannot be made fast, and the alternative to a generous one is an action
         * killed mid-fit with nothing to say for itself.
         */
        const val MAX_RUN_MS = 55 * 60_000L

        /** Just above [MAX_RUN_MS]: the lock must outlive the work and nothing else. */
        const val WAKE_LOCK_MS = MAX_RUN_MS + 60_000L

        const val WAKE_LOCK_TAG = "shiroikuma:pgnss"

        /** About two seconds, as the contract says. */
        const val THROTTLE_MS = 2_000L

        /** The elapsed clock's own tick. Same cadence, so the two never fight for the store. */
        const val TICK_MS = 2_000L

        /** Below this the build is refused unless `force` is set. Charging is never required. */
        const val MIN_BATTERY_PERCENT = 20

        val TRUE_WORDS = setOf("1", "true", "yes", "on")

        /** Percent, or -1 when the sticky battery broadcast says nothing. */
        fun batteryPercent(app: Context): Int {
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            return if (level >= 0 && scale > 0) level * 100 / scale else -1
        }
    }
}
