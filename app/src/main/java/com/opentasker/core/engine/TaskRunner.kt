package com.opentasker.core.engine

import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
    /**
     * Reports the step about to run so an in-flight execution can say what it is doing. Nested
     * sub-task runners inherit it, so a run stuck inside a sub-task still names the real step.
     */
    private val onStep: ((index: Int, label: String) -> Unit)? = null,
    private val collisionCoordinator: TaskCollisionCoordinator? = null,
    private val executionChain: Set<Long> = emptySet(),
) {
    /** A live `flow.foreach` iteration in progress. */
    private class LoopFrame(
        val foreachIndex: Int,
        val items: List<String>,
        val itemVar: String,
        val sensitive: Boolean,
        var index: Int,
    )

    private enum class TryPhase { BODY, CATCH }

    private class TryFrame(
        val tryIndex: Int,
        val catchIndex: Int?,
        val endIndex: Int,
        val config: FlowControl.TryConfig,
        val loopDepth: Int,
        var attempt: Int = 1,
        var phase: TryPhase = TryPhase.BODY,
    )

    suspend fun run(task: Task): TaskRunReport {
        ctx.variables.pushScope()
        val started = System.currentTimeMillis()
        val results = mutableListOf<ActionResult>()
        val traces = mutableListOf<ActionExecutionTrace>()

        val unknownActionIds = task.actions
            .map(ActionSpec::type)
            .filter { actionId ->
                !AutomationSensitivityRegistry.isKnown(actionId) && ActionRegistry.get(actionId) == null
            }
            .distinct()
            .sorted()
        if (unknownActionIds.isNotEmpty()) {
            ctx.variables.popScope()
            val failure = ActionResult.Failure(
                "task contains unknown unclassified actions: ${unknownActionIds.joinToString()}",
            )
            return TaskRunReport(
                taskId = task.id,
                taskName = task.name,
                startedAt = started,
                durationMs = System.currentTimeMillis() - started,
                results = listOf(failure),
                traces = listOf(
                    ActionExecutionTrace(
                        index = 0,
                        actionType = "preflight",
                        label = "action classification",
                        durationMs = 0,
                        status = ActionTraceStatus.FAILURE,
                        message = failure.message,
                    ),
                ),
                success = false,
            )
        }

        val unsupportedActionIds = task.actions
            .map(ActionSpec::type)
            .filter { actionId ->
                AutomationSensitivityRegistry.isKnown(actionId) &&
                    !ActionCapabilityRegistry.get(actionId).canAdd
            }
            .distinct()
            .sorted()
        if (unsupportedActionIds.isNotEmpty()) {
            ctx.variables.popScope()
            val failure = ActionResult.Failure(
                "task contains unsupported actions: ${unsupportedActionIds.joinToString()}",
            )
            return TaskRunReport(
                taskId = task.id,
                taskName = task.name,
                startedAt = started,
                durationMs = System.currentTimeMillis() - started,
                results = listOf(failure),
                traces = listOf(
                    ActionExecutionTrace(
                        index = 0,
                        actionType = "preflight",
                        label = "action capability",
                        durationMs = 0,
                        status = ActionTraceStatus.FAILURE,
                        message = failure.message,
                    ),
                ),
                success = false,
            )
        }

        val structure = FlowStructure.analyze(task.actions)
        if (structure.error != null) {
            ctx.variables.popScope()
            val failure = ActionResult.Failure("flow control error: ${structure.error}")
            return TaskRunReport(
                taskId = task.id,
                taskName = task.name,
                startedAt = started,
                durationMs = System.currentTimeMillis() - started,
                results = listOf(failure),
                traces = listOf(
                    ActionExecutionTrace(
                        index = 0,
                        actionType = "flow",
                        label = "flow control",
                        durationMs = 0,
                        status = ActionTraceStatus.FAILURE,
                        message = failure.message,
                    ),
                ),
                success = false,
            )
        }

        val loopStack = ArrayDeque<LoopFrame>()
        val tryStack = ArrayDeque<TryFrame>()
        val handledFailureIndices = mutableSetOf<Int>()
        var unhandledFailure = false
        try {
            var pc = 0
            var steps = 0
            while (pc in task.actions.indices) {
                if (++steps > MAX_FLOW_STEPS) {
                    val failure = ActionResult.Failure("flow step budget ($MAX_FLOW_STEPS) exceeded")
                    results += failure
                    traces += markerTrace(pc, task.actions[pc], failure, ActionTraceStatus.FAILURE)
                    unhandledFailure = true
                    break
                }
                val spec = task.actions[pc]
                if (FlowControl.isControl(spec.type)) {
                    val outcome = stepControl(pc, spec, structure, loopStack, tryStack)
                    results += outcome.result
                    traces += outcome.trace
                    if (outcome.halt) {
                        if (outcome.result is ActionResult.Failure) unhandledFailure = true
                        break
                    }
                    pc = outcome.nextPc
                    continue
                }

                onStep?.invoke(pc, spec.label ?: spec.type)
                val (result, trace) = runOne(pc, spec)
                results += result
                traces += trace
                if (result is ActionResult.Failure) {
                    val recovery = recoverFailure(pc, spec, result, structure, loopStack, tryStack)
                    recovery.reason?.let { reason ->
                        traces[traces.lastIndex] = traces.last().copy(
                            message = "${traces.last().message}; $reason",
                        )
                    }
                    if (recovery.nextPc != null) {
                        handledFailureIndices += results.lastIndex
                        pc = recovery.nextPc
                        continue
                    }
                    if (!spec.continueOnError) {
                        unhandledFailure = true
                        break
                    }
                }
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
            success = !unhandledFailure && results.withIndex().all { (index, result) ->
                result !is ActionResult.Failure || index in handledFailureIndices
            }
        )
    }

    private class ControlOutcome(
        val result: ActionResult,
        val trace: ActionExecutionTrace,
        val nextPc: Int,
        val halt: Boolean = false,
    )

    private data class FailureRecovery(
        val nextPc: Int?,
        val reason: String? = null,
    )

    private fun stepControl(
        pc: Int,
        spec: ActionSpec,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
    ): ControlOutcome {
        fun outcome(message: String, nextPc: Int, halt: Boolean = false) = ControlOutcome(
            result = ActionResult.Success,
            trace = markerTrace(pc, spec, ActionResult.Success, ActionTraceStatus.SUCCESS, message),
            nextPc = nextPc,
            halt = halt,
        )

        return when (spec.type) {
            FlowControl.IF -> {
                val condition = spec.args["condition"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: spec.condition?.trim()?.takeIf { it.isNotBlank() }
                    ?: "true"
                val matched = evaluateConditionString(condition)
                if (matched) {
                    outcome("if ($condition) -> true", pc + 1)
                } else {
                    val target = structure.ifToElse[pc]?.plus(1)
                        ?: structure.ifToEndif.getValue(pc) + 1
                    outcome("if ($condition) -> false", target)
                }
            }
            FlowControl.ELSE -> outcome("else", structure.elseToEndif.getValue(pc) + 1)
            FlowControl.ENDIF -> outcome("endif", pc + 1)
            FlowControl.FOREACH -> {
                val listName = listOf("list", "in", "array", "items")
                    .firstNotNullOfOrNull { spec.args[it]?.trim()?.takeIf(String::isNotBlank) }
                val itemVar = spec.args["var"]?.trim()?.takeIf { it.isNotBlank() } ?: "item"
                val items = listName?.let { ctx.variables.getArrayItems(it) }.orEmpty()
                val endfor = structure.foreachToEndfor.getValue(pc)
                if (items.isEmpty()) {
                    outcome("foreach $listName -> 0 items", endfor + 1)
                } else {
                    val sensitive = listName?.let(ctx.variables::isArraySensitive) == true
                    loopStack.addLast(LoopFrame(pc, items, itemVar, sensitive, 0))
                    ctx.variables.set(itemVar, items[0], sensitive = sensitive)
                    outcome("foreach $listName -> ${items.size} items (1/${items.size})", pc + 1)
                }
            }
            FlowControl.ENDFOR -> {
                val frame = loopStack.lastOrNull()
                if (frame == null || frame.foreachIndex != structure.endforToForeach[pc]) {
                    ControlOutcome(
                        result = ActionResult.Failure("flow.endfor without an active loop"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("flow.endfor without an active loop"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                } else {
                    frame.index++
                    if (frame.index < frame.items.size) {
                        ctx.variables.set(frame.itemVar, frame.items[frame.index], sensitive = frame.sensitive)
                        outcome("loop ${frame.index + 1}/${frame.items.size}", frame.foreachIndex + 1)
                    } else {
                        loopStack.removeLast()
                        outcome("endfor", pc + 1)
                    }
                }
            }
            FlowControl.TRY -> {
                val config = FlowControl.parseTryConfig(spec.args)
                    ?: return ControlOutcome(
                        result = ActionResult.Failure("invalid flow.try retry bounds"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("invalid flow.try retry bounds"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                tryStack.addLast(
                    TryFrame(
                        tryIndex = pc,
                        catchIndex = structure.tryToCatch[pc],
                        endIndex = structure.tryToEndtry.getValue(pc),
                        config = config,
                        loopDepth = loopStack.size,
                    ),
                )
                outcome("try attempt 1/${config.maxAttempts}", pc + 1)
            }
            FlowControl.CATCH -> {
                val frame = tryStack.lastOrNull()
                if (frame == null || frame.catchIndex != pc) {
                    ControlOutcome(
                        result = ActionResult.Failure("flow.catch without an active try"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("flow.catch without an active try"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                } else if (frame.phase == TryPhase.BODY) {
                    // The try body completed normally; skip the handler and its end marker.
                    tryStack.removeLast()
                    outcome("catch skipped; try succeeded", frame.endIndex + 1)
                } else {
                    ctx.variables.set(FLOW_ERROR_CAUGHT, "true")
                    outcome("catch", pc + 1)
                }
            }
            FlowControl.ENDTRY -> {
                val frame = tryStack.removeLastOrNull()
                if (frame == null || frame.endIndex != pc) {
                    ControlOutcome(
                        result = ActionResult.Failure("flow.endtry without an active try"),
                        trace = markerTrace(pc, spec, ActionResult.Failure("flow.endtry without an active try"), ActionTraceStatus.FAILURE),
                        nextPc = pc + 1,
                        halt = true,
                    )
                } else {
                    outcome("endtry", pc + 1)
                }
            }
            FlowControl.STOP -> outcome("stop", pc + 1, halt = true)
            else -> outcome(spec.type, pc + 1)
        }
    }

    private suspend fun recoverFailure(
        pc: Int,
        spec: ActionSpec,
        failure: ActionResult.Failure,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
    ): FailureRecovery {
        var nonRetryReason: String? = null
        while (true) {
            val frame = tryStack.asReversed().firstOrNull { candidate ->
                candidate.phase == TryPhase.BODY && pc > candidate.tryIndex && pc < candidate.endIndex
            } ?: return FailureRecovery(nextPc = null, reason = nonRetryReason)

            while (tryStack.lastOrNull() !== frame) tryStack.removeLast()
            if (frame.attempt < frame.config.maxAttempts &&
                ActionRegistry.get(spec.type)?.retrySafetyFor(spec.args) == ActionRetrySafety.IDEMPOTENT
            ) {
                setFailureVariables(pc, spec, failure, frame.attempt, retrying = true, retryReason = null)
                frame.attempt++
                clearLoopsToDepth(loopStack, frame.loopDepth)
                val waitMs = retryBackoffMs(frame.config.backoffMs, frame.attempt - 1)
                if (waitMs > 0) delay(waitMs)
                return FailureRecovery(nextPc = frame.tryIndex + 1, reason = nonRetryReason)
            } else if (frame.attempt < frame.config.maxAttempts || frame.config.maxAttempts > 1) {
                nonRetryReason = retryReason(spec, ActionRegistry.get(spec.type)?.retrySafetyFor(spec.args))
            }

            frame.catchIndex?.let { catchIndex ->
                setFailureVariables(
                    pc,
                    spec,
                    failure,
                    frame.attempt,
                    retrying = false,
                    retryReason = nonRetryReason,
                )
                ctx.variables.set(FLOW_ERROR_CAUGHT, "false")
                frame.phase = TryPhase.CATCH
                clearLoopsToDepth(loopStack, frame.loopDepth)
                return FailureRecovery(nextPc = catchIndex + 1, reason = nonRetryReason)
            }

            // An uncaught nested failure propagates to the enclosing try block.
            tryStack.removeLast()
        }
    }

    private fun retryReason(spec: ActionSpec, safety: ActionRetrySafety?): String = when (safety) {
        ActionRetrySafety.NEVER ->
            "not retried: ${spec.type} is classified NEVER; flow.try retries only IDEMPOTENT actions"
        ActionRetrySafety.IDEMPOTENT ->
            "not retried: ${spec.type} exhausted the configured attempts"
        null ->
            "not retried: ${spec.type} has no runtime retry classification"
    }

    private fun setFailureVariables(
        pc: Int,
        spec: ActionSpec,
        failure: ActionResult.Failure,
        attempt: Int,
        retrying: Boolean,
        retryReason: String?,
    ) {
        ctx.variables.set(FLOW_ERROR_MESSAGE, failure.message)
        ctx.variables.set(FLOW_ERROR_ACTION, spec.type)
        ctx.variables.set(FLOW_ERROR_INDEX, (pc + 1).toString())
        ctx.variables.set(FLOW_ERROR_ATTEMPT, attempt.toString())
        ctx.variables.set(FLOW_ERROR_RETRYING, retrying.toString())
        ctx.variables.set(FLOW_ERROR_RETRY_REASON, retryReason.orEmpty())
    }

    private fun clearLoopsToDepth(loopStack: ArrayDeque<LoopFrame>, depth: Int) {
        while (loopStack.size > depth) loopStack.removeLast()
    }

    private fun markerTrace(
        index: Int,
        spec: ActionSpec,
        result: ActionResult,
        status: ActionTraceStatus,
        message: String = spec.type,
    ): ActionExecutionTrace = ActionExecutionTrace(
        index = index,
        actionType = spec.type,
        label = spec.label ?: spec.type,
        durationMs = 0,
        status = status,
        message = message,
    )

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
        val expansionReport = expandArgs(spec.type, spec.args)
        val timeoutMs = actionTimeoutMs(spec.type)
        val before = ctx.variables.toTemplateScope()
        val rawResult = try {
            withTimeout(timeoutMs) {
                ctx.variables.withSensitiveWrites(expansionReport.hasSecretDerivedValues()) {
                    action.run(ctx.forAction(expansionReport.sensitiveArgumentNames()), expansionReport.args)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ActionResult.Failure("timed out after ${timeoutMs / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure("threw: ${e.message}", e)
        }
        val result = if (rawResult is ActionResult.Failure && expansionReport.hasSecretDerivedValues()) {
            // Preserve the real error class/context for debuggability but scrub any secret-derived
            // argument value the action echoed into its message; drop the cause because its message
            // and stack could also embed the secret and a Throwable cannot be redacted in place.
            // Only literal echoes of the raw argument are removed — a secret the action transformed
            // (hashed/encoded/sliced) before failing is not detectable here, so the whole value is
            // over-redacted rather than partially matched, which errs toward non-disclosure.
            ActionResult.Failure(expansionReport.redactSecretDerivedValues(rawResult.message))
        } else {
            rawResult
        }
        val changes = variableChangesBetween(before, ctx.variables.toTemplateScope())
        return result to traceFor(index, spec, started, result, expansionReport, changes)
    }

    private suspend fun runSubTask(
        index: Int,
        spec: ActionSpec,
        started: Long,
    ): Pair<ActionResult, ActionExecutionTrace> {
        val expansionReport = expandArgs(spec.type, spec.args)
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
        if (target.id > 0L && target.id in executionChain) {
            return fail("sub-task '${target.name}' is already active in this execution chain")
        }

        // Pass any extra args as input variables scoped to the sub-task invocation: a dedicated
        // scope wraps the child run so local (lowercase) inputs are visible to the child through the
        // scope chain but are popped when it returns, never leaking into the parent's later actions.
        // Global (uppercase) outputs still flow back through the shared global namespace.
        val child = TaskRunner(
            ctx = ctx,
            templateExpressionEngine = templateExpressionEngine,
            resolveTask = resolveTask,
            depth = depth + 1,
            onStep = onStep,
            collisionCoordinator = collisionCoordinator,
            executionChain = executionChain + target.id,
        )
        ctx.variables.pushScope()
        val report = try {
            args.forEach { (key, value) ->
                if (key !in SUB_TASK_REF_KEYS) {
                    ctx.variables.set(key, value, sensitive = expansionReport.isArgumentSensitive(key))
                }
            }
            when (val collision = collisionCoordinator?.execute(target) { child.run(target) }) {
                null -> child.run(target)
                is TaskCollisionOutcome.Executed -> collision.value
                is TaskCollisionOutcome.Skipped -> return fail(collision.reason)
            }
        } finally {
            ctx.variables.popScope()
        }
        val result = if (report.success) {
            ActionResult.Success
        } else {
            ActionResult.Failure("sub-task '${target.name}' failed")
        }
        return result to traceFor(index, spec, started, result, expansionReport)
    }

    private fun shouldRun(spec: ActionSpec): Boolean {
        val condition = spec.condition?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return evaluateConditionString(condition)
    }

    /** Evaluates a condition string with legacy `%var` then bounded `{{ ... }}` expansion. */
    private fun evaluateConditionString(condition: String): Boolean {
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
        variableChanges: List<ActionVariableChange> = emptyList(),
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
        variableChanges = variableChanges,
    )

    private fun expandArgs(actionType: String, args: Map<String, String>): ActionArgumentExpansionReport {
        if (args.isEmpty()) return ActionArgumentExpansionReport.Empty

        val templateScope = ctx.variables.toTemplateScope(ctx.eventVariables)
        val expansions = mutableListOf<ActionArgumentExpansionTrace>()
        val expandedArgs = args.mapValues { (name, rawValue) ->
            val legacy = ctx.variables.expandTracked(rawValue)
            if (!legacy.value.contains("{{")) {
                if (legacy.isSecretDerived) {
                    expansions += ActionArgumentExpansionTrace(
                        argName = name,
                        rawValue = rawValue,
                        expandedValue = REDACTED_VALUE,
                        expressions = emptyList(),
                        warnings = emptyList(),
                        isSecretDerived = true,
                    )
                }
                return@mapValues legacy.value
            }

            val result = templateExpressionEngine.expand(legacy.value, templateScope)
            val isSecretDerived = legacy.isSecretDerived || result.traces.any { it.isSecretDerived }
            if (result.traces.isNotEmpty() || result.warnings.isNotEmpty() || isSecretDerived) {
                expansions += ActionArgumentExpansionTrace(
                    argName = name,
                    rawValue = rawValue,
                    expandedValue = if (isSecretDerived) REDACTED_VALUE else result.value,
                    expressions = result.traces.map { trace ->
                        if (trace.isSecretDerived) trace.copy(value = REDACTED_VALUE) else trace
                    },
                    warnings = result.warnings,
                    isSecretDerived = isSecretDerived,
                )
            }
            result.value
        }

        return ActionArgumentExpansionReport(expandedArgs, expansions, actionType)
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
    // Playback and speech suspend until completion; a long sound file or near-limit TTS
    // text legitimately outlives the default 60 s budget.
    actionType == "sound.play" || actionType == "tts.speak" -> MEDIA_ACTION_TIMEOUT_MS
    else -> DEFAULT_ACTION_TIMEOUT_MS
}

private const val DEFAULT_ACTION_TIMEOUT_MS = 60_000L
private const val MEDIA_ACTION_TIMEOUT_MS = 600_000L // 10 minutes

// The engine budget must exceed WaitAction.MAX_WAIT_MS (30 min): the timeout clock starts
// before the action parses its arguments, so an equal budget deterministically failed a
// wait at the documented maximum.
private const val MAX_WAIT_TIMEOUT_MS = 1_860_000L // 30 minutes + 60 s margin

const val SUB_TASK_ACTION_ID = "task.run"
const val MAX_SUBTASK_DEPTH = 8
/** Argument keys `task.run` accepts as its target, in precedence order. */
val SUB_TASK_REF_KEYS = listOf("task", "name", "id")

/** Safety cap on total interpreted steps to bound pathological flow.foreach loops. */
private const val MAX_FLOW_STEPS = 100_000
private const val FLOW_ERROR_MESSAGE = "FLOW_ERROR_MESSAGE"
private const val FLOW_ERROR_ACTION = "FLOW_ERROR_ACTION"
private const val FLOW_ERROR_INDEX = "FLOW_ERROR_INDEX"
private const val FLOW_ERROR_ATTEMPT = "FLOW_ERROR_ATTEMPT"
private const val FLOW_ERROR_RETRYING = "FLOW_ERROR_RETRYING"
private const val FLOW_ERROR_RETRY_REASON = "FLOW_ERROR_RETRY_REASON"
private const val FLOW_ERROR_CAUGHT = "FLOW_ERROR_CAUGHT"

private fun retryBackoffMs(baseMs: Long, retryNumber: Int): Long {
    if (baseMs <= 0L) return 0L
    val multiplier = 1L shl retryNumber.coerceIn(0, 16)
    return (baseMs * multiplier).coerceAtMost(FlowControl.MAX_BACKOFF_MS)
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
    val variableChanges: List<ActionVariableChange> = emptyList(),
)

/**
 * One variable an action added or modified. Captured per step so a finished run answers "what did
 * this task actually set?" — the run log previously showed only what went *into* each action.
 */
data class ActionVariableChange(
    val scope: VariableChangeScope,
    val name: String,
    val value: String,
    val added: Boolean,
    val sensitive: Boolean = false,
)

enum class VariableChangeScope { TASK, GLOBAL, ARRAY }

/**
 * Variables an action added or modified, derived by diffing the store around the call.
 *
 * Deltas are used rather than a full snapshot because a run's interesting output is what changed,
 * and a snapshot per step would grow the run log with the same untouched globals over and over.
 * Sensitive names carry the flag so the value is redacted at the serialization boundary — the
 * value itself is never written to the log.
 */
internal fun variableChangesBetween(
    before: TemplateScope,
    after: TemplateScope,
): List<ActionVariableChange> = buildList {
    fun diff(
        scope: VariableChangeScope,
        beforeValues: Map<String, String>,
        afterValues: Map<String, String>,
        sensitiveNames: Set<String>,
    ) {
        afterValues.entries
            .sortedBy { it.key }
            .forEach { (name, value) ->
                if (!beforeValues.containsKey(name)) {
                    add(ActionVariableChange(scope, name, value, added = true, sensitive = name in sensitiveNames))
                } else if (beforeValues[name] != value) {
                    add(ActionVariableChange(scope, name, value, added = false, sensitive = name in sensitiveNames))
                }
            }
    }

    diff(VariableChangeScope.TASK, before.task, after.task, after.sensitiveTask)
    diff(VariableChangeScope.GLOBAL, before.global, after.global, after.sensitiveGlobal)
    diff(
        VariableChangeScope.ARRAY,
        before.arrays.mapValues { (_, items) -> items.joinToString(", ") },
        after.arrays.mapValues { (_, items) -> items.joinToString(", ") },
        after.sensitiveArrays,
    )
}

data class ActionArgumentExpansionTrace(
    val argName: String,
    val rawValue: String,
    val expandedValue: String,
    val expressions: List<TemplateExpansionTrace>,
    val warnings: List<String>,
    val isSecretDerived: Boolean = false,
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
    val actionType: String? = null,
) {
    fun templateWarnings(): List<String> =
        expansions.flatMap { expansion -> expansion.warnings.map { "${expansion.argName}: $it" } }.distinct()

    fun summary(): String? {
        if (expansions.isEmpty()) return null
        return expansions
            .take(MAX_SUMMARY_ARGS)
            .joinToString(", ") { expansion ->
                "${expansion.argName}=${summarizeArgValue(actionType, expansion.argName, expansion.expandedValue, expansion.isSecretDerived)}"
            }
            .let { summary ->
                val remaining = expansions.size - MAX_SUMMARY_ARGS
                if (remaining > 0) "$summary, +$remaining more" else summary
            }
    }

    fun hasSecretDerivedValues(): Boolean = expansions.any { it.isSecretDerived }

    /**
     * Removes the raw values of secret-derived arguments from [message]. The raw expanded values
     * live in [args] (what the action actually received); the per-expansion trace already stores a
     * redacted placeholder, so it cannot be used for scrubbing. Longest values are replaced first so
     * a secret that contains a shorter secret as a substring is fully removed.
     */
    fun redactSecretDerivedValues(message: String): String {
        val secretValues = expansions
            .filter { it.isSecretDerived }
            .mapNotNull { args[it.argName]?.takeIf(String::isNotBlank) }
            .distinct()
            .sortedByDescending { it.length }
        var redacted = message
        for (value in secretValues) {
            redacted = redacted.replace(value, REDACTED_VALUE)
        }
        return redacted
    }

    fun sensitiveArgumentNames(): Set<String> = expansions
        .filter { it.isSecretDerived }
        .mapTo(linkedSetOf()) { it.argName }

    fun isArgumentSensitive(name: String): Boolean = expansions.any {
        it.argName == name && it.isSecretDerived
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
        .flatMap { it.toTemplateDiagnosticLines(actionType) }
        .take(MAX_TEMPLATE_TRACE_LINES_PER_ACTION)
        .forEach(::add)
    variableChanges
        .take(MAX_VARIABLE_CHANGE_LINES_PER_ACTION)
        .map { it.toRunLogLine() }
        .forEach(::add)
}

private fun ActionVariableChange.toRunLogLine(): String = listOf(
    VARIABLE_CHANGE_PREFIX,
    scope.name.lowercase(),
    name.toLogField(),
    if (added) VARIABLE_CHANGE_ADDED else VARIABLE_CHANGE_UPDATED,
    if (sensitive) REDACTED_VALUE else value.toLogField(),
).joinToString("	")

private fun ActionArgumentExpansionTrace.toTemplateDiagnosticLines(actionType: String?): List<String> =
    expressions.map { expressionTrace ->
        val sensitive = isSecretDerived ||
            ActionArgumentSensitivity.isSensitive(actionType, argName) ||
            expressionTrace.isSecretDerived
        listOf(
            TEMPLATE_TRACE_PREFIX,
            argName.toLogField(),
            expressionTrace.source.name.lowercase().toLogField(),
            if (sensitive) REDACTED_VALUE else expressionTrace.expression.toLogField(),
            if (sensitive) REDACTED_VALUE else expressionTrace.value.toLogField(),
            expressionTrace.warning.orEmpty().toLogField(),
        ).joinToString("\t")
    }

private fun summarizeArgValue(
    actionType: String?,
    argName: String,
    value: String,
    forceRedact: Boolean = false,
): String {
    if (forceRedact || ActionArgumentSensitivity.isSensitive(actionType, argName)) {
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

private const val TEMPLATE_TRACE_PREFIX = "Template:"
private const val REDACTED_VALUE = ActionArgumentSensitivity.REDACTED
private const val MAX_SUMMARY_ARGS = 4
private const val MAX_SUMMARY_VALUE_LENGTH = 80
private const val MAX_TEMPLATE_TRACE_LINES_PER_ACTION = 8
private const val MAX_VARIABLE_CHANGE_LINES_PER_ACTION = 8
private const val VARIABLE_CHANGE_PREFIX = "Var:"
private const val VARIABLE_CHANGE_ADDED = "added"
private const val VARIABLE_CHANGE_UPDATED = "updated"
private const val MAX_TEMPLATE_TRACE_FIELD_LENGTH = 120
