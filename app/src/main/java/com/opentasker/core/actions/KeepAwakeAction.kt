package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.RunAwake
import kotlinx.coroutines.currentCoroutineContext

/**
 * `Keep Awake` — this run finishes in real time: the CPU stays awake until the run ends, or for
 * the seconds given, whichever comes first.
 *
 * Without it a run started by an alarm or a message keeps the CPU only for an instant, and on an
 * unplugged phone with its screen off every `Wait`, every retry pause and every time limit after
 * that is stretched by however long the phone sleeps. See [RunAwake].
 *
 * Args:
 *   - "seconds": how long at most (default 300, at most 3600); asking again replaces the limit, and
 *                `0` lets the phone sleep again at once
 *
 * The lock belongs to the outermost run: a task called with `Run Task` shares it, and it is given
 * back when that outermost run ends — finished, stopped or failed.
 */
class KeepAwakeAction : Action {
    override val id = "power.keep_awake"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val raw = args["seconds"]?.trim().orEmpty()
        val seconds = if (raw.isEmpty()) DEFAULT_SECONDS else raw.toIntOrNull()
            ?: return ActionResult.Failure("seconds is not a number: $raw")
        if (seconds < 0) return ActionResult.Failure("seconds cannot be negative: $seconds")
        val holder = currentCoroutineContext()[RunAwake]
            ?: return ActionResult.Failure("this step is not inside a task run")
        if (!holder.hold(ctx.app, seconds)) return ActionResult.Failure("the phone refused the wakelock")
        ctx.logger(if (seconds == 0) "Keep awake: released" else "Keep awake: ${seconds.coerceAtMost(RunAwake.MAX_SECONDS)} s, or until this run ends")
        return ActionResult.Success
    }

    private companion object {
        const val DEFAULT_SECONDS = 300
    }
}
