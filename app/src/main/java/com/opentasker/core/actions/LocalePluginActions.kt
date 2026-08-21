package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.plugins.locale.LocalePluginConditionState
import com.opentasker.core.plugins.locale.LocalePluginHost
import com.opentasker.core.plugins.locale.LocalePluginRequest

/**
 * Outcome decisions for the two Locale host actions, kept free of Android types so the run-log
 * honesty rules below are testable on the JVM.
 */
internal object LocalePluginActionPolicy {
    const val DEFAULT_TIMEOUT_MS = 5_000L

    /**
     * Returns null when the caller supplied something that is not a whole number of milliseconds.
     * An absent value still means the default; a typo does not, because silently substituting the
     * default hides a misconfigured action behind a green run-log row.
     */
    fun parseTimeout(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        return if (value.isEmpty()) DEFAULT_TIMEOUT_MS else value.toLongOrNull()
    }

    /**
     * Unknown is not a condition answer. It is what the host reports when the plugin never replied,
     * timed out, or returned RESULT_CONDITION_UNKNOWN, so reporting it as Success claims an
     * observation that never happened. Unsatisfied is a real answer and stays a successful query
     * unless the action was configured to require a match.
     */
    fun conditionResult(
        state: LocalePluginConditionState,
        requireSatisfied: Boolean,
        message: String,
    ): ActionResult = when (state) {
        LocalePluginConditionState.Satisfied -> ActionResult.Success
        LocalePluginConditionState.Unsatisfied ->
            if (requireSatisfied) ActionResult.Failure(message) else ActionResult.Success
        LocalePluginConditionState.Unknown -> ActionResult.Failure(message)
    }

    /** The trace line for a query, so Unknown reads differently from Unsatisfied in the run log. */
    fun conditionTrace(state: LocalePluginConditionState, message: String): String = when (state) {
        LocalePluginConditionState.Satisfied -> "Condition satisfied. $message"
        LocalePluginConditionState.Unsatisfied -> "Condition not satisfied. $message"
        LocalePluginConditionState.Unknown ->
            "Condition state unknown: the plugin did not report one. $message"
    }

    /**
     * FIRE_SETTING has no reply in the Locale protocol, so the host returns as soon as the
     * broadcast leaves. The trace says so rather than letting a plain green row imply the plugin
     * acted on it.
     */
    fun settingDispatchTrace(message: String): String =
        "$message Delivery is unconfirmed: the Locale setting protocol sends no acknowledgement."
}

class LocalePluginSettingAction : DeclaredAction(ActionCatalog.require("plugin.locale.fire")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val packageName = args["package"]?.trim().orEmpty()
        val bundleJson = args["bundleJson"].orEmpty()
        val blurb = args["blurb"].orEmpty()
        val timeoutMs = LocalePluginActionPolicy.parseTimeout(args["timeoutMs"])
            ?: return ActionResult.Failure("Timeout must be a whole number of milliseconds")

        return try {
            val result = LocalePluginHost(ctx.app).fireSetting(
                LocalePluginRequest(
                    packageName = packageName,
                    bundleJson = bundleJson,
                    blurb = blurb,
                    timeoutMs = timeoutMs,
                )
            )
            if (result.success) {
                ctx.logger(LocalePluginActionPolicy.settingDispatchTrace(result.message))
                ActionResult.Success
            } else {
                ActionResult.Failure(result.message)
            }
        } catch (ex: Exception) {
            ActionResult.Failure("Locale plugin failed: ${ex.message}", ex)
        }
    }
}

class LocalePluginConditionQueryAction : DeclaredAction(ActionCatalog.require("plugin.locale.query")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val packageName = args["package"]?.trim().orEmpty()
        val bundleJson = args["bundleJson"].orEmpty()
        val blurb = args["blurb"].orEmpty()
        val timeoutMs = LocalePluginActionPolicy.parseTimeout(args["timeoutMs"])
            ?: return ActionResult.Failure("Timeout must be a whole number of milliseconds")
        val resultVariable = args["resultVariable"]?.trim()?.removePrefix("%").orEmpty()
        val requireSatisfied = args["requireSatisfied"]?.toBooleanStrictOrNull() ?: false

        return try {
            val result = LocalePluginHost(ctx.app).queryCondition(
                LocalePluginRequest(
                    packageName = packageName,
                    bundleJson = bundleJson,
                    blurb = blurb,
                    timeoutMs = timeoutMs,
                )
            )
            // Written before the outcome is decided so an automation can still branch on the
            // reported state after an unknown query fails the action.
            if (resultVariable.isNotBlank()) {
                ctx.variables.set(resultVariable, result.state.serializedName)
            }
            ctx.logger(LocalePluginActionPolicy.conditionTrace(result.state, result.message))
            LocalePluginActionPolicy.conditionResult(result.state, requireSatisfied, result.message)
        } catch (ex: Exception) {
            ActionResult.Failure("Locale plugin condition query failed: ${ex.message}", ex)
        }
    }
}
