package com.opentasker.widget

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.opentasker.app.OpenTaskerApp_NoHilt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * External **"which app does this task open?"** bridge. A sister launcher holding one of our
 * run-task shortcuts (see [TaskShortcutHelper]) sends an explicit, **ordered** broadcast naming the
 * task ([TaskRunActivity.EXTRA_TASK_ID] / [TaskRunActivity.EXTRA_TASK_NAME], name winning — it
 * survives re-imports); we reply, in the ordered-broadcast **result extras**, with the package the
 * task ultimately unfreezes/launches ([EXTRA_TARGET_PACKAGE]), so the launcher can point its
 * "app info" / "uninstall" menu entries at that app instead of at us.
 *
 * Newer shortcuts carry the target baked in as an intent extra; this query exists for shortcuts
 * created before that, and stays authoritative when a task is later re-pointed at a different app.
 *
 * No permission: the broadcast is explicit ([Intent.setPackage]), and the sister apps may be signed
 * with a different key. [Activity.RESULT_OK] = the task was looked up; the target extra is then
 * present only when the task actually opens a single resolvable app. Anything else (old build with
 * no receiver → initial RESULT_CANCELED) tells the caller to fall back.
 */
class GetTaskTargetPackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(SetWidgetNameReceiver.EXTRA_PROTOCOL, -1) != SetWidgetNameReceiver.PROTOCOL_VERSION) return
        if (!isOrderedBroadcast) return

        val taskId = intent.getLongExtra(TaskRunActivity.EXTRA_TASK_ID, -1L)
        val taskName = intent.getStringExtra(TaskRunActivity.EXTRA_TASK_NAME)?.trim().orEmpty()
        if (taskId < 0 && taskName.isEmpty()) return

        val app = context.applicationContext
        // Room DAOs are suspend-only — go async and answer from IO (well under the broadcast timeout).
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = OpenTaskerApp_NoHilt.db
                // Resolve by name first (survives re-imports), else by id — same rule as TaskRunActivity.
                val entity = if (taskName.isNotEmpty()) db.taskDao().getByName(taskName) else db.taskDao().getById(taskId)
                val target = entity?.toDomain()?.let(TaskShortcutHelper::targetPackage)
                pending.setResultExtras(Bundle().apply { target?.let { putString(EXTRA_TARGET_PACKAGE, it) } })
                pending.setResultCode(Activity.RESULT_OK)
            } catch (_: Exception) {
                // Leave the initial result code — the caller reads non-OK as "fall back".
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_GET_TASK_TARGET_PACKAGE = "shiroikuma.jiyusagyoban.action.GET_TASK_TARGET_PACKAGE"
        const val EXTRA_TARGET_PACKAGE = "shiroikuma.jiyusagyoban.extra.TARGET_PACKAGE"
    }
}
