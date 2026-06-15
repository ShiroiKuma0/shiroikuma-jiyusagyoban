package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.widget.StyledWidgetProvider
import com.opentasker.widget.TemplateStore

/**
 * `Set Widget` — our Widget V2. Replaces the layout of every placed styled widget bound to a name and
 * re-renders it. The layout comes from either an inline `layout` arg (already `%var`-expanded by the
 * engine) or a named `template` from the Widget Templates library — templates hold `%vars` raw, so we
 * re-expand them here at set time, which is what lets "edit the template once, every widget updates".
 */
class SetWidgetAction : Action {
    override val id = "widget.set"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["widget"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing widget name")
        val templateName = args["template"]?.trim().orEmpty()
        val layout = if (templateName.isNotEmpty()) {
            val raw = TemplateStore.get(templateName)
                ?: return ActionResult.Failure("no widget template named \"$templateName\"")
            ctx.variables.expand(raw)
        } else {
            args["layout"]?.trim().orEmpty()
        }
        if (layout.isEmpty()) return ActionResult.Failure("missing layout (set a template or a layout)")
        if (WidgetLayoutCodec.decode(layout) == null) return ActionResult.Failure("layout is not valid JSON")
        val updated = StyledWidgetProvider.updateByName(ctx.app, name, layout)
        if (updated == 0) return ActionResult.Failure("no widget named \"$name\" is on the home screen")
        ctx.logger("Set widget \"$name\" ($updated)")
        return ActionResult.Success
    }
}
