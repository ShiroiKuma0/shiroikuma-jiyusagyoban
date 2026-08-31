package com.opentasker.core.actions

import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * `Tap by label` — press a control in another app by the words on it.
 *
 * Built for the physical-key camera bindings, where the double press must land on the photo tab and
 * the triple press on the video tab. Both open the SAME activity — the secure camera is the only
 * one the keyguard lets through — so the tab has to be chosen afterwards rather than by the intent.
 *
 * **By label, never by coordinate.** The Mate XT reports a different geometry folded and unfolded,
 * so a remembered x/y lands somewhere else on the other panel. A caption is the same caption in
 * both states.
 *
 * **Several candidates**, because the label is in whatever language the phone is set to: the
 * camera's tabs read 写真 / ビデオ here and Photo / Video after a locale switch, and a binding
 * should survive that.
 *
 * Needs the accessibility service — it is the only way to read another app's nodes, and the only
 * way that works over the keyguard, where a screenshot comes back blank because the camera preview
 * is a secure surface.
 */
class UiClickAction : Action {
    override val id = "ui.click"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"]?.trim().orEmpty()
        if (text.isEmpty()) return ActionResult.Failure("no text given")
        val candidates = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val pkg = args["package"]?.trim()?.ifEmpty { null }
        val timeout = args["timeout"]?.trim()?.toLongOrNull()?.coerceIn(100, 30_000) ?: 3_000
        val store = args["store"]?.trim()?.ifEmpty { null }

        if (!ShiroiKumaAccessibilityService.isConnected) {
            val why = "the accessibility service is not running — tapping by label needs it"
            store?.let { ctx.variables.set(it, why) }
            return ActionResult.Failure(why)
        }

        val hit = withContextIO { ShiroiKumaAccessibilityService.clickByLabel(candidates, pkg, timeout) }
        store?.let { ctx.variables.set(it, hit ?: "") }
        return if (hit != null) {
            ctx.logger("Tap by label: clicked \"$hit\"")
            ActionResult.Success
        } else {
            // Naming what was looked for, because "not found" on its own sends you hunting in the
            // wrong app: nine times in ten the control is there and the LABEL has changed.
            val why = "no clickable control labelled ${candidates.joinToString(" / ")}" +
                (pkg?.let { " in $it" } ?: "") + " within ${timeout}ms"
            ctx.logger("Tap by label failed: $why")
            ActionResult.Failure(why)
        }
    }

    private suspend fun <T> withContextIO(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
