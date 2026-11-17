package com.opentasker.core.actions

import com.opentasker.app.BuildConfig
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Write ONE project as an importable bundle — its tasks, profiles and scenes with their notes and
 * groups, and NO variables — into a directory (白い熊, 2026-11-17).
 *
 * This is the "clean setup" of a project: what a second phone imports to get the same automation
 * without the first phone's state. 携帯 is the case it was built for — a full export from the phone at
 * hand would carry its journal cursors, its conversation ids and its pseudo-SIMs to a relay, where
 * every one of them is wrong. Settings live in variables, so leaving the variables out is what makes
 * the copy clean; the new phone fills them by running the project's own settings task.
 *
 * The file is `<basename>_<yyyy-MM-dd_HH-mm-ss>.json` (never overwritten — a second export is a second
 * file), its path lands in `%<store>`.
 */
class ProjectExportAction : Action {
    override val id = "project.export"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val projectName = args["project"].orEmpty().trim()
        if (projectName.isEmpty()) return ActionResult.Failure("project name is required")
        val dirArg = args["dir"].orEmpty().trim()
        if (dirArg.isEmpty()) return ActionResult.Failure("directory is required")
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "export_path"
        val basename = args["basename"]?.trim()?.takeIf { it.isNotEmpty() } ?: projectName

        val db = OpenTaskerApp_NoHilt.db
        return withContext(Dispatchers.IO) {
            val project = db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }
                ?: return@withContext ActionResult.Failure("no such project: $projectName")
            val taskIds = db.taskDao().getAll().filter { it.projectId == project.id }.map { it.id }.toSet()
            val profileIds = db.profileDao().getAll().filter { it.projectId == project.id }.map { it.id }.toSet()
            val sceneIds = db.sceneDao().getAll().filter { it.projectId == project.id }.map { it.id }.toSet()
            if (taskIds.isEmpty() && profileIds.isEmpty() && sceneIds.isEmpty()) {
                return@withContext ActionResult.Failure("project $projectName is empty")
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val bundle = OpenTaskerBundleRepository(db).exportSelection(
                appVersion = BuildConfig.VERSION_NAME,
                profileIds = profileIds,
                taskIds = taskIds,
                sceneIds = sceneIds,
                includeVariables = false,
                name = "${project.name} $stamp",
                description = "Clean setup of project ${project.name}: tasks, profiles and scenes, no variables.",
            )
            val dir = File(dirArg)
            if (!dir.isDirectory && !dir.mkdirs()) {
                return@withContext ActionResult.Failure("cannot create directory $dirArg")
            }
            val file = File(dir, "${basename}_$stamp.json")
            runCatching { file.writeText(OpenTaskerBundleCodec.encode(bundle), Charsets.UTF_8) }
                .onFailure { return@withContext ActionResult.Failure("cannot write ${file.path}: ${it.message}") }
            ctx.variables.set(store, file.absolutePath)
            ctx.variables.set("${store}_tasks", taskIds.size.toString())
            ctx.variables.set("${store}_profiles", profileIds.size.toString())
            ctx.variables.set("${store}_scenes", sceneIds.size.toString())
            ctx.logger("Exported project ${project.name}: ${taskIds.size} tasks, ${profileIds.size} profiles, ${sceneIds.size} scenes → ${file.path}")
            ActionResult.Success
        }
    }
}
