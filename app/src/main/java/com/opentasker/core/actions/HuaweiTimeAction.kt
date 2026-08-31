package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.DeclaredAction
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import java.util.Calendar

/**
 * Set the band's clock, for looking at what a face does at a particular time.
 *
 * A watch face can only be checked at the hour it renders, and some hours come round slowly. This
 * moves the band's clock so the interesting one can be looked at now.
 *
 * **It is not destructive, and could hardly be.** `announceCompanion` pushes the phone's time on
 * every session, so the next connection to the band restores the real time by itself; this action
 * with no arguments does the same thing deliberately.
 *
 * What it does cost: the band timestamps its own records from this clock, so a large jump — one
 * that crosses midnight in particular — can disturb a day's counters. Prefer the smallest move that
 * answers the question.
 */
class HuaweiTimeAction : DeclaredAction(ActionCatalog.require("huawei.time")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)

        val at = args["at"]?.trim()?.ifEmpty { null }
        val epoch = when {
            at == null || at.equals("now", ignoreCase = true) -> System.currentTimeMillis() / 1000
            else -> {
                val parts = at.split(":").mapNotNull { it.trim().toIntOrNull() }
                if (parts.size < 2) {
                    return fail(ctx, prefix, store, "at must be HH:MM or 'now', not '$at'")
                }
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, parts[0])
                    set(Calendar.MINUTE, parts[1])
                    set(Calendar.SECOND, parts.getOrElse(2) { 0 })
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis / 1000
            }
        }

        val result = HuaweiSyncRunner.setBandTime(ctx.app, address, epoch)
        return result.fold(
            onSuccess = { when_ ->
                val text = "band clock set to $when_"
                ctx.variables.set("${prefix}Summary", text)
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei band time: $text")
                ActionResult.Success
            },
            onFailure = { fail(ctx, prefix, store, it.message ?: it::class.java.simpleName) },
        )
    }

    private fun fail(ctx: ActionContext, prefix: String, store: String?, why: String): ActionResult {
        ctx.variables.set("${prefix}Summary", why)
        store?.let { ctx.variables.set(it, why) }
        return ActionResult.Failure(why)
    }
}
