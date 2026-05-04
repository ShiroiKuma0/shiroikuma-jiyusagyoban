package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.plugins.locale.LocalePluginHost
import com.opentasker.core.plugins.locale.LocalePluginRequest

class LocalePluginSettingAction : Action {
    override val id = "plugin.locale.fire"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val packageName = args["package"]?.trim().orEmpty()
        val bundleJson = args["bundleJson"].orEmpty()
        val blurb = args["blurb"].orEmpty()
        val timeoutMs = args["timeoutMs"]?.toLongOrNull() ?: 5_000L

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
                ctx.logger(result.message)
                ActionResult.Success
            } else {
                ActionResult.Failure(result.message)
            }
        } catch (ex: Exception) {
            ActionResult.Failure("Locale plugin failed: ${ex.message}", ex)
        }
    }
}
