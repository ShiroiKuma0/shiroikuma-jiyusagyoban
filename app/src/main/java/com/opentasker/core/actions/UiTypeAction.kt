package com.opentasker.core.actions

import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * `Type into field` — put text into a text field of another app, the partner of [UiClickAction].
 *
 * Built for 白い熊 応用管理's 起動マネージャー button (2026-11-07): EMUI's App-launch list has a 検索
 * box, and typing the app's name there jumps to its row instead of scrolling a list of hundreds.
 *
 * Through the accessibility service's ACTION_SET_TEXT rather than `input text`, which cannot type
 * Japanese — and many of the names it is given are Japanese.
 */
class UiTypeAction : Action {
    override val id = "ui.type"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"].orEmpty()
        if (text.isBlank()) return ActionResult.Failure("no text given")
        val pkg = args["package"]?.trim()?.ifEmpty { null }
        val timeout = args["timeout"]?.trim()?.toLongOrNull()?.coerceIn(100, 30_000) ?: 3_000

        if (!ShiroiKumaAccessibilityService.isConnected) {
            return ActionResult.Failure("the accessibility service is not running — typing into a field needs it")
        }
        val field = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ShiroiKumaAccessibilityService.typeIntoField(text, pkg, timeout)
        }
        return if (field != null) {
            ctx.logger("Type into field: \"$text\" into $field")
            ActionResult.Success
        } else {
            val why = "no text field" + (pkg?.let { " in $it" } ?: "") + " within ${timeout}ms"
            ctx.logger("Type into field failed: $why")
            ActionResult.Failure(why)
        }
    }
}
