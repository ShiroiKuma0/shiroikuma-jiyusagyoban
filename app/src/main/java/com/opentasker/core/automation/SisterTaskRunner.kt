package com.opentasker.core.automation

import android.content.Context
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.RunTrace
import com.opentasker.core.engine.executeAndLogTask

/**
 * Runs one named task for a sister app and words the answer — for the provider call
 * ([AutomationProvider.METHOD_RUN_TASK]), where the system itself names the caller. The broadcast
 * door that used to offer the same (`RUN_TASK_WITH_TARGET`) was closed in +024.
 */
internal object SisterTaskRunner {
    private const val TAG = "SisterTask"

    suspend fun run(
        app: Context,
        source: String,
        taskName: String,
        projectName: String,
        variables: Map<String, String>,
    ): String {
        val db = OpenTaskerApp_NoHilt.db
        val projectId = if (projectName.isEmpty()) null else
            db.projectDao().getAll().firstOrNull { it.name.equals(projectName, ignoreCase = true) }?.id
                ?: return "ERROR:no such project: $projectName"
        val matches = db.taskDao().getAll().filter {
            it.name.equals(taskName, ignoreCase = true) && (projectId == null || it.projectId == projectId)
        }
        when {
            matches.isEmpty() ->
                return "ERROR:no such task: $taskName" + if (projectName.isEmpty()) "" else " in $projectName"
            matches.size > 1 ->
                return "ERROR:ambiguous task name: $taskName (${matches.size} matches — pass the project)"
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
        return if (stoppedBy == null) "OK:ran '$taskName' in ${result.report.durationMs}ms" else "ERROR:'$taskName' $stoppedBy"
    }
}
