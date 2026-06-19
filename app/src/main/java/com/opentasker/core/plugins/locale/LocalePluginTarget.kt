package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.opentasker.core.external.AutomationTargetReceiver
import com.opentasker.core.logging.AppLogger

/**
 * Exposes OpenTasker as a Locale-compatible setting plugin so Tasker, MacroDroid,
 * and other Locale hosts can invoke approved OpenTasker tasks.
 *
 * Protocol:
 * - Host starts [LocaleSettingEditActivity] with ACTION_EDIT_SETTING
 * - User picks a task, activity returns a bundle with taskId + taskName
 * - Host fires [LocaleSettingFireReceiver] with ACTION_FIRE_SETTING and the saved bundle
 * - Receiver dispatches the task through the existing automation pipeline
 */
object LocalePluginTarget {
    const val BUNDLE_KEY_TASK_ID = "com.opentasker.locale.TASK_ID"
    const val BUNDLE_KEY_TASK_NAME = "com.opentasker.locale.TASK_NAME"
    private const val TAG = "LocalePluginTarget"

    fun buildResultBundle(taskId: Long, taskName: String): Bundle =
        Bundle().apply {
            putLong(BUNDLE_KEY_TASK_ID, taskId)
            putString(BUNDLE_KEY_TASK_NAME, taskName)
        }

    fun buildBlurb(taskName: String): String =
        "Run task: $taskName"

    fun parseTaskId(bundle: Bundle?): Long? {
        if (bundle == null) return null
        val id = bundle.getLong(BUNDLE_KEY_TASK_ID, -1L)
        return if (id > 0) id else null
    }

    fun parseTaskName(bundle: Bundle?): String? =
        bundle?.getString(BUNDLE_KEY_TASK_NAME)?.ifBlank { null }
}

class LocaleSettingFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalePluginContract.ACTION_FIRE_SETTING) return

        val bundle = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
        val taskId = LocalePluginTarget.parseTaskId(bundle)
        val taskName = LocalePluginTarget.parseTaskName(bundle) ?: "unknown"

        if (taskId == null) {
            AppLogger.warn("LocaleSettingFireReceiver", "Missing or invalid task ID in Locale bundle")
            return
        }

        AppLogger.info("LocaleSettingFireReceiver", "Locale fire: taskId=$taskId name=$taskName")

        val runIntent = Intent(context, AutomationTargetReceiver::class.java).apply {
            action = "com.opentasker.action.RUN_TASK"
            putExtra("com.opentasker.extra.TASK_ID", taskId)
            putExtra("com.opentasker.extra.SOURCE", "locale_plugin")
        }
        context.sendOrderedBroadcast(runIntent, "com.opentasker.permission.AUTOMATION")
    }
}
