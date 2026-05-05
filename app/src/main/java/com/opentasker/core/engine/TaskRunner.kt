package com.opentasker.core.engine

import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException

/**
 * Executes a Task's action list with flow control and variable expansion.
 */
class TaskRunner(
    private val ctx: ActionContext,
    private val templateExpressionEngine: TemplateExpressionEngine = TemplateExpressionEngine(),
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

        val action = ActionRegistry.get(spec.type)
            ?: ActionResult.Failure("unknown action: ${spec.type}").let { result ->
                return result to traceFor(index, spec, started, result, ActionArgumentExpansionReport.Empty)
            }
        val expansionReport = expandArgs(spec.args)
        val result = try {
            action.run(ctx, expansionReport.args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure("threw: ${e.message}", e)
        }
        return result to traceFor(index, spec, started, result, expansionReport)
    }

    private fun shouldRun(spec: ActionSpec): Boolean {
        val condition = spec.condition?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return ctx.variables.evaluateCondition(condition)
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
            is ActionResult.Failure -> ActionTraceStatus.FAILURE
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
    SKIPPED,
}

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
    val visible = take(maxLines).joinToString("\n") { it.toSummaryLine() }
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

private fun summarizeArgValue(argName: String, value: String): String {
    if (SENSITIVE_ARG_TOKENS.any { token -> argName.contains(token, ignoreCase = true) }) {
        return "<redacted>"
    }
    val singleLine = value.replace(Regex("""\s+"""), " ").trim()
    return if (singleLine.length <= MAX_SUMMARY_VALUE_LENGTH) {
        singleLine
    } else {
        singleLine.take(MAX_SUMMARY_VALUE_LENGTH) + "..."
    }
}

private val SENSITIVE_ARG_TOKENS = listOf(
    "authorization",
    "cookie",
    "key",
    "password",
    "secret",
    "token",
)
private const val MAX_SUMMARY_ARGS = 4
private const val MAX_SUMMARY_VALUE_LENGTH = 80
