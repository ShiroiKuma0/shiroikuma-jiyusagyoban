package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.huawei.HuaweiChartsActivity
import com.opentasker.ui.charts.huawei.HuaweiMetricSpecs

/**
 * `Show Huawei Band Charts` — open 健康（Huawei） in its own fullscreen window.
 *
 * A separate window from the Hume band's, not a mode of it: two entry points, two launcher
 * shortcuts, and a demotion later that is a deletion rather than surgery.
 *
 * It ships **no `span_minutes` field**, unlike `band.charts`. That argument writes an extra nothing
 * ever reads — the initial span comes from the chart style — so a picker control that does nothing
 * would be worse than no control at all.
 */
class HuaweiChartsAction : Action {
    override val id = "huawei.charts"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val requested = args["metric"]?.trim().orEmpty()
        val metric = when {
            requested.isEmpty() -> null
            HuaweiMetricSpecs.byKey(requested) != null -> HuaweiMetricSpecs.byKey(requested)!!.key
            else -> return ActionResult.Failure(
                "unknown metric '$requested' — one of: " +
                    HuaweiMetricSpecs.ALL.joinToString(", ") { it.key.removePrefix("hw:") },
            )
        }
        // Persisted rather than merely passed: the window can be resumed by the system long after
        // the task that opened it has finished, and it must come back up in the same language.
        args["lang"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            HuaweiSettings.setLanguage(ctx.app, BandLanguage.parse(it).tag)
        }
        HuaweiChartsActivity.open(ctx.app, metric)
        return ActionResult.Success
    }
}
