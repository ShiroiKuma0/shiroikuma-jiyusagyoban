package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

class TaskerUnsupportedAction : DeclaredAction(ActionCatalog.require("tasker.unsupported")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val code = args["taskerCode"].orEmpty().ifBlank { "unknown" }
        return ActionResult.Failure("Unsupported imported Tasker action: $code")
    }
}

class MacroDroidUnsupportedAction : DeclaredAction(ActionCatalog.require("macrodroid.unsupported")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val classType = args["classType"].orEmpty().ifBlank { "unknown" }
        return ActionResult.Failure("Unsupported imported MacroDroid action: $classType")
    }
}
