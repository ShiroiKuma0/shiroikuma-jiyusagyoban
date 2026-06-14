package com.opentasker.core.engine

import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Resolves a sub-task by id or name for the `task.run` action. */
typealias SubTaskResolver = suspend (ref: String) -> Task?

/**
 * Executes a Task's action list with flow control and variable expansion.
 *
 * When a [resolveTask] resolver is supplied, the `task.run` action can execute another task as a
 * reusable sub-task, sharing this task's variable store so inputs flow in and global outputs flow
 * out. Recursion is bounded by [MAX_SUBTASK_DEPTH] to prevent infinite call chains.
 */
class TaskRunner(
    private val ctx: ActionContext,
    private val templateExpressionEngine: TemplateExpressionEngine = TemplateExpressionEngine(),
    private val resolveTask: SubTaskResolver? = null,
    private val depth: Int = 0,
) {
    suspend fun run(task: Task): TaskRunReport {
        ctx.variables.pushScope()
        val started = System.currentTimeMillis()
        val results = mutableListOf<ActionResult>()
        val traces = mutableListOf<ActionExecutionTrace>()
        try {
            var pc = 0
            while (pc in task.actions.indices) {
                val spec = task.actions[pc]
                val (result, trace) = runOne(pc, spec)
                results += result
                traces += trace
                if (result is ActionResult.Failure && !spec.continueOnError) break
                pc++
            }
        } finally {
            ctx.variables.popScope()
        }
        return TaskRunReport(
            taskId = task.id,
            taskName = task.name,
            startedAt = started,
            durationMs = System.currentTimeMillis() - started,
            results = results,
            traces = traces,
            success = results.all { it is ActionResult.Success || it is ActionResult.Skip }
        )
    }

    private suspend fun runOne(index: Int, spec: ActionSpec): Pair<ActionResult, ActionExecutionTrace> {
        val started = System.currentTimeMillis()
        if (!shouldRun(spec)) {
            val result = ActionResult.Skip
            return result to traceFor(index, spec, started, result, ActionArgumentExpansionReport.Empty)
        }

        if (spec.type == SUB_TASK_ACTION_ID) {
            return runSubTask(index, spec, started)
        }

        val action = ActionRegistry.get(spec.type)
            ?: ActionResult.Failure("unknown action: ${spec.type}").let { result ->
                return result to traceFor(index, spec, started, result, ActionArgumentExpansionReport.Empty)
            }
        val expansionReport = expandArgs(spec.args)
        val timeoutMs = actionTimeoutMs(spec.type)
        val result = try {
            withTimeout(timeoutMs) {
                action.run(ctx, expansionReport.args)
            }
        } catch (e: TimeoutCancellationException) {
            ActionResult.Failure("timed out after ${timeoutMs / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure("threw: ${e.message}", e)
        }
        return result to traceFor(index, spec, started, result, expansionReport)
    }

    private suspend fun runSubTask(
        index: Int,
        spec: ActionSpec,
        started: Long,
    ): Pair<ActionResult, ActionExecutionTrace> {
        val expansionReport = expandArgs(spec.args)
        val args = expansionReport.args

        fun fail(message: String): Pair<ActionResult, ActionExecutionTrace> {
            val result = ActionResult.Failure(message)
            return result to traceFor(index, spec, started, result, expansionReport)
        }

        val resolver = resolveTask ?: return fail("sub-tasks are not available in this context")
        if (depth >= MAX_SUBTASK_DEPTH) {
            return fail("sub-task depth limit ($MAX_SUBTASK_DEPTH) exceeded; possible recursion")
        }

        val ref = SUB_TASK_REF_KEYS.firstNotNullOfOrNull { args[it]?.trim()?.takeIf(String::isNotBlank) }
            ?: return fail("task.run requires a 'task' (id or name)")
        val target = resolver(ref) ?: return fail("sub-task not found: $ref")

        // Pass any extra args as input variables; the shared store lets global outputs flow back.
        args.forEach { (key, value) ->
            if (key !in SUB_TASK_REF_KEYS) ctx.variables.set(key, value)
        }

        val child = TaskRunner(ctx, templateExpressionEngine, resolveTask, depth + 1)
        val report = child.run(target)
        val result = if (report.success) {
            ActionResult.Success
        } else {
            ActionResult.Failure("sub-task '${target.name}' failed")
        }
        return result to traceFor(index, spec, started, result, expansionReport)
    }

    private fun shouldRun(spec: ActionSpec): Boolean {
        val condition = spec.condition?.trim()?.takeIf { it.isNotBlank() } ?: return true
        val legacyExpanded = ctx.variables.expand(condition)
        if (!legacyExpanded.contains("{{")) return ctx.variables.evaluateCondition(legacyExpanded)

        val expanded = templateExpressionEngine.expand(legacyExpanded, ctx.variables.toTemplateScope(ctx.eventVariables))
        if (expanded.warnings.isNotEmpty()) return false
        return ctx.variables.evaluateCondition(expanded.value)
    }

    private fun traceFor(
        index: Int,
        spec: ActionSpec,
        started: Long,
        result: ActionResult,
        expansionReport: ActionArgumentExpansionReport,
    ): ActionExecutionTrace = ActionExecutionTrace(
        index = index,
        actionType = spec.type,
        label = spec.label ?: spec.type,
        durationMs = System.currentTimeMillis() - started,
        status = when (result) {
            is ActionResult.Success -> ActionTraceStatus.SUCCESS
            is ActionResult.Failure -> if (result.message.startsWith("timed out")) ActionTraceStatus.TIMEOUT else ActionTraceStatus.FAILURE
            is ActionResult.Skip -> ActionTraceStatus.SKIPPED
        },
        message = when (result) {
            is ActionResult.Failure -> result.message
            is ActionResult.Skip -> "Skipped"
            is ActionResult.Success -> "Completed"
        },
        expandedArgSummary = expansionReport.summary(),
        templateWarnings = expansionReport.templateWarnings(),
        argumentExpansions = expansionReport.expansions,
    )

    private fun expandArgs(args: Map<String, String>): ActionArgumentExpansionReport {
        if (args.isEmpty()) return ActionArgumentExpansionReport.Empty

        val templateScope = ctx.variables.toTemplateScope(ctx.eventVariables)
        val expansions = mutableListOf<ActionArgumentExpansionTrace>()
        val expandedArgs = args.mapValues { (name, rawValue) ->
            val legacyExpanded = ctx.variables.expand(rawValue)
            if (!legacyExpanded.contains("{{")) return@mapValues legacyExpanded

            val result = templateExpressionEngine.expand(legacyExpanded, templateScope)
            if (result.traces.isNotEmpty() || result.warnings.isNotEmpty()) {
                expansions += ActionArgumentExpansionTrace(
                    argName = name,
                    rawValue = rawValue,
                    expandedValue = result.value,
                    expressions = result.traces,
                    warnings = result.warnings,
                )
            }
            result.value
        }

        return ActionArgumentExpansionReport(expandedArgs, expansions)
    }
}

data class TaskRunReport(
    val taskId: Long,
    val taskName: String,
    val startedAt: Long,
    val durationMs: Long,
    val results: List<ActionResult>,
    val traces: List<ActionExecutionTrace>,
    val success: Boolean,
)

enum class ActionTraceStatus {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    SKIPPED,
}

private fun actionTimeoutMs(actionType: String): Long = when {
    actionType == "flow.wait" -> MAX_WAIT_TIMEOUT_MS
    actionType.startsWith("http.") || actionType == "download" || actionType == "ping" -> 120_000L
    else -> DEFAULT_ACTION_TIMEOUT_MS
}

private const val DEFAULT_ACTION_TIMEOUT_MS = 60_000L
private const val MAX_WAIT_TIMEOUT_MS = 1_800_000L // 30 minutes

const val SUB_TASK_ACTION_ID = "task.run"
const val MAX_SUBTASK_DEPTH = 8
private val SUB_TASK_REF_KEYS = listOf("task", "name", "id")

data class ActionExecutionTrace(
    val index: Int,
    val actionType: String,
    val label: String,
    val durationMs: Long,
    val status: ActionTraceStatus,
    val message: String,
    val expandedArgSummary: String? = null,
    val templateWarnings: List<String> = emptyList(),
    val argumentExpansions: List<ActionArgumentExpansionTrace> = emptyList(),
)

data class ActionArgumentExpansionTrace(
    val argName: String,
    val rawValue: String,
    val expandedValue: String,
    val expressions: List<TemplateExpansionTrace>,
    val warnings: List<String>,
)

fun ActionExecutionTrace.toSummaryLine(): String =
    "${index + 1}. ${status.name.lowercase()}: $label [$actionType] ${durationMs}ms - $message${traceDetailSuffix()}"

fun List<ActionExecutionTrace>.toRunLogMessage(maxLines: Int = 8): String {
    if (isEmpty()) return "No actions executed"
    val visible = take(maxLines).flatMap { it.toRunLogLines() }.joinToString("\n")
    val remaining = size - maxLines
    return if (remaining > 0) "$visible\n... $remaining more action(s)" else visible
}

private data class ActionArgumentExpansionReport(
    val args: Map<String, String>,
    val expansions: List<ActionArgumentExpansionTrace>,
) {
    fun templateWarnings(): List<String> =
        expansions.flatMap { expansion -> expansion.warnings.map { "${expansion.argName}: $it" } }.distinct()

    fun summary(): String? {
        if (expansions.isEmpty()) return null
        return expansions
            .take(MAX_SUMMARY_ARGS)
            .joinToString(", ") { expansion ->
                "${expansion.argName}=${summarizeArgValue(expansion.argName, expansion.expandedValue)}"
            }
            .let { summary ->
                val remaining = expansions.size - MAX_SUMMARY_ARGS
                if (remaining > 0) "$summary, +$remaining more" else summary
            }
    }

    companion object {
        val Empty = ActionArgumentExpansionReport(emptyMap(), emptyList())
    }
}

private fun ActionExecutionTrace.traceDetailSuffix(): String {
    val details = buildList {
        expandedArgSummary?.takeIf { it.isNotBlank() }?.let { add("args: $it") }
        if (templateWarnings.isNotEmpty()) add("template warnings: ${templateWarnings.size}")
    }
    return if (details.isEmpty()) "" else " (${details.joinToString("; ")})"
}

private fun ActionExecutionTrace.toRunLogLines(): List<String> = buildList {
    add(toSummaryLine())
    argumentExpansions
        .flatMap { it.toTemplateDiagnosticLines() }
        .take(MAX_TEMPLATE_TRACE_LINES_PER_ACTION)
        .forEach(::add)
}

private fun ActionArgumentExpansionTrace.toTemplateDiagnosticLines(): List<String> =
    expressions.map { expressionTrace ->
        val sensitive = isSensitiveArgName(argName)
        listOf(
            TEMPLATE_TRACE_PREFIX,
            argName.toLogField(),
            expressionTrace.source.name.lowercase().toLogField(),
            if (sensitive) REDACTED_VALUE else expressionTrace.expression.toLogField(),
            if (sensitive) REDACTED_VALUE else expressionTrace.value.toLogField(),
            expressionTrace.warning.orEmpty().toLogField(),
        ).joinToString("\t")
    }

private fun summarizeArgValue(argName: String, value: String): String {
    if (isSensitiveArgName(argName)) {
        return REDACTED_VALUE
    }
    val singleLine = value.replace(Regex("""\s+"""), " ").trim()
    return if (singleLine.length <= MAX_SUMMARY_VALUE_LENGTH) {
        singleLine
    } else {
        singleLine.take(MAX_SUMMARY_VALUE_LENGTH) + "..."
    }
}

private fun String.toLogField(): String =
    replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
        .let { value ->
            if (value.length <= MAX_TEMPLATE_TRACE_FIELD_LENGTH) value else value.take(MAX_TEMPLATE_TRACE_FIELD_LENGTH) + "..."
        }

private fun isSensitiveArgName(argName: String): Boolean =
    SENSITIVE_ARG_TOKENS.any { token -> argName.contains(token, ignoreCase = true) }

private val SENSITIVE_ARG_TOKENS = listOf(
    "authorization",
    "cookie",
    "key",
    "password",
    "secret",
    "token",
)
private const val TEMPLATE_TRACE_PREFIX = "Template:"
private const val REDACTED_VALUE = "<redacted>"
private const val MAX_SUMMARY_ARGS = 4
private const val MAX_SUMMARY_VALUE_LENGTH = 80
private const val MAX_TEMPLATE_TRACE_LINES_PER_ACTION = 8
private const val MAX_TEMPLATE_TRACE_FIELD_LENGTH = 120
