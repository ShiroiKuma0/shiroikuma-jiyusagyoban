package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import java.io.File

/**
 * Fetch the band's recorded walks, and their GPS tracks.
 *
 * A workout is not part of the ordinary sync. The per-minute history is a grid of measurements; a
 * walk is an object the band numbered, with a summary and — if it saw satellites — a track stored as
 * a file. So this is its own action rather than a flag on `huawei.sync`: it costs a session, it can
 * take a while for a long route, and 白い熊 should be able to ask for it when a walk exists rather
 * than have every four-hourly sync go looking for one.
 *
 * The raw `.bin` is kept beside the `.gpx`, always. Two things about the track format are unproven —
 * the earth radius that turns its metres back into degrees, and whether its datum is WGS-84 or the
 * offset Chinese one — and both are settled by decoding the same file again once a real walk says
 * which is right. A track thrown away after one pass cannot be re-decoded.
 */
class HuaweiWorkoutsAction : Action {
    override val id = "huawei.workouts"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        val days = args["days"]?.trim()?.toIntOrNull()?.coerceIn(1, 30) ?: 7
        val outDir = args["out"]?.trim()?.ifEmpty { null } ?: "/sdcard/tmp"

        val now = System.currentTimeMillis() / 1000
        val from = now - days * 86_400L

        val result = HuaweiSyncRunner.fetchWorkouts(ctx.app, address, from, now, File(outDir))
        return result.fold(
            onSuccess = { walks ->
                ctx.variables.set("${prefix}Workouts", walks.size.toString())
                ctx.variables.set("${prefix}Tracks", walks.count { it.gpxPath != null }.toString())
                // The newest track's path, because a task that wants to hand one to a map wants the
                // one just walked, and reaching into a list is more machinery than that deserves.
                walks.lastOrNull { it.gpxPath != null }?.let {
                    ctx.variables.set("${prefix}Gpx", it.gpxPath!!)
                }
                val text = if (walks.isEmpty()) {
                    "no workouts in the last $days days"
                } else {
                    walks.joinToString(" · ") { w ->
                        val km = w.summary.distanceMetres?.let { "%.2f km".format(it / 1000.0) } ?: "—"
                        val pts = if (w.trackPoints > 0) "${w.trackPoints} points" else (w.note ?: "no track")
                        "${w.summary.kind} $km ($pts)"
                    }
                }
                ctx.variables.set("${prefix}Summary", text)
                store?.let { ctx.variables.set(it, text) }
                ctx.logger("Huawei workouts: $text")
                ActionResult.Success
            },
            onFailure = {
                val why = it.message ?: it::class.java.simpleName
                ctx.variables.set("${prefix}Summary", why)
                store?.let { k -> ctx.variables.set(k, why) }
                ActionResult.Failure(why)
            },
        )
    }
}
