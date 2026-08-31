package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Stop flag for a running speed test.
 *
 * A leg is a tight read/write loop inside a 10-second deadline, so "cancel" cannot be a scene change —
 * the transfer has to notice and abort mid-stream. The flag is process-global and checked by every
 * stream on every buffer, which is the cheapest thing that reacts immediately.
 */
object SpeedTestCancel {
    @Volatile
    private var requested = false

    fun request() { requested = true }
    fun clear() { requested = false }
    val isRequested: Boolean get() = requested
}

/**
 * `Cancel speed test` — abort the running test at once. The task that owns the run is still
 * responsible for putting WiFi and the data SIM back; this only stops the transfer.
 */
class CancelSpeedTestAction : Action {
    override val id = "net.speedtest.cancel"
    override val category = ActionCategory.NET

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        SpeedTestCancel.request()
        ctx.variables.set("SPD_Phase", "cancelled")
        // Nothing is being measured any more, so the live overlay must stop claiming a direction.
        ctx.variables.set("SPD_Arrow", "")
        ctx.logger("Speed test cancel requested")
        return ActionResult.Success
    }
}
