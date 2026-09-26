package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.AlmanacWatch

/**
 * Keep SEARCHING for a newer almanac in the background, and say so the moment one is published.
 *
 * It searches — on a cadence, by itself. The first name this wore was 「新しい概略暦を待つ」, "wait
 * for a newer almanac", which reads as sit-and-do-nothing and is exactly what it is not
 * (白い熊, 2026-09-25). Every string it writes now says how often it goes looking, and every
 * notification it raises carries the way to call it off.
 *
 * The panel offers this **instead of Close** when a set has gone out on a stale almanac. Nothing
 * else in this app asks a person to keep checking a web server, and this is the one case where the
 * remedy is simply to look again later: GSSC publishes daily, a build at half past seven can
 * easily precede the day's file, and on 2026-09-25 the almanac 白い熊 needed existed by the time they walked.
 *
 * `stop` cancels it; starting again replaces rather than doubles. See [AlmanacWatch] for what one
 * check costs and why it tells rather than rebuilds.
 */
class HuaweiAlmanacWatchAction : Action {
    override val id = "huawei.almanacwatch"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val stop = args["stop"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
        if (stop) {
            AlmanacWatch.stop(ctx.app)
            ctx.logger("Almanac watch stopped")
            return ActionResult.Success
        }
        // The vintage we are trying to beat. Blank is a refusal rather than a guess: watching for
        // "something newer than nothing" would fire on the first check and mean nothing.
        val have = ctx.variables.expand(args["have"].orEmpty()).trim()
        if (have.isEmpty()) {
            return ActionResult.Failure(
                "no `have` date — the watch needs the almanac vintage it is trying to beat " +
                    "(%HUAWEI_PgnssAlmanacHave, written by the build)",
            )
        }
        val task = ctx.variables.expand(args["task"].orEmpty()).trim()
            .ifEmpty { "衛星 生成 -- [727]" }
        AlmanacWatch.start(ctx.app, have, task)
        ctx.logger(
            "Searching for an almanac newer than $have — the publisher is checked every " +
                "${AlmanacWatch.EVERY_MINUTES} min in the background, for up to " +
                "${AlmanacWatch.GIVE_UP_HOURS} h; then $task",
        )
        return ActionResult.Success
    }
}
