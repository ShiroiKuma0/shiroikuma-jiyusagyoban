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
 *
 * `at` takes a time (`15:30`, today), a date and a time (`2026-12-31 23:59`), a date on its own
 * (midnight of it), or `now`/blank for the phone's time. `pick` opens the window instead of setting
 * anything, which is the same job with the answer left open: a task carrying `at=15:00` answers one
 * question once, and looking at a face means asking a different one each time.
 */
class HuaweiTimeAction : DeclaredAction(ActionCatalog.require("huawei.time")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)

        // The window, rather than a time. Opening it is the whole action: what it then sends is
        // 白い熊's to choose, including the phone's own time, so this is also the reset.
        val pick = args["pick"]?.trim()?.lowercase().orEmpty()
        if (pick in TRUE_WORDS) {
            com.opentasker.ui.charts.huawei.HuaweiBandClockActivity.open(ctx.app)
            ctx.variables.set("${prefix}Summary", "opened the band clock")
            store?.let { ctx.variables.set(it, "opened the band clock") }
            return ActionResult.Success
        }

        val at = args["at"]?.trim()?.ifEmpty { null }
        val epoch = when {
            at == null || at.equals("now", ignoreCase = true) -> System.currentTimeMillis() / 1000
            else -> parseAt(at)
                ?: return fail(ctx, prefix, store,
                    "at must be HH:MM, YYYY-MM-DD, YYYY-MM-DD HH:MM or 'now', not '$at'")
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

    /**
     * `HH:MM[:SS]`, `YYYY-MM-DD`, `YYYY-MM-DD HH:MM[:SS]` (or with a `T`), in the phone's own zone.
     *
     * The two halves are told apart by what is in them rather than by position — a `-` makes a date
     * and a `:` a time — so either may be left out. A date with no time means midnight of it, and a
     * time with no date means today, which is what every earlier `at=15:00` meant and still does.
     */
    private fun parseAt(spec: String): Long? {
        val parts = spec.split('T', 't', ' ').filter { it.isNotBlank() }
        val date = parts.firstOrNull { it.contains('-') }
        val time = parts.firstOrNull { it.contains(':') }
        if (date == null && time == null) return null
        val cal = Calendar.getInstance()
        if (date != null) {
            val ymd = date.split('-').map { it.trim().toIntOrNull() ?: return null }
            if (ymd.size != 3) return null
            cal.set(Calendar.YEAR, ymd[0])
            cal.set(Calendar.MONTH, ymd[1] - 1)
            cal.set(Calendar.DAY_OF_MONTH, ymd[2])
        }
        val hms = time?.split(':')?.map { it.trim().toIntOrNull() ?: return null } ?: listOf(0, 0)
        if (hms.size < 2) return null
        cal.set(Calendar.HOUR_OF_DAY, hms[0])
        cal.set(Calendar.MINUTE, hms[1])
        cal.set(Calendar.SECOND, hms.getOrElse(2) { 0 })
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    private fun fail(ctx: ActionContext, prefix: String, store: String?, why: String): ActionResult {
        ctx.variables.set("${prefix}Summary", why)
        store?.let { ctx.variables.set(it, why) }
        return ActionResult.Failure(why)
    }

    private companion object {
        val TRUE_WORDS = setOf("1", "true", "yes", "on")
    }
}
