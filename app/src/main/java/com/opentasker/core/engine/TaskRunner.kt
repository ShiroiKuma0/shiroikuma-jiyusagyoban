package com.opentasker.core.engine

import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.capabilities.CapabilityPrompt
import com.opentasker.core.capabilities.CapabilityState
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.model.VariableNamePolicy
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
    private val originatingProfileId: Long? = null,
    private val originatingProfileName: String? = null,
    /** Injectable clock for deterministic scenario tests; production uses wall-clock time. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Resolves a projectId → project name, for the permission-block dialog. Null → "Unfiled". */
    private val projectNameResolver: (suspend (Long?) -> String?)? = null,
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
        // Pre-flight permission gate: if the task uses an action whose special access isn't granted,
        // DON'T run any action — show a modal OK dialog naming the task, project, and missing permission(s).
        val missing = CapabilityState.missingForTask(task, ctx.app)
        if (missing.isNotEmpty()) {
            val preStart = System.currentTimeMillis()
            val project = runCatching { projectNameResolver?.invoke(task.projectId) }.getOrNull() ?: "Unfiled"
            // Still blocked, still logged — but don't stack another modal on top of the settings page
            // the user was just sent to. A per-minute profile would otherwise re-raise it immediately.
            if (!CapabilityPrompt.allQuiet(missing.map { it.requirement })) {
                showPermissionBlockDialog(project, task.name, missing)
            }
            val summary = missing.joinToString(", ") { CapabilityState.shortLabel(it.requirement) }
            val failure = ActionResult.Failure("Not run — missing permission(s): $summary")
            return TaskRunReport(
                taskId = task.id,
                taskName = task.name,
                startedAt = preStart,
                durationMs = System.currentTimeMillis() - preStart,
                results = listOf(failure),
                traces = listOf(
                    ActionExecutionTrace(
                        index = 0,
                        actionType = "permission",
                        label = "permission check",
                        durationMs = 0,
                        status = ActionTraceStatus.FAILURE,
                        message = failure.message,
                    ),
                ),
                success = false,
            )
        }

        ctx.variables.pushScope()
        val started = now()
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
        val armedElseIndices = mutableSetOf<Int>()
        val handledFailureIndices = mutableSetOf<Int>()
        var unhandledFailure = false
        // Only the FIRST unhandled failure is kept: it is the one that actually ended the run, and a
        // later one (from a continueOnError action further down) would misname the cause.
        var structuredFailure: StructuredTaskError? = null
        fun recordFailure(index: Int, spec: ActionSpec, failure: ActionResult.Failure) {
            if (structuredFailure != null) return
            structuredFailure = failure.structuredError ?: StructuredTaskError(
                taskId = task.id,
                taskName = task.name,
                actionId = spec.id,
                actionIndex = index + 1,
                actionType = spec.type,
                message = failure.message,
                attemptCount = 1,
            )
        }
        try {
            var pc = 0
            var steps = 0
            while (pc in task.actions.indices) {
                if (++steps > MAX_FLOW_STEPS) {
                    val failure = ActionResult.Failure("flow step budget ($MAX_FLOW_STEPS) exceeded")
                    results += failure
                    traces += markerTrace(pc, task.actions[pc], failure, ActionTraceStatus.FAILURE)
                    recordFailure(pc, task.actions[pc], failure)
                    unhandledFailure = true
                    break
                }
                val spec = task.actions[pc]
                if (FlowControl.isControl(spec.type)) {
                    val outcome = stepControl(pc, spec, structure, loopStack, tryStack, armedElseIndices)
                    results += outcome.result
                    traces += outcome.trace
                    if (outcome.halt) {
                        (outcome.result as? ActionResult.Failure)?.let {
                            recordFailure(pc, spec, it)
                            unhandledFailure = true
                        }
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
                    val recoveryPc = recoverFailure(pc, spec, result, structure, loopStack, tryStack)
                    if (recoveryPc != null) {
                        handledFailureIndices += results.lastIndex
                        pc = recoveryPc
                        continue
                    }
                    if (!spec.continueOnError) {
                        recordFailure(pc, spec, result)
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
            durationMs = now() - started,
            results = results,
            traces = traces,
            success = !unhandledFailure && results.withIndex().all { (index, result) ->
                result !is ActionResult.Failure || index in handledFailureIndices
            },
            structuredError = structuredFailure,
        )
    }

    private class ControlOutcome(
        val result: ActionResult,
        val trace: ActionExecutionTrace,
        val nextPc: Int,
        val halt: Boolean = false,
    )

    private fun stepControl(
        pc: Int,
        spec: ActionSpec,
        structure: FlowStructure,
        loopStack: ArrayDeque<LoopFrame>,
        tryStack: ArrayDeque<TryFrame>,
        armedElseIndices: MutableSet<Int>,
    ): ControlOutcome {
        fun outcome(message: String, nextPc: Int, halt: Boolean = false) = ControlOutcome(
            result = ActionResult.Success,
            trace = markerTrace(pc, spec, ActionResult.Success, ActionTraceStatus.SUCCESS, message),
            nextPc = nextPc,
            halt = halt,
        )

        fun skipped(message: String, nextPc: Int) = ControlOutcome(
            result = ActionResult.Skip,
            trace = markerTrace(pc, spec, ActionResult.Skip, ActionTraceStatus.SKIPPED, message),
            nextPc = nextPc,
        )

        // The generic "Run only if" guard (ActionSpec.condition — what a Tasker <ConditionList>
        // imports to). Non-control actions evaluate it in runOne via shouldRun, but control
        // actions are dispatched here before runOne is ever reached, so each branch with real
        // behavior applies it explicitly. Tasker semantics: an unmet guard skips the action, it
        // never aborts the task. For the block-opening markers (foreach, try) the whole block is
        // the action, so skipping the marker skips its block — falling through into the body
        // would execute it with no active frame and abort at the closing marker. flow.if handles
        // the guard inside its own branch because the importer copies the guard into
        // args["condition"] (both fields equal), and that one expression must not apply twice.
        // The remaining markers are structural or error-path bookkeeping (endif, catch, endtry)
        // and deliberately ignore the guard: skipping them would corrupt block state.
        val guard = spec.condition?.trim()?.takeIf { it.isNotBlank() }
        fun guardMet(): Boolean = guard == null || evaluateConditionString(guard)

        return when (spec.type) {
            FlowControl.IF -> {
                val argCondition = spec.args["condition"]?.trim()?.takeIf { it.isNotBlank() }
                val condition = argCondition ?: guard ?: "true"
                // A guard distinct from the if's own test gates the branch alongside it: both
                // must hold. When the guard IS the test (an imported ConditionList lands in both
                // fields with the same text) it is evaluated once.
                val distinctGuard = guard != null && argCondition != null && guard != argCondition
                val display = if (distinctGuard) "$condition, only if $guard" else condition
                val matched = evaluateConditionString(condition) && (!distinctGuard || guardMet())
                if (matched) {
                    outcome("if ($display) -> true", pc + 1)
                } else {
                    val elseIndex = structure.ifToElse[pc]
                    if (elseIndex != null) {
                        // Land on the else marker itself (not past it) so an "Else If" guard on
                        // that marker can decide whether its branch runs.
                        armedElseIndices += elseIndex
                        outcome("if ($display) -> false", elseIndex)
                    } else {
                        outcome("if ($display) -> false", structure.ifToEndif.getValue(pc) + 1)
                    }
                }
            }
            FlowControl.ELSE -> {
                if (armedElseIndices.remove(pc)) {
                    // Reached from a false if: this marker opens the else branch. A guard here is
                    // Tasker's "Else If" — unmet means the branch is skipped, not run.
                    if (guardMet()) {
                        outcome(if (guard == null) "else" else "else if ($guard) -> true", pc + 1)
                    } else {
                        skipped("else if ($guard) -> false", structure.elseToEndif.getValue(pc) + 1)
                    }
                } else {
                    // Fell in from the end of the taken if branch: exit the block.
                    outcome("else", structure.elseToEndif.getValue(pc) + 1)
                }
            }
            FlowControl.ENDIF -> outcome("endif", pc + 1)
            FlowControl.FOREACH -> if (!guardMet()) {
                skipped("foreach skipped: condition ($guard) -> false", structure.foreachToEndfor.getValue(pc) + 1)
            } else {
                val listName = listOf("list", "in", "array", "items")
                    .firstNotNullOfOrNull { key ->
                        spec.args[key]?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
                            arrayReferenceName(spec.type, key, raw) ?: raw
                        }
                    }
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
                } else if (!guardMet()) {
                    // Tasker semantics for a skipped End For: no jump back happens, so the loop
                    // exits and execution continues after the marker.
                    loopStack.removeLast()
                    skipped("endfor skipped: condition ($guard) -> false; loop exited", pc + 1)
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
            FlowControl.TRY -> if (!guardMet()) {
                skipped("try skipped: condition ($guard) -> false", structure.tryToEndtry.getValue(pc) + 1)
            } else {
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
            FlowControl.STOP -> if (!guardMet()) {
                skipped("stop skipped: condition ($guard) -> false", pc + 1)
            } else {
                outcome("stop", pc + 1, halt = true)
            }
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
    ): Int? {
        while (true) {
            val frame = tryStack.asReversed().firstOrNull { candidate ->
                candidate.phase == TryPhase.BODY && pc > candidate.tryIndex && pc < candidate.endIndex
            } ?: return null

            while (tryStack.lastOrNull() !== frame) tryStack.removeLast()
            if (frame.attempt < frame.config.maxAttempts &&
                ActionRegistry.get(spec.type)?.retrySafety == ActionRetrySafety.IDEMPOTENT
            ) {
                setFailureVariables(pc, spec, failure, frame.attempt, retrying = true)
                frame.attempt++
                clearLoopsToDepth(loopStack, frame.loopDepth)
                val waitMs = retryBackoffMs(frame.config.backoffMs, frame.attempt - 1)
                if (waitMs > 0) delay(waitMs)
                return frame.tryIndex + 1
            }

            frame.catchIndex?.let { catchIndex ->
                setFailureVariables(pc, spec, failure, frame.attempt, retrying = false)
                ctx.variables.set(FLOW_ERROR_CAUGHT, "false")
                frame.phase = TryPhase.CATCH
                clearLoopsToDepth(loopStack, frame.loopDepth)
                return catchIndex + 1
            }

            // An uncaught nested failure propagates to the enclosing try block.
            tryStack.removeLast()
        }
    }

    private fun setFailureVariables(
        pc: Int,
        spec: ActionSpec,
        failure: ActionResult.Failure,
        attempt: Int,
        retrying: Boolean,
    ) {
        ctx.variables.set(FLOW_ERROR_MESSAGE, failure.message)
        ctx.variables.set(FLOW_ERROR_ACTION, spec.type)
        ctx.variables.set(FLOW_ERROR_INDEX, (pc + 1).toString())
        ctx.variables.set(FLOW_ERROR_ATTEMPT, attempt.toString())
        ctx.variables.set(FLOW_ERROR_RETRYING, retrying.toString())
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
        val started = now()
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
        if (target.id > 0L && target.id in executionChain) {
            return fail("sub-task '${target.name}' is already active in this execution chain")
        }

        // Named parameters (param:<name>); values are already expanded in the caller's scope.
        val parameters = buildMap {
            args.forEach { (key, value) ->
                if (key.startsWith(SUB_TASK_PARAM_PREFIX)) put(key.removePrefix(SUB_TASK_PARAM_PREFIX), value)
            }
        }
        val resultsPrefix = args[SUB_TASK_RESULTS_PREFIX_KEY]?.trim().orEmpty()

        // Isolated child: shares globals + arrays, fresh locals, read-only params, its own returns.
        // The child resolves its %MixedCase project-globals against ITS OWN project.
        val childCtx = ActionContext(
            app = ctx.app,
            variables = ctx.variables.childScope(target.projectId),
            eventVariables = emptyMap(),
            parameters = parameters,
            returns = mutableMapOf(),
            logger = ctx.logger,
        )
        val child = TaskRunner(
            ctx = childCtx,
            templateExpressionEngine = templateExpressionEngine,
            resolveTask = resolveTask,
            depth = depth + 1,
            onStep = onStep,
            collisionCoordinator = collisionCoordinator,
            executionChain = executionChain + target.id,
            originatingProfileId = originatingProfileId,
            originatingProfileName = originatingProfileName,
            now = now,
            projectNameResolver = projectNameResolver,
        )
        // Upstream's global last-mile rule: the target task's own collision policy decides whether a
        // nested run starts, waits behind the active one, or is skipped outright.
        val report = when (val collision = collisionCoordinator?.execute(target) { child.run(target) }) {
            null -> child.run(target)
            is TaskCollisionOutcome.Executed -> collision.value
            is TaskCollisionOutcome.Skipped -> return fail(collision.reason)
        }

        // Surface the sub-task's named results and status back to the caller as variables.
        childCtx.returns.forEach { (name, value) -> ctx.variables.set("$resultsPrefix$name", value) }
        ctx.variables.set("${resultsPrefix}ok", report.success.toString())
        val errorMessage = report.results.firstNotNullOfOrNull { (it as? ActionResult.Failure)?.message } ?: ""
        ctx.variables.set("${resultsPrefix}error", errorMessage)

        val result = if (report.success) {
            ActionResult.Success
        } else {
            ActionResult.Failure(errorMessage.ifBlank { "sub-task '${target.name}' failed" })
        }
        return result to traceFor(index, spec, started, result, expansionReport)
    }

    /**
     * Modal OK dialog (via DialogActivity) naming the task, project, and each missing permission.
     *
     * **It does not wait for the answer.** The pre-flight has already decided the task cannot run, so
     * there is nothing an answer could change about this run — and awaiting it held the engine for up
     * to two minutes per blocked task. Measured on 白い熊's phone on 2026-08-08: two tasks failed at
     * `120030ms` and `120022ms`, both of them entirely spent sitting in this dialog.
     *
     * Showing it also quiets the requirement immediately, so a workspace whose tasks fire by the
     * second raises one dialog rather than a stack of them.
     */
    private fun showPermissionBlockDialog(
        project: String,
        taskName: String,
        missing: List<CapabilityState.MissingCapability>,
    ) {
        val lines = missing.joinToString("\n") { m ->
            "• ${CapabilityState.shortLabel(m.requirement)} — needed by: ${m.actionTypes.joinToString(", ")}"
        }
        // A permission whose ordinary "you have not granted it" story is wrong says so instead — see
        // CapabilityState.blockedDetail.
        val details = missing.distinctBy { it.requirement }
            .mapNotNull { CapabilityState.blockedDetail(it.requirement, ctx.app) }
        val advice = details.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
            ?: "Grant it below, then run again."
        val text = "Can't run “$taskName” (project “$project”).\n\nMissing permission(s):\n$lines\n\n$advice"
        // Only offer a deep-link pill for a permission that actually has a System settings page to open.
        val grantable = missing.distinctBy { it.requirement }
            .filter { CapabilityState.settingsIntent(it.requirement, ctx.app) != null }
        val intent = android.content.Intent(ctx.app, DialogActivity::class.java).apply {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_TEXT)
            putExtra(DialogActivity.EXTRA_TITLE, "Permission required")
            putExtra(DialogActivity.EXTRA_TEXT, text)
            putExtra(DialogActivity.EXTRA_OK, "OK")
            putExtra(DialogActivity.EXTRA_SETTINGS_REQS, grantable.map { it.requirement.name }.toTypedArray())
            putExtra(DialogActivity.EXTRA_SETTINGS_LABELS, grantable.map { CapabilityState.shortLabel(it.requirement) }.toTypedArray())
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val shown = runCatching { ctx.app.startActivity(intent); true }.getOrDefault(false)
        // Quiet on SHOWING, not on the answer — the answer is never awaited. Tapping "Open … settings"
        // shortens the window from inside DialogActivity, so an active fix is re-checked sooner.
        if (shown) missing.forEach { CapabilityPrompt.markShown(it.requirement) }
    }

    private fun shouldRun(spec: ActionSpec): Boolean {
        val condition = spec.condition?.trim()?.takeIf { it.isNotBlank() } ?: return true
        return evaluateConditionString(condition)
    }

    /** Evaluates a condition string with legacy `%var` then bounded `{{ ... }}` expansion. */
    private fun evaluateConditionString(condition: String): Boolean {
        val legacyExpanded = ctx.variables.expand(rewriteParamSugar(condition))
        if (!legacyExpanded.contains("{{")) return ctx.variables.evaluateCondition(legacyExpanded)

        val expanded = templateExpressionEngine.expand(legacyExpanded, ctx.variables.toTemplateScope(ctx.eventVariables, ctx.parameters))
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
        durationMs = now() - started,
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

        val templateScope = ctx.variables.toTemplateScope(ctx.eventVariables, ctx.parameters)
        val expansions = mutableListOf<ActionArgumentExpansionTrace>()
        val expandedArgs = args.mapValues { (name, rawValue) ->
            val legacy = ctx.variables.expandTracked(rewriteParamSugar(rawValue))
            if (!legacy.value.contains("{{")) {
                if (legacy.isSecretDerived) {
                    expansions += ActionArgumentExpansionTrace(
                        argName = name,
                        rawValue = rawValue,
                        expandedValue = ActionArgumentSensitivity.REDACTED,
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
                    expandedValue = if (isSecretDerived) ActionArgumentSensitivity.REDACTED else result.value,
                    expressions = result.traces.map { trace ->
                        if (trace.isSecretDerived) trace.copy(value = ActionArgumentSensitivity.REDACTED) else trace
                    },
                    warnings = result.warnings,
                    isSecretDerived = isSecretDerived,
                )
            }
            result.value
        }

        // The fork keeps arrayReferenceName and ARRAY_REFERENCE at file level rather than in a
        // companion object, and already escapes the closing braces there — which is why upstream's
        // ICU-rejects-"}}" defect, fatal on device from 2026-08-10, never reached this build.
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
    /**
     * The failure that ended this run, when one did and nothing caught it.
     *
     * It names the action rather than just the message, which is what a fallback task needs to say
     * anything useful about what broke — and it is the presence of this, not `!success`, that makes a
     * run eligible for a fallback: a run stopped on purpose has no error to recover from.
     */
    val structuredError: StructuredTaskError? = null,
)

enum class ActionTraceStatus {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    SKIPPED,
}

private fun actionTimeoutMs(actionType: String): Long = when {
    actionType == "flow.wait" -> MAX_WAIT_TIMEOUT_MS
    // The two other network actions belong in this bucket too. The HA webhook's own contract
    // allows timeout_sec=30 across four attempts plus backoff (~140 s) and MQTT allows a 30 s
    // connect plus three 30 s reads, so the 60 s default silently truncated retries the user
    // configured and reported a timeout instead.
    actionType.startsWith("http.") || actionType == "download" || actionType == "ping" ||
        actionType == "mqtt.publish" || actionType == "integration.home_assistant.webhook" -> 120_000L
    // Playback and speech suspend until completion; a long sound file or near-limit TTS
    // text legitimately outlives the default 60 s budget.
    actionType == "sound.play" || actionType == "tts.speak" -> MEDIA_ACTION_TIMEOUT_MS
    // intent.send receiver-mode replies wait on real work in the target app (sister-app
    // state exports run for minutes); its result_timeout caps at 600 s, budget adds slack.
    actionType == "intent.send" -> INTENT_SEND_TIMEOUT_MS
    // Interactive dialogs suspend on the user; a picker pondered for over a minute must not
    // be killed under them (their own `timeout` arg still applies when set).
    actionType.startsWith("dialog.") || actionType == "app.pickmulti" -> MEDIA_ACTION_TIMEOUT_MS
    // A scrolling screenshot is read in slices, each a full detect + recognise pass: 25 of them on
    // the sample article's first page and 19 on its second. This is minutes of honest work, not a
    // hung action — measured, it was 64 s into the first page when the default budget killed it.
    actionType == "ocr.article" -> ARTICLE_ACTION_TIMEOUT_MS
    // The Huawei band's own ceilings are far above the 60 s default and are already enforced
    // inside the actions themselves. A pairing run waits on TWO human confirmations and then has
    // to stay connected for ninety seconds afterwards; a sync's configurable limit caps at 1800 s.
    //
    // A 60 s budget does not merely report a timeout here — it kills the action AFTER work has
    // started on the band, leaving it mid-conversation with no companion, which makes it revert to
    // its out-of-box wizard. It also cuts the run before the action can write its own Ok/Summary
    // variables, so the failure arrives with nothing at all to say what happened.
    actionType == "huawei.pair" -> HUAWEI_PAIR_TIMEOUT_MS
    actionType == "huawei.sync" -> HUAWEI_SYNC_TIMEOUT_MS
    // Announce, wait for the band to ask, send ~1 MB, then wait while it unpacks.
    actionType == "huawei.watchface" -> HUAWEI_WATCHFACE_TIMEOUT_MS
    actionType == "huawei.files" -> HUAWEI_PAIR_TIMEOUT_MS
    // These all open a full session — connect, handshake, authenticate — before they send
    // anything, which alone outlives the 60 s default on a band that is asleep. Probe and language
    // were left on the default by oversight: killing one at 60 s does not merely fail the action,
    // it walks away mid-conversation, and each of them has its own ceiling (the session watchdog)
    // that reports what actually went wrong if it is allowed to fire first.
    actionType == "huawei.settings" || actionType == "huawei.weather" ||
        actionType == "huawei.probe" || actionType == "huawei.language" ||
        actionType == "huawei.workouts" || actionType == "huawei.charts" ||
        actionType == "huawei.unpair" -> HUAWEI_PAIR_TIMEOUT_MS
    // The satellite watch is the one action here whose whole job is to WAIT. 衛星待受 asks for an
    // hour, because the band raises its request when a walk starts and that is the moment worth
    // catching — but every action is wrapped in `withTimeout(actionTimeoutMs(type))`, so an
    // unlisted `huawei.gnss` was silently held to 60 s and the hour was a fiction. The one catch
    // we have (2026-08-26, 39 s) survived only because the band happened to ask inside that minute.
    // Its own ceiling is the `wait` argument, which the action caps and reports against.
    actionType == "huawei.gnss" -> HUAWEI_GNSS_TIMEOUT_MS
    else -> DEFAULT_ACTION_TIMEOUT_MS
}

private const val DEFAULT_ACTION_TIMEOUT_MS = 60_000L
private const val MEDIA_ACTION_TIMEOUT_MS = 600_000L // 10 minutes
private const val INTENT_SEND_TIMEOUT_MS = 660_000L // result_timeout max (600 s) + 60 s margin
private const val ARTICLE_ACTION_TIMEOUT_MS = 1_800_000L // 30 minutes — a long article, many pages
private const val HUAWEI_PAIR_TIMEOUT_MS = 600_000L // 180 s of human + handshake + 90 s serving, with margin
private const val HUAWEI_SYNC_TIMEOUT_MS = 1_860_000L // the action's own maximum (1800 s) + 60 s margin

/**
 * The satellite watch: `HuaweiGnssAction.MAX_WAIT_SEC` (3600 s) plus the transfer that follows.
 *
 * The band asks and then pulls at its own pace — up to 806 KB across seven files — so the wait is
 * not the whole action. `HuaweiSyncRunner`'s session watchdog (420 s) bounds what comes after the
 * ask, and this sits above the two together so that the action's own ceiling is always the one that
 * fires.
 */
private const val HUAWEI_GNSS_TIMEOUT_MS = 4_080_000L // 3600 s watch + 420 s transfer + 60 s margin

/**
 * A watch-face install is bounded by HuaweiSyncRunner's own session watchdog (420 s) plus the grace
 * HuaweiSessionGuard allows the transfer to unwind in — so this only has to sit above that.
 *
 * It used to borrow the sync's 31-minute budget, and that is not a harmless over-estimate: a manual
 * run holds `ActiveAutomationViewModel.runActionBusy` for its whole duration, and that one boolean
 * greys the Run arrow on EVERY task in the list. So a wedged install did not just fail — it locked
 * the Tasks screen for half an hour, with the only stop button on a different screen entirely.
 * The action's own ceiling must always be the one that fires; this is the backstop, not the plan.
 */
private const val HUAWEI_WATCHFACE_TIMEOUT_MS = 480_000L

// The engine budget must exceed WaitAction.MAX_WAIT_MS (30 min): the timeout clock starts
// before the action parses its arguments, so an equal budget deterministically failed a
// wait at the documented maximum.
private const val MAX_WAIT_TIMEOUT_MS = 1_860_000L // 30 minutes + 60 s margin

const val SUB_TASK_ACTION_ID = "task.run"
const val MAX_SUBTASK_DEPTH = 8
internal val SUB_TASK_REF_KEYS = listOf("task", "name", "id")

/** Run Task arg keys: each `param:<name>` is a named parameter; results land under this prefix. */
const val SUB_TASK_PARAM_PREFIX = "param:"
const val SUB_TASK_RESULTS_PREFIX_KEY = "results_prefix"

private val PARAM_SUGAR_REGEX = Regex("%@([A-Za-z_][A-Za-z0-9_]*)")

/** Rewrites the terse `%@name` parameter reference into the canonical `{{ param.name }}`. */
private fun rewriteParamSugar(text: String): String =
    if (text.contains("%@")) text.replace(PARAM_SUGAR_REGEX) { "{{ param.${it.groupValues[1]} }}" } else text

/** Safety cap on total interpreted steps to bound pathological flow.foreach loops. */
private const val MAX_FLOW_STEPS = 100_000
private const val FLOW_ERROR_MESSAGE = "FLOW_ERROR_MESSAGE"
private const val FLOW_ERROR_ACTION = "FLOW_ERROR_ACTION"
private const val FLOW_ERROR_INDEX = "FLOW_ERROR_INDEX"
private const val FLOW_ERROR_ATTEMPT = "FLOW_ERROR_ATTEMPT"
private const val FLOW_ERROR_RETRYING = "FLOW_ERROR_RETRYING"
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
)

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
) {
    fun templateWarnings(): List<String> =
        expansions.flatMap { expansion -> expansion.warnings.map { "${expansion.argName}: $it" } }.distinct()

    fun summary(): String? {
        if (expansions.isEmpty()) return null
        return expansions
            .take(MAX_SUMMARY_ARGS)
            .joinToString(", ") { expansion ->
                "${expansion.argName}=${summarizeArgValue(expansion.argName, expansion.expandedValue, expansion.isSecretDerived)}"
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
            redacted = redacted.replace(value, ActionArgumentSensitivity.REDACTED)
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

/**
 * The editor writes an array slot as the explicit reference `{{ array.parts }}` rather than a bare
 * name, so the two actions whose contracts take an array — foreach's list and text.join's array —
 * resolve that form back to the array's own name before looking it up. Anything else is passed
 * through untouched, which is how a plain `%name` still works.
 */
private fun arrayReferenceName(actionType: String, argumentName: String, rawValue: String): String? {
    val validArgument = when (actionType) {
        "text.join" -> argumentName == "array"
        FlowControl.FOREACH -> argumentName in setOf("list", "in", "array", "items")
        else -> false
    }
    if (!validArgument) return null
    val match = ARRAY_REFERENCE.matchEntire(rawValue.trim()) ?: return null
    return VariableNamePolicy.normalize(match.groupValues[1])
}

// The closing braces are escaped, and that is not cosmetic. Android's regex engine is ICU, which
// treats `{` and `}` as interval-quantifier syntax and REJECTS a stray `}` — `Pattern.compile` throws
// PatternSyntaxException at class-init and takes the process down. Desktop Java is lenient and accepts
// the same pattern, so this compiles fine and every JVM test passes; it only fails on the phone.
// Upstream's own copy of this literal leaves them bare.
private val ARRAY_REFERENCE = Regex("\\{\\{\\s*array\\.([A-Za-z][A-Za-z0-9_-]*)\\s*\\}\\}")

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
        val sensitive = isSecretDerived || isSensitiveArgName(argName) || expressionTrace.isSecretDerived
        listOf(
            TEMPLATE_TRACE_PREFIX,
            argName.toLogField(),
            expressionTrace.source.name.lowercase().toLogField(),
            if (sensitive) ActionArgumentSensitivity.REDACTED else expressionTrace.expression.toLogField(),
            if (sensitive) ActionArgumentSensitivity.REDACTED else expressionTrace.value.toLogField(),
            expressionTrace.warning.orEmpty().toLogField(),
        ).joinToString("\t")
    }

private fun summarizeArgValue(argName: String, value: String, forceRedact: Boolean = false): String {
    if (forceRedact || isSensitiveArgName(argName)) {
        return ActionArgumentSensitivity.REDACTED
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
    "body",
    "headers",
    "key",
    "password",
    "query",
    "secret",
    "token",
)
private const val TEMPLATE_TRACE_PREFIX = "Template:"
private const val MAX_SUMMARY_ARGS = 4
private const val MAX_SUMMARY_VALUE_LENGTH = 80
private const val MAX_TEMPLATE_TRACE_LINES_PER_ACTION = 8
private const val MAX_TEMPLATE_TRACE_FIELD_LENGTH = 120
