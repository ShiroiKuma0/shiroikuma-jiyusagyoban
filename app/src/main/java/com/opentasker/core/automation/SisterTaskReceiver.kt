package com.opentasker.core.automation

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.engine.RunTrace
import com.opentasker.core.engine.executeAndLogTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A sister app runs one of 白い熊's tasks **about one app** — [ACTION_RUN_TASK_WITH_TARGET].
 *
 * Built for 白い熊 応用管理's per-app “起動マネージャー” button (2026-11-07). EMUI's App-launch screen
 * is signature-gated and the shell holds no START_ANY_ACTIVITY, so 応用管理 cannot open it; our
 * accessibility service can, and the task 起動マネージャーを開く in アプリ起動 types `%label` into the
 * screen's 検索 box. Contract: `~/git/shiroikuma-oyokanri/…/appstartup/AppLaunchContract.java`.
 *
 * ## Who may call
 *
 * The [AutomationCallers] allowlist — name, uid, pinned certificate. A broadcast carries no sender
 * uid below API 34, so the caller adds [EXTRA_IDENTITY]: an immutable `PendingIntent` it created
 * itself. Its creator package and uid are filled in by the system, never by the caller, so they
 * stand in for the binder identity the provider door gets for free. [EXTRA_SOURCE] must name the
 * same package. No token.
 *
 * ## What the task sees
 *
 * Two task-local variables: `%pkg` = [EXTRA_TARGET_PACKAGE] and `%label` = [EXTRA_TARGET_LABEL].
 *
 * ## The reply
 *
 * A **fresh** broadcast, [ACTION_RESULT] to [EXTRA_REPLY_PACKAGE] — EMUI severs the ordered-result
 * channel between third-party apps — echoing [EXTRA_REQUEST_ID], with [EXTRA_RESULT] =
 * `OK:<detail>` or `ERROR:<reason>`. The caller's reply window is 8 s and a task that drives a
 * settings screen may take longer, so a run still going after [REPLY_BY_MS] answers `OK:running`
 * and its eventual outcome stays in the run log. The reply goes only to the verified caller's own
 * package, so this door cannot be used to bounce broadcasts at a third app.
 */
class SisterTaskReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_TASK_WITH_TARGET) return
        val app = context.applicationContext
        val source = intent.getStringExtra(EXTRA_SOURCE)?.trim().orEmpty()
        val replyTo = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val replied = AtomicBoolean(false)

        fun reply(result: String) {
            if (isOrderedBroadcast) {
                resultCode = if (result.startsWith("OK:")) Activity.RESULT_OK else Activity.RESULT_FIRST_USER
                resultData = result
            }
            RunTrace.line("sister run from '$source' request '$requestId' → $result")
            // Only to the caller itself: never a broadcast reflector for another package.
            if (replyTo.isEmpty() || replyTo != source || !replied.compareAndSet(false, true)) return
            app.sendBroadcast(
                Intent(ACTION_RESULT)
                    .setPackage(replyTo)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EXTRA_REQUEST_ID, requestId)
                    .putExtra(EXTRA_RESULT, result),
            )
        }

        RunTrace.attach(app)
        val identity = identityOf(intent)
        if (identity == null) {
            reply("ERROR:caller identity missing — send $EXTRA_IDENTITY (an immutable PendingIntent you created)")
            return
        }
        if (identity.first != source) {
            reply("ERROR:caller identity is ${identity.first}, not $source")
            return
        }
        val verdict = AutomationCallers.verify(app, identity.first, identity.second)
        if (verdict is AutomationCallers.Verdict.Refused) {
            reply(verdict.why)
            return
        }
        if (EngineShutdown.refuse(app, "sister run ($source)")) {
            reply("ERROR:白い熊 自由作業盤 is stopped — open the app to start it again")
            return
        }
        // One press, one run. 白い熊 応用管理 delivered the same press twice 6 s apart (2026-11-08
        // 14:23:45 and :51); the second met the first still walking Settings and was refused by
        // the task's Abort-new rule, and that refusal was the answer 応用管理 showed.
        if (requestId.isNotEmpty() && !firstSighting(requestId)) {
            RunTrace.line("sister run from '$source' request $requestId repeated — ignored")
            return
        }
        val taskName = intent.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
        if (taskName.isEmpty()) {
            reply("ERROR:missing $EXTRA_TASK")
            return
        }
        val projectName = intent.getStringExtra(EXTRA_PROJECT)?.trim().orEmpty()
        val variables = mapOf(
            VAR_PKG to intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty(),
            VAR_LABEL to intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty(),
        )

        val pending = goAsync()
        val finished = AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            delay(REPLY_BY_MS)
            reply("OK:running '$taskName'")
            finish()
        }
        scope.launch {
            try {
                val db = OpenTaskerApp_NoHilt.db
                val projectId = if (projectName.isEmpty()) null else
                    db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }?.id
                        ?: return@launch reply("ERROR:no such project: $projectName")
                val matches = db.taskDao().getAll().filter {
                    it.name.equals(taskName, ignoreCase = true) && (projectId == null || it.projectId == projectId)
                }
                when {
                    matches.isEmpty() ->
                        return@launch reply("ERROR:no such task: $taskName" + if (projectName.isEmpty()) "" else " in $projectName")
                    matches.size > 1 ->
                        return@launch reply("ERROR:ambiguous task name: $taskName (${matches.size} matches — pass $EXTRA_PROJECT)")
                }
                val result = executeAndLogTask(
                    appContext = app,
                    db = db,
                    task = matches.single().toDomain(),
                    source = "sister: $source",
                    initialVariables = variables,
                    logTag = TAG,
                )
                // A step marked “continue on error” that fails is part of the plan (the 検索 icon a
                // screen may not have), not the end of the run: only a skipped run or a step that
                // STOPPED it is an error. `report.success` counts both, so it is not used here.
                val stoppedBy = result.skippedReason?.let { "not run — $it" } ?: result.report.structuredError?.let {
                    "stopped at step ${it.actionIndex} (${it.actionType}): ${it.message}"
                }
                val passedOver = result.report.traces
                    .filter { it.status == ActionTraceStatus.FAILURE || it.status == ActionTraceStatus.TIMEOUT }
                    .joinToString("; ") { "step ${it.index + 1} (${it.actionType}): ${it.message}" }
                if (passedOver.isNotEmpty()) RunTrace.line("sister run '$taskName' passed over: $passedOver")
                reply(
                    if (stoppedBy == null) "OK:ran '$taskName' in ${result.report.durationMs}ms"
                    else "ERROR:'$taskName' $stoppedBy",
                )
            } catch (t: Throwable) {
                reply("ERROR:${t.message ?: t.javaClass.simpleName}")
            } finally {
                finish()
            }
        }
    }

    /** The system-recorded creator of the caller's [EXTRA_IDENTITY], or null when there is none. */
    private fun identityOf(intent: Intent): Pair<String, Int>? {
        val pi = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_IDENTITY, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<PendingIntent>(EXTRA_IDENTITY)
            }
        }.getOrNull() ?: return null
        val pkg = pi.creatorPackage ?: return null
        return pkg to pi.creatorUid
    }

    companion object {
        private const val TAG = "SisterTask"

        /** Request ids seen in the last [REPEAT_WINDOW_MS], with when. */
        private val seen = LinkedHashMap<String, Long>()
        private const val REPEAT_WINDOW_MS = 60_000L

        @Synchronized
        private fun firstSighting(requestId: String): Boolean {
            val now = System.currentTimeMillis()
            seen.entries.removeAll { now - it.value > REPEAT_WINDOW_MS }
            return seen.put(requestId, now) == null
        }

        const val ACTION_RUN_TASK_WITH_TARGET = "shiroikuma.jiyusagyoban.action.RUN_TASK_WITH_TARGET"
        const val EXTRA_TASK = "shiroikuma.jiyusagyoban.extra.TASK"
        const val EXTRA_PROJECT = "shiroikuma.jiyusagyoban.extra.PROJECT"
        const val EXTRA_TARGET_PACKAGE = "shiroikuma.jiyusagyoban.extra.TARGET_PACKAGE"
        const val EXTRA_TARGET_LABEL = "shiroikuma.jiyusagyoban.extra.TARGET_LABEL"
        const val EXTRA_SOURCE = "shiroikuma.jiyusagyoban.extra.SOURCE"
        const val EXTRA_REPLY_PACKAGE = "shiroikuma.jiyusagyoban.extra.REPLY_PACKAGE"
        const val EXTRA_REQUEST_ID = "shiroikuma.jiyusagyoban.extra.REQUEST_ID"
        /** An immutable `PendingIntent` the caller created — its creator is the caller's identity. */
        const val EXTRA_IDENTITY = "shiroikuma.jiyusagyoban.extra.IDENTITY"

        const val ACTION_RESULT = "shiroikuma.oyokanri.action.APP_LAUNCH_RESULT"
        const val EXTRA_RESULT = "shiroikuma.oyokanri.extra.RESULT"

        /** Task-local variable names the run is given. */
        const val VAR_PKG = "pkg"
        const val VAR_LABEL = "label"

        /** Answer before the caller's 8 s reply window closes. */
        private const val REPLY_BY_MS = 6_000L
    }
}
