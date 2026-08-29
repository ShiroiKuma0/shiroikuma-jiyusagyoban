package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.huawei.HuaweiBoardActivity

/**
 * `Huawei Band board` — open 健康 -- [727], the window everything else is reached from.
 *
 * Its own action rather than a flag on `huawei.charts`: the board is not a view of the data, it is
 * the way in to sixteen tasks, and one of the cards opens the charts. A mode of the thing it launches
 * is the wrong shape.
 */
class HuaweiBoardAction : Action {
    override val id = "huawei.board"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        // Persisted rather than merely passed, exactly as the charts window does it: the board can be
        // resumed by the system long after the task that opened it has gone, and it must come back up
        // in the language it was left in.
        args["lang"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            HuaweiSettings.setLanguage(ctx.app, BandLanguage.parse(it).tag)
        }
        HuaweiBoardActivity.open(ctx.app)
        return ActionResult.Success
    }
}
