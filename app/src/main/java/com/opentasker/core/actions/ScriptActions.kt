package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.scripting.TermuxScriptBackend

class TermuxScriptAction : Action {
    override val id = TermuxScriptBackend.ACTION_ID
    override val category = ActionCategory.PLUGIN

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val executable = args["executable"].orEmpty().ifBlank { "unspecified" }
        ctx.logger("Termux script requested: $executable")
        return ActionResult.Failure(
            "Termux script execution is not implemented. Configure Termux/Termux:Tasker readiness first; dispatch, permission handling, output capture, and run-log auditing remain blocked.",
        )
    }
}
