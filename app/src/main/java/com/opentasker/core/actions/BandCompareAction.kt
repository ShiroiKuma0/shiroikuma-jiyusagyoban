package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.ui.charts.compare.BandCompareActivity

/**
 * Open 「バンド比較」.
 *
 * Belongs to neither band, which is why it is `band.compare` rather than something prefixed with a
 * device: it is the screen that decides whether the Hume band can be retired, and it would be odd
 * for that to live inside one of the two things it judges.
 */
class BandCompareAction : Action {
    override val id = "band.compare"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val span = args["span_minutes"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        BandCompareActivity.open(ctx.app, span)
        args["store"]?.trim()?.ifEmpty { null }?.let {
            ctx.variables.set(it, "opened the band comparison")
        }
        return ActionResult.Success
    }
}
