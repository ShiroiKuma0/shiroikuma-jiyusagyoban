package com.opentasker.core.transfer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import com.opentasker.app.BuildConfig
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.widget.SetWidgetNameReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * External **workspace export / bundle import** bridge, driven over adb during the build-test loop
 * (`adb shell am broadcast …`), so the workspace can be exported to and re-imported from
 * `/sdcard/tmp` without touching the UI.
 *
 * - [ACTION_EXPORT_WORKSPACE]: write the full workspace as an OpenTaskerBundle JSON. Optional
 *   [EXTRA_PATH] names the output file; default is `/sdcard/tmp/白い熊 自由作業盤.<stamp>.json`
 *   (the same name the manual Export All produces). The written path comes back as the ordered
 *   broadcast's result data.
 * - [ACTION_IMPORT_BUNDLE]: import the bundle JSON at [EXTRA_PATH] (a bare filename resolves
 *   against `/sdcard/tmp`) with the default strategies — merge projects, overwrite same-name
 *   items in place. The result data is a human-readable summary; warnings ride along in
 *   [EXTRA_WARNINGS].
 *
 * No permission (adb shell can't hold one): the broadcast must be explicit AND carry the shared
 * protocol extra, same gate as the other bridges. [Activity.RESULT_OK] = done;
 * [Activity.RESULT_FIRST_USER] = failed, message in [EXTRA_ERROR] / result data.
 */
class WorkspaceTransferReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(SetWidgetNameReceiver.EXTRA_PROTOCOL, -1) != SetWidgetNameReceiver.PROTOCOL_VERSION) return
        if (!isOrderedBroadcast) return

        val app = context.applicationContext
        val action = intent.action
        val path = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()

        // Room DAOs are suspend-only — go async and answer from IO (well under the broadcast timeout).
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = OpenTaskerBundleRepository(OpenTaskerApp_NoHilt.db)
                when (action) {
                    ACTION_EXPORT_WORKSPACE -> {
                        val file = if (path.isNotEmpty()) resolveInTmp(path) else defaultExportFile()
                        val bundle = repository.exportBundle(appVersion = BuildConfig.VERSION_NAME)
                        file.parentFile?.mkdirs()
                        file.writeText(OpenTaskerBundleCodec.encode(bundle), Charsets.UTF_8)
                        pending.setResultCode(Activity.RESULT_OK)
                        pending.setResultData(file.absolutePath)
                        pending.setResultExtras(Bundle().apply {
                            putString(EXTRA_PATH, file.absolutePath)
                            putInt(EXTRA_COUNT_TASKS, bundle.tasks.size)
                            putInt(EXTRA_COUNT_PROFILES, bundle.profiles.size)
                            putInt(EXTRA_COUNT_SCENES, bundle.scenes.size)
                        })
                    }
                    ACTION_IMPORT_BUNDLE -> {
                        require(path.isNotEmpty()) { "missing $EXTRA_PATH" }
                        val file = resolveInTmp(path)
                        require(file.isFile) { "no such file: ${file.absolutePath}" }
                        val bundle = OpenTaskerBundleCodec.decode(file.readText(Charsets.UTF_8))
                        val report = repository.importBundle(bundle)
                        pending.setResultCode(Activity.RESULT_OK)
                        pending.setResultData(
                            "imported ${report.insertedTasks} tasks, ${report.insertedProfiles} profiles, " +
                                "${report.insertedScenes} scenes, ${report.insertedVariables} variables, " +
                                "${report.insertedTemplates} templates from ${file.name}"
                        )
                        val warnings = report.warnings + report.lossyWarnings
                        if (warnings.isNotEmpty()) {
                            pending.setResultExtras(Bundle().apply {
                                putStringArray(EXTRA_WARNINGS, warnings.toTypedArray())
                            })
                        }
                    }
                    else -> {
                        pending.setResultCode(Activity.RESULT_FIRST_USER)
                        pending.setResultData("unknown action: $action")
                    }
                }
            } catch (e: Exception) {
                pending.setResultCode(Activity.RESULT_FIRST_USER)
                val message = e.message ?: e.javaClass.simpleName
                pending.setResultData(message)
                pending.setResultExtras(Bundle().apply { putString(EXTRA_ERROR, message) })
            } finally {
                pending.finish()
            }
        }
    }

    /** An absolute path is used as-is; a bare filename / relative path lands in `/sdcard/tmp`. */
    private fun resolveInTmp(path: String): File =
        if (path.startsWith("/")) File(path)
        else File(File(Environment.getExternalStorageDirectory(), "tmp"), path)

    private fun defaultExportFile(): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return resolveInTmp("白い熊 自由作業盤.$stamp.json")
    }

    companion object {
        const val ACTION_EXPORT_WORKSPACE = "shiroikuma.jiyusagyoban.action.EXPORT_WORKSPACE"
        const val ACTION_IMPORT_BUNDLE = "shiroikuma.jiyusagyoban.action.IMPORT_BUNDLE"
        const val EXTRA_PATH = "shiroikuma.jiyusagyoban.extra.PATH"
        const val EXTRA_ERROR = "shiroikuma.jiyusagyoban.extra.ERROR"
        const val EXTRA_WARNINGS = "shiroikuma.jiyusagyoban.extra.WARNINGS"
        const val EXTRA_COUNT_TASKS = "shiroikuma.jiyusagyoban.extra.COUNT_TASKS"
        const val EXTRA_COUNT_PROFILES = "shiroikuma.jiyusagyoban.extra.COUNT_PROFILES"
        const val EXTRA_COUNT_SCENES = "shiroikuma.jiyusagyoban.extra.COUNT_SCENES"
    }
}
