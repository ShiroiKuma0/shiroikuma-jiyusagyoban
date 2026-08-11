package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.power.ShellResult
import com.opentasker.core.power.ShizukuShellRunner

internal fun ActionContext.runShizukuAction(
    actionId: String,
    label: String,
    variantIndex: Int = 0,
): ActionResult = when (val result = ShizukuShellRunner.execute(actionId, variantIndex)) {
    is ShellResult.Success -> {
        logger("$label completed")
        ActionResult.Success
    }
    is ShellResult.Failure -> ActionResult.Failure(result.reason)
}

internal fun ActionContext.runShizukuScreenshot(path: String): ActionResult =
    when (val result = ShizukuShellRunner.captureScreenshot(path)) {
        is ShellResult.Success -> {
            logger("Screenshot saved: ${result.output}")
            ActionResult.Success
        }
        is ShellResult.Failure -> ActionResult.Failure(result.reason)
    }
