package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.ui.charts.BandChartsActivity

/**
 * `Show Band Charts` — open 「健康」 in its own fullscreen window.
 *
 * This exists so the charts are reachable the way 白い熊 actually wants to reach them (2026-08-03):
 * a task, and therefore a launcher shortcut through the existing CREATE_SHORTCUT picker, opening a
 * real window — rather than a tab buried in an app about automation.
 *
 * It starts a window and returns; it does not wait for the window to close. A task that opens the
 * charts and then flashes something would flash immediately, which is the correct behaviour for
 * "show me this" and worth knowing.
 */
class BandChartsAction : Action {
    override val id = "band.charts"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val metric = args["metric"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (metric != null && metric !in KNOWN_METRICS) {
            return ActionResult.Failure("unknown metric '$metric' — one of ${KNOWN_METRICS.joinToString(", ")}")
        }

        val spanRaw = args["span_minutes"]?.trim()?.takeIf { it.isNotEmpty() }
        val spanMinutes = if (spanRaw == null) {
            null
        } else {
            spanRaw.toIntOrNull()?.takeIf { it > 0 }
                ?: return ActionResult.Failure("span_minutes must be a positive whole number of minutes")
        }

        return try {
            BandChartsActivity.open(ctx.app, metric, spanMinutes)
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure(e.message ?: "could not open the 健康 window")
        }
    }

    private companion object {
        /** The line metrics [com.opentasker.ui.charts.MetricSpecs] can draw. */
        val KNOWN_METRICS = setOf("hr", "hrv", "spo2", "temp", "stress")
    }
}
