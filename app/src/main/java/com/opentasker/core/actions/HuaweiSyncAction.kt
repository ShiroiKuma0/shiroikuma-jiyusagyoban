package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncArgs
import com.opentasker.core.huawei.HuaweiSyncRunner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `Sync Huawei Band` — pull the HUAWEI Band 11 Pro's stored history into its own tables.
 *
 * The Hume band's `band.sync` is the model, and the result semantics are copied deliberately:
 * data landed → Success; nothing new → **Success**, because "nothing new" is not a failure; a sync
 * already in flight → **Skip**, which TaskRunner treats as non-failing; anything else → Failure.
 *
 * What is NOT copied is the buffer-pressure readout. `band.sync` publishes `HeadroomHours` and
 * `PressurePct`, both of which divide by a measured buffer depth. This band's depth has never been
 * measured, so those variables would be fabrications. `ObservedDepthHours` is published instead and
 * is a **floor** — the deepest the band has ever actually answered from — and the auto-sync task
 * warns on age rather than on pressure until the depth probe has a week of rows behind it.
 */
class HuaweiSyncAction : Action {
    override val id = "huawei.sync"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val db = OpenTaskerApp_NoHilt.db
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }

        ctx.variables.set("${prefix}Phase", "starting")
        ctx.variables.set("${prefix}Pct", "0")
        ctx.variables.set("${prefix}Records", "0")

        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        val timeoutSec = args["timeout_sec"]?.trim()?.toIntOrNull() ?: HuaweiSettings.timeoutSec(ctx.app)
        val maxRecords = args["max_records"]?.trim()?.toIntOrNull() ?: 4096

        val status = runCatching { HuaweiSyncRunner.status(db) }.getOrNull()
        val windows = HuaweiSyncArgs.resolve(
            from = HuaweiSyncArgs.parseFrom(args["from"]),
            lastSuccessAtSeconds = status?.lastSuccessAtMillis?.let { it / 1000 },
            overlapMinutes = HuaweiSettings.overlapMinutes(ctx.app),
            nowSeconds = System.currentTimeMillis() / 1000,
        )

        val outcome = HuaweiSyncRunner.sync(
            context = ctx.app,
            db = db,
            request = HuaweiSyncRunner.Request(
                address = address,
                windows = windows,
                timeoutSec = timeoutSec,
                maxRecords = maxRecords,
                source = "action",
            ),
        )

        // From the DATABASE and on every path — the failed case is exactly the one a Profile needs
        // to be able to reason about.
        publishStatus(ctx, prefix, outcome)

        return when (outcome) {
            is HuaweiSyncRunner.Outcome.Ok -> {
                val text = listOfNotNull(outcome.summary, outcome.warning).joinToString(" — ")
                store?.let { ctx.variables.set(it, text) }
                ctx.variables.set("${prefix}Summary", text)
                ctx.variables.set("${prefix}SyncId", outcome.syncId.toString())
                ctx.logger("Huawei sync: $text")
                ActionResult.Success
            }
            is HuaweiSyncRunner.Outcome.Skipped -> {
                store?.let { ctx.variables.set(it, outcome.reason) }
                ctx.variables.set("${prefix}Summary", outcome.reason)
                ActionResult.Skip
            }
            is HuaweiSyncRunner.Outcome.Failed -> {
                store?.let { ctx.variables.set(it, outcome.reason) }
                ctx.variables.set("${prefix}Summary", outcome.reason)
                ActionResult.Failure(outcome.reason)
            }
        }
    }

    private suspend fun publishStatus(
        ctx: ActionContext,
        prefix: String,
        outcome: HuaweiSyncRunner.Outcome,
    ) = runCatching {
        val now = System.currentTimeMillis()
        val status = HuaweiSyncRunner.status(OpenTaskerApp_NoHilt.db)
        ctx.variables.set("${prefix}Ok", (outcome is HuaweiSyncRunner.Outcome.Ok).toString())
        ctx.variables.set("${prefix}AgeHours", status.ageHours(now)?.let { "%.1f".format(it) } ?: "")
        ctx.variables.set("${prefix}LastSuccess", status.lastSuccessAtMillis?.let(::formatMillis) ?: "")
        ctx.variables.set("${prefix}BatteryPct", status.batteryPct?.toString() ?: "")
        ctx.variables.set("${prefix}BatteryAgeHours", status.batteryAgeHours(now)?.let { "%.1f".format(it) } ?: "")
        ctx.variables.set("${prefix}Firmware", status.firmware ?: "")
        // Blank, not "0", when nothing has measured it: "not measured" and "measured as nothing"
        // are different claims and a task condition must be able to tell them apart.
        ctx.variables.set(
            "${prefix}ObservedDepthHours",
            status.observedDepthHours?.let { "%.1f".format(it) } ?: "",
        )
        ctx.variables.set("${prefix}MissingCount", status.lastMissingCount.toString())
        ctx.variables.set("${prefix}SyncCount", status.syncCount.toString())
    }.getOrElse {
        // A status readout must never turn a good sync into a failed task.
        ctx.logger("Huawei sync: could not publish status — ${it.message}")
    }

    private fun formatMillis(millis: Long): String = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}
