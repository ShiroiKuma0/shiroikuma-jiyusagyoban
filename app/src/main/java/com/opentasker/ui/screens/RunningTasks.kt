package com.opentasker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.opentasker.core.engine.ActiveExecution
import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What is running right now, for the Running pill on task and profile cards (upstream 3575ad42,
 * abcd0e47): each run's own task, and any task one of them is running as a sub-task.
 *
 * [names] exists because the fork links a profile to its tasks by NAME first — after a bundle
 * re-import the id on the profile can be stale while the name still resolves, and a pill that
 * followed the id alone would stay dark on exactly those profiles. A sub-task is known by id only.
 */
internal data class RunningTasks(val ids: Set<Long> = emptySet(), val names: Set<String> = emptySet()) {
    fun runs(taskId: Long): Boolean = taskId in ids

    /** Whether [profile]'s enter, exit or fallback task is running. */
    fun runs(profile: Profile): Boolean =
        listOfNotNull(profile.enterTaskId.takeIf { it > 0L }, profile.exitTaskId, profile.fallbackTaskId).any { it in ids } ||
            listOf(profile.enterTaskName, profile.exitTaskName).any { it.isNotEmpty() && it in names }
}

/** Pure, so the pill's rule can be tested without a registry. */
internal fun runningTasks(executions: List<ActiveExecution>, nestedTaskIds: List<Long>): RunningTasks =
    RunningTasks(
        ids = executions.mapTo(mutableSetOf()) { it.taskId } + nestedTaskIds,
        names = executions.mapTo(mutableSetOf()) { it.taskName },
    )

/**
 * One shared, de-duplicated flow. The registry republishes its list on every STEP of every run, so a
 * card collecting it directly would recompose once per step; this only changes when a run starts or
 * ends.
 */
private val runningTasksFlow: Flow<RunningTasks> =
    combine(ActiveExecutionRegistry.active, ActiveExecutionRegistry.nestedTaskIds, ::runningTasks).distinctUntilChanged()

@Composable
internal fun rememberRunningTasks(): RunningTasks {
    val running by runningTasksFlow.collectAsState(initial = RunningTasks())
    return running
}
