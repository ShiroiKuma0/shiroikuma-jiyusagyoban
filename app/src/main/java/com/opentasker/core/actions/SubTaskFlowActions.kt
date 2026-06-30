package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/** Action id of the Return Values action; its named outputs use the [RETURN_VALUE_PREFIX] arg keys. */
const val RETURN_VALUES_ACTION_ID = "task.return"
const val RETURN_VALUE_PREFIX = "ret:"
const val FAIL_ACTION_ID = "flow.fail"

/**
 * Set named return values for the current task. A caller's Run Task action surfaces these as
 * variables under its results prefix. Each `ret:<name>` arg becomes a named result; values are
 * expanded in this task's scope first (so they can reference its variables and `{{ param.* }}`).
 */
class ReturnValuesAction : Action {
    override val id = RETURN_VALUES_ACTION_ID
    override val category = ActionCategory.FLOW

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        var count = 0
        args.forEach { (key, value) ->
            if (key.startsWith(RETURN_VALUE_PREFIX)) {
                ctx.returns[key.removePrefix(RETURN_VALUE_PREFIX)] = value
                count++
            }
        }
        ctx.logger("Return values: $count")
        return ActionResult.Success
    }
}

/**
 * Fail the current task with a message. The failure propagates like any action failure (honouring
 * continueOnError) and is surfaced to a caller's Run Task action as its error.
 */
class FailAction : Action {
    override val id = FAIL_ACTION_ID
    override val category = ActionCategory.FLOW

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val message = args["message"]?.trim()?.ifBlank { null } ?: "Task failed"
        return ActionResult.Failure(message)
    }
}
