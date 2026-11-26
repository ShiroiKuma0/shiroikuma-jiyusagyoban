package com.sysadmindoc.ruledeck.core.actions

import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult

class MacroDroidUnsupportedAction : DeclaredAction(ActionCatalog.require("macrodroid.unsupported")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val classType = args["classType"].orEmpty().ifBlank { "unknown" }
        return ActionResult.Failure("Unsupported imported MacroDroid action: $classType")
    }
}
