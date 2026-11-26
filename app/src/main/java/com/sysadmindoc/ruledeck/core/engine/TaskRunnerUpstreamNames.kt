package com.sysadmindoc.ruledeck.core.engine

import com.sysadmindoc.ruledeck.core.actions.ActionArgumentSensitivity

// FORK: the names upstream's TaskRunner.kt declares beside its runner, which Preflight, the Tasker
// importer and exporter and the reference index read. The fork keeps its own runner
// (TaskRunner.kt), so they live here. Where a name stands for a step the fork's runner does not
// run (Stop Task, a Run Task return variable), it is only what those tools need to read a task
// that carries one — see docs in the sync notes of 2026-11-19.

/** The action id of Tasker's Stop with a task named. */
const val STOP_TASK_ACTION_ID = "task.stop"

/** The `task.run` argument naming the caller's variable for the value a Stop hands back, Tasker's Return Value Variable. */
const val SUB_TASK_RETURN_KEY = "returnVariable"

/** The `task.run` switch that clears that variable before the task runs, Tasker's Reset Return Variable. */
const val SUB_TASK_RESET_RETURN_KEY = "resetReturnVariable"

/** `task.run` arguments that set up the step itself, so they never reach the sub-task as inputs. */
val SUB_TASK_SETTING_KEYS = setOf(SUB_TASK_RETURN_KEY, SUB_TASK_RESET_RETURN_KEY)

/** Whether [args] turn on [SUB_TASK_RESET_RETURN_KEY]. */
fun resetsReturnVariable(args: Map<String, String>): Boolean =
    args[SUB_TASK_RESET_RETURN_KEY]?.trim().equals("true", ignoreCase = true)

/** What the run log and Preflight say for a step turned off. */
const val STEP_TURNED_OFF = "step is turned off"

/** Why a Run Task step's return variable can't hold the returned value. */
internal fun unusableReturnVariable(written: String, expanded: String, sensitive: Boolean): String = when {
    expanded.isBlank() -> "'${written.trim()}' came out empty, so the returned value has nowhere to go"
    sensitive -> "${ActionArgumentSensitivity.REDACTED} isn't a variable name, so the returned value has nowhere to go"
    else -> "'${expanded.trim()}' isn't a variable name, so the returned value has nowhere to go"
}

/**
 * Which arguments keep a `%name` as written instead of filling it in.
 *
 * FORK: upstream keeps every declared output name as written ("%count names count"). The fork fills
 * output names in like any other text — 白い熊's tasks write through a name held in a variable
 * (保存中核 sets `%catvar`) — so only Tasker's own steps below keep the name.
 */
internal class OutputNameArguments(private val actionType: String) {
    fun keepsAsWritten(argumentName: String, rawValue: String): Boolean =
        ((actionType == "text.join" && argumentName == "array") && SINGLE_VARIABLE.matches(rawValue.trim())) ||
            (actionType == "var.set_multiple" && argumentName == "names")

    /**
     * The `%config` of a Set Variable path like `%config.theme` or `%items[0]`, which names the
     * variable to write into rather than reading it (upstream f275c6c7). Only Preflight asks; the
     * fork's run fills a Set Variable name in like any other text.
     */
    fun pathBase(argumentName: String, rawValue: String): String? =
        if (actionType == "var.set" && argumentName == "name") PATH_BASE.find(rawValue)?.value else null

    private companion object {
        val SINGLE_VARIABLE = Regex("%[A-Za-z][A-Za-z0-9_]*")
        val PATH_BASE = Regex("^\\s*%[A-Za-z][A-Za-z0-9_-]*(?=[.\\[])")
    }
}

/** A step argument as the run log and Preflight show it: one line, cut short, hidden when sensitive. */
internal fun summarizeArgValue(
    actionType: String?,
    argName: String,
    value: String,
    forceRedact: Boolean = false,
): String {
    if (forceRedact || ActionArgumentSensitivity.isSensitive(actionType, argName)) {
        return ActionArgumentSensitivity.REDACTED
    }
    val singleLine = value.replace(Regex("""\s+"""), " ").trim()
    return if (singleLine.length <= 80) singleLine else singleLine.take(80) + "..."
}
