package com.opentasker.core.actions

import com.opentasker.core.data.DateTimeOps
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.engine.ActionResult

private fun resolveEpochMillis(raw: String?): Long? {
    val value = raw?.trim()
    if (value.isNullOrEmpty() || value.equals("now", ignoreCase = true)) return System.currentTimeMillis()
    return value.toLongOrNull()
}

/**
 * Format an epoch-millis time into a string. Args: `time` (epoch millis or "now"), `format`
 * (pattern, default `yyyy-MM-dd HH:mm:ss`), `zone` (optional, e.g. `UTC`), `var` (output).
 */
class DateTimeFormatAction : Action {
    override val id = "datetime.format"
    override val category = ActionCategory.VARIABLE
    override val retrySafety = ActionRetrySafety.IDEMPOTENT

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val epoch = resolveEpochMillis(args["time"]) ?: return ActionResult.Failure("invalid time (expected epoch millis or 'now')")
        val pattern = args["format"]?.trim()?.ifBlank { null } ?: "yyyy-MM-dd HH:mm:ss"
        val varName = (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: "datetime"
        val formatted = DateTimeOps.format(epoch, pattern, args["zone"])
            ?: return ActionResult.Failure("invalid date format pattern: $pattern")
        ctx.variables.set(varName, formatted)
        ctx.logger("datetime.format -> \$$varName = $formatted")
        return ActionResult.Success
    }
}

/**
 * Parse a date-time string into epoch millis. Args: `text` (required), `format` (pattern, required),
 * `zone` (optional), `var` (output). Sets `%var` to the epoch-millis value.
 */
class DateTimeParseAction : Action {
    override val id = "datetime.parse"
    override val category = ActionCategory.VARIABLE
    override val retrySafety = ActionRetrySafety.IDEMPOTENT

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"]?.trim()?.ifBlank { null } ?: return ActionResult.Failure("missing text")
        val pattern = args["format"]?.trim()?.ifBlank { null } ?: return ActionResult.Failure("missing format pattern")
        val varName = (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: "datetime"
        val epoch = DateTimeOps.parse(text, pattern, args["zone"])
            ?: return ActionResult.Failure("could not parse '$text' with pattern '$pattern'")
        ctx.variables.set(varName, epoch.toString())
        ctx.logger("datetime.parse -> \$$varName = $epoch")
        return ActionResult.Success
    }
}

/**
 * Add (or subtract) a duration to an epoch-millis time. Args: `time` (epoch millis or "now"),
 * `amount` (integer, may be negative), `unit` (seconds/minutes/hours/days/weeks/months/years),
 * `var` (output). Sets `%var` to the resulting epoch-millis value.
 */
class DateTimeAddAction : Action {
    override val id = "datetime.add"
    override val category = ActionCategory.VARIABLE
    override val retrySafety = ActionRetrySafety.IDEMPOTENT

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val epoch = resolveEpochMillis(args["time"]) ?: return ActionResult.Failure("invalid time (expected epoch millis or 'now')")
        val amount = args["amount"]?.trim()?.toLongOrNull() ?: return ActionResult.Failure("invalid amount (expected an integer)")
        val unit = args["unit"]?.trim()?.ifBlank { null } ?: return ActionResult.Failure("missing unit")
        val varName = (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: "datetime"
        val result = DateTimeOps.add(epoch, amount, unit)
            ?: return ActionResult.Failure("invalid unit: $unit")
        ctx.variables.set(varName, result.toString())
        ctx.logger("datetime.add -> \$$varName = $result")
        return ActionResult.Success
    }
}
