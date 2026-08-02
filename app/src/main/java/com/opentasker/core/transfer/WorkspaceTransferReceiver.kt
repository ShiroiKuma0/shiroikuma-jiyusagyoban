package com.opentasker.core.transfer

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import com.opentasker.app.BuildConfig
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.storage.AutoStartSettings
import com.opentasker.core.storage.BootStartSettings
import com.opentasker.core.storage.ShutdownSettings
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
 * - [ACTION_DELETE_ITEMS]: delete named workspace items — a bundle import can only add/overwrite,
 *   so headless cleanup (e.g. retiring a legacy feature's tasks/scenes/variables) needs this.
 *   [EXTRA_PATH] names a JSON manifest:
 *   `{"projectName": "...", "tasks": [...], "profiles": [...], "scenes": [...], "variables": [...]}`
 *   Items are resolved by (project, name); a shown scene is hidden first; variables are removed from
 *   BOTH the project bucket and the super bucket (pre-project-scoping residue); item notes are
 *   cleaned up. Unknown names are reported as warnings, never failures.
 * - [ACTION_RUN_TASK]: run a task by name — [EXTRA_TASK] (required) + [EXTRA_PROJECT] (optional
 *   project name to disambiguate). Runs through [executeAndLogTask] like a manual run and answers
 *   with success + duration. Exists so the dev loop can trigger a project's 起動/71 reload task
 *   right after a settings-bundle import (白い熊 2026-07-20: never leave that reload to the user).
 *
 * No permission (adb shell can't hold one): the broadcast must be explicit AND carry the shared
 * protocol extra, same gate as the other bridges. [Activity.RESULT_OK] = done;
 * [Activity.RESULT_FIRST_USER] = failed, message in [EXTRA_ERROR] / result data.
 */
/** Manifest for [WorkspaceTransferReceiver.ACTION_DELETE_ITEMS] — names resolved within [projectName]. */
@kotlinx.serialization.Serializable
data class DeleteItemsManifest(
    val projectName: String,
    val tasks: List<String> = emptyList(),
    val profiles: List<String> = emptyList(),
    val scenes: List<String> = emptyList(),
    val variables: List<String> = emptyList(),
)

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
                    ACTION_DELETE_ITEMS -> {
                        require(path.isNotEmpty()) { "missing $EXTRA_PATH" }
                        val file = resolveInTmp(path)
                        require(file.isFile) { "no such file: ${file.absolutePath}" }
                        val manifest = json.decodeFromString<DeleteItemsManifest>(file.readText(Charsets.UTF_8))
                        val result = deleteItems(manifest)
                        pending.setResultCode(Activity.RESULT_OK)
                        pending.setResultData(result.summary)
                        if (result.warnings.isNotEmpty()) {
                            pending.setResultExtras(Bundle().apply {
                                putStringArray(EXTRA_WARNINGS, result.warnings.toTypedArray())
                            })
                        }
                    }
                    ACTION_RUN_TASK -> {
                        // Only the RUN branch is gated by the shutdown flag: export / import / delete are
                        // pure workspace data and start nothing, so the dev loop keeps working on a
                        // stopped app. Running a task would restart automation, which "stopped" forbids.
                        require(!EngineShutdown.refuse(app, "adb bridge (run task)")) {
                            "白い熊 自由作業盤 is stopped — open the app to start it again"
                        }
                        val taskName = intent.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
                        require(taskName.isNotEmpty()) { "missing $EXTRA_TASK" }
                        val projectName = intent.getStringExtra(EXTRA_PROJECT)?.trim().orEmpty()
                        val db = OpenTaskerApp_NoHilt.db
                        val projectId = if (projectName.isEmpty()) null else
                            db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }?.id
                                ?: throw IllegalArgumentException("no such project: $projectName")
                        val matches = db.taskDao().getAll().filter {
                            it.name.equals(taskName, ignoreCase = true) &&
                                (projectId == null || it.projectId == projectId)
                        }
                        require(matches.isNotEmpty()) { "no such task: $taskName" }
                        require(matches.size == 1) {
                            "ambiguous task name: $taskName (${matches.size} matches — pass $EXTRA_PROJECT)"
                        }
                        val result = executeAndLogTask(
                            appContext = app,
                            db = db,
                            task = matches.single().toDomain(),
                            source = "adb bridge",
                            logTag = "WorkspaceTransfer",
                        )
                        pending.setResultCode(if (result.report.success) Activity.RESULT_OK else Activity.RESULT_FIRST_USER)
                        // Carry the first failure back to the caller. Without it an adb-driven run
                        // reports only "success=false" and the reason is stranded in the on-device run
                        // log, which is exactly the wrong place when the caller is a script.
                        val reason = result.skippedReason
                            ?: result.report.results
                                .firstNotNullOfOrNull { (it as? ActionResult.Failure)?.message }
                        pending.setResultData(
                            "ran '$taskName': success=${result.report.success} in ${result.report.durationMs}ms" +
                                if (reason.isNullOrBlank()) "" else " — $reason"
                        )
                    }
                    ACTION_SET_STARTUP_TASKS -> {
                        // Configuration, not automation, so it is NOT gated by the shutdown flag —
                        // setting what should run on exit has to work on a stopped app too.
                        // Tasks are named, never id'd (the workspace's reference-by-name rule); an
                        // omitted extra leaves that list alone, an empty string clears it.
                        val db = OpenTaskerApp_NoHilt.db
                        val projectName = intent.getStringExtra(EXTRA_PROJECT)?.trim().orEmpty()
                        val projectId = if (projectName.isEmpty()) null else
                            db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }?.id
                                ?: throw IllegalArgumentException("no such project: $projectName")
                        val allTasks = db.taskDao().getAll()
                        fun resolve(names: String): List<Long> = names.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { name ->
                                val matches = allTasks.filter {
                                    it.name.equals(name, ignoreCase = true) &&
                                        (projectId == null || it.projectId == projectId)
                                }
                                require(matches.isNotEmpty()) { "no such task: $name" }
                                require(matches.size == 1) {
                                    "ambiguous task name: $name (${matches.size} matches — pass $EXTRA_PROJECT)"
                                }
                                matches.single().id
                            }
                        val summary = mutableListOf<String>()
                        intent.getStringExtra(EXTRA_START_TASKS)?.let { raw ->
                            val ids = resolve(raw)
                            AutoStartSettings.set(app, ids)
                            summary += "run-on-start = ${ids.size} task(s)"
                        }
                        intent.getStringExtra(EXTRA_EXIT_TASKS)?.let { raw ->
                            val ids = resolve(raw)
                            ShutdownSettings.set(app, ids)
                            summary += "run-on-exit = ${ids.size} task(s)"
                        }
                        intent.getStringExtra(EXTRA_BOOT_START)?.let { raw ->
                            val on = raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
                            BootStartSettings.set(app, on)
                            summary += "start-on-boot = $on"
                        }
                        require(summary.isNotEmpty()) {
                            "nothing to set — pass $EXTRA_START_TASKS, $EXTRA_EXIT_TASKS or $EXTRA_BOOT_START"
                        }
                        pending.setResultCode(Activity.RESULT_OK)
                        pending.setResultData(summary.joinToString(", "))
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

    private data class DeleteResult(val summary: String, val warnings: List<String>)

    private suspend fun deleteItems(manifest: DeleteItemsManifest): DeleteResult {
        val db = OpenTaskerApp_NoHilt.db
        val warnings = mutableListOf<String>()
        // An empty name (or "Unfiled") targets the Unfiled bucket — items with no project at all, which
        // is where a scratch/test task lands. Without this they could only be deleted by hand in the UI.
        val unfiled = manifest.projectName.isBlank() || manifest.projectName.equals("Unfiled", ignoreCase = true)
        val project = if (unfiled) null else {
            db.projectDao().getAll().firstOrNull { it.name.equals(manifest.projectName, ignoreCase = true) }
                ?: throw IllegalArgumentException("no such project: ${manifest.projectName}")
        }
        val pid: Long? = project?.id
        var tasks = 0; var profiles = 0; var scenes = 0; var variables = 0

        // Profiles first, so nothing triggers a task while it's being removed; the engine reconciles
        // from the Room flow automatically.
        manifest.profiles.forEach { name ->
            val entity = db.profileDao().getAll().firstOrNull { it.projectId == pid && it.name.equals(name, ignoreCase = true) }
            if (entity == null) { warnings += "profile not found: $name"; return@forEach }
            db.profileDao().delete(entity)
            db.itemMetaDao().delete("profiles", entity.id.toString())
            profiles++
        }
        manifest.scenes.forEach { name ->
            val entity = db.sceneDao().getAll().firstOrNull { it.projectId == pid && it.name.equals(name, ignoreCase = true) }
            if (entity == null) { warnings += "scene not found: $name"; return@forEach }
            runCatching { com.opentasker.scenes.SceneOverlayManager.hide(entity.id) }
            db.sceneDao().delete(entity)
            db.itemMetaDao().delete("scenes", entity.id.toString())
            scenes++
        }
        manifest.tasks.forEach { name ->
            val entity = db.taskDao().getAll().firstOrNull { it.projectId == pid && it.name.equals(name, ignoreCase = true) }
            if (entity == null) { warnings += "task not found: $name"; return@forEach }
            db.taskDao().delete(entity)
            db.itemMetaDao().delete("tasks", entity.id.toString())
            tasks++
        }
        manifest.variables.forEach { name ->
            // Project bucket + super bucket (a pre-project-scoping copy may linger in super).
            var hit = false
            val vpid = pid ?: 0L    // Unfiled variables live in the super bucket
            if (db.variableDao().get(vpid, name) != null) { db.variableDao().delete(vpid, name); hit = true }
            if (db.variableDao().get(0L, name) != null) { db.variableDao().delete(0L, name); hit = true }
            if (hit) variables++ else warnings += "variable not found: $name"
        }
        // Deletions above bypass PersistentGlobalScope — re-warm so the cache drops them too.
        if (manifest.variables.isNotEmpty()) {
            com.opentasker.core.engine.variables.PersistentGlobalScope.refreshFromDb()
        }
        return DeleteResult(
            "deleted $tasks tasks, $profiles profiles, $scenes scenes, $variables variables from ${project?.name ?: "Unfiled"}",
            warnings,
        )
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
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        const val ACTION_EXPORT_WORKSPACE = "shiroikuma.jiyusagyoban.action.EXPORT_WORKSPACE"
        const val ACTION_IMPORT_BUNDLE = "shiroikuma.jiyusagyoban.action.IMPORT_BUNDLE"
        const val ACTION_DELETE_ITEMS = "shiroikuma.jiyusagyoban.action.DELETE_ITEMS"
        const val ACTION_RUN_TASK = "shiroikuma.jiyusagyoban.action.RUN_TASK"
        const val ACTION_SET_STARTUP_TASKS = "shiroikuma.jiyusagyoban.action.SET_STARTUP_TASKS"

        /** Comma-separated task NAMES for the run-on-start list; omit to leave it alone, "" to clear. */
        const val EXTRA_START_TASKS = "shiroikuma.jiyusagyoban.extra.START_TASKS"
        /** Comma-separated task NAMES for the run-on-exit list; omit to leave it alone, "" to clear. */
        const val EXTRA_EXIT_TASKS = "shiroikuma.jiyusagyoban.extra.EXIT_TASKS"
        /** "true"/"1" or "false"/"0" — whether a reboot starts the engine. */
        const val EXTRA_BOOT_START = "shiroikuma.jiyusagyoban.extra.BOOT_START"
        const val EXTRA_PATH = "shiroikuma.jiyusagyoban.extra.PATH"
        const val EXTRA_TASK = "shiroikuma.jiyusagyoban.extra.TASK"
        const val EXTRA_PROJECT = "shiroikuma.jiyusagyoban.extra.PROJECT"
        const val EXTRA_ERROR = "shiroikuma.jiyusagyoban.extra.ERROR"
        const val EXTRA_WARNINGS = "shiroikuma.jiyusagyoban.extra.WARNINGS"
        const val EXTRA_COUNT_TASKS = "shiroikuma.jiyusagyoban.extra.COUNT_TASKS"
        const val EXTRA_COUNT_PROFILES = "shiroikuma.jiyusagyoban.extra.COUNT_PROFILES"
        const val EXTRA_COUNT_SCENES = "shiroikuma.jiyusagyoban.extra.COUNT_SCENES"
    }
}
