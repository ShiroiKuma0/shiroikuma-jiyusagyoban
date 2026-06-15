package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.widget.StyledWidgetProvider

/**
 * `Set Widget` — our Widget V2. Replaces the layout of every placed styled widget bound to a name and
 * re-renders it. `%vars` in the layout JSON are already expanded by the engine, so the task just hands
 * over the final layout (e.g. a clock task recomputing the kanji time every minute).
 */
class SetWidgetAction : Action {
    override val id = "widget.set"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["widget"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing widget name")
        val layout = args["layout"]?.trim().orEmpty()
        if (layout.isEmpty()) return ActionResult.Failure("missing layout")
        if (WidgetLayoutCodec.decode(layout) == null) return ActionResult.Failure("layout is not valid JSON")
        val updated = StyledWidgetProvider.updateByName(ctx.app, name, layout)
        if (updated == 0) return ActionResult.Failure("no widget named \"$name\" is on the home screen")
        ctx.logger("Set widget \"$name\" ($updated)")
        return ActionResult.Success
    }
}
