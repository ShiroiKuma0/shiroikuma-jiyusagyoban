package com.opentasker.core.actions

import com.opentasker.core.band.BandSettings
import com.opentasker.core.band.BandSyncArgs
import com.opentasker.core.band.BandSyncEngine
import com.opentasker.core.band.BandSyncOutcome
import com.opentasker.core.band.BandSyncRequest
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.app.OpenTaskerApp_NoHilt
import java.time.LocalDateTime

/**
 * `Sync Band` — pull the Hume Band's stored history into the workspace.
 *
 * Exposed as an Action rather than a background service on purpose: this is an automation app, so
 * 白い熊's own Profiles decide when a sync happens. No hidden service, no surprise battery drain.
 *
 * Result semantics matter here and are deliberate:
 *  - data landed → Success
 *  - every stream terminated immediately → **Success**. "Nothing new" is not a failure.
 *  - some streams timed out, others banked data → Success, with the warning in the message
 *  - a sync already in flight → **Skip**, which TaskRunner already treats as non-failing
 *  - permission missing, adapter off, or everything errored → Failure
 */
class BandSyncAction : Action {
    override val id = "band.sync"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val parsed = BandSyncArgs.parse(args).getOrElse { return ActionResult.Failure(it.message ?: "bad arguments") }
        val db = OpenTaskerApp_NoHilt.db
        val prefix = parsed.prefix

        ctx.variables.set("${prefix}Phase", "starting")
        ctx.variables.set("${prefix}Pct", "0")
        ctx.variables.set("${prefix}Records", "0")

        val address = parsed.address ?: BandSettings.address(ctx.app)
        val lastSuccess = db.bandSyncDao().lastSuccessful()?.startedAt
        val from = BandSyncArgs.resolve(
            from = parsed.from,
            lastSuccessAtMillis = lastSuccess,
            overlapMinutes = BandSettings.overlapMinutes(ctx.app),
            now = LocalDateTime.now(),
        )

        val outcome = BandSyncEngine.sync(
            context = ctx.app,
            db = db,
            request = BandSyncRequest(
                address = address,
                from = from,
                streams = parsed.streams,
                timeoutSec = parsed.timeoutSec,
                backup = parsed.backup,
                backupDir = BandSettings.backupDir(ctx.app),
                source = "action",
            ),
            onProgress = { progress ->
                // Mirrored into the variable store as the sync runs, so a Scene bound to these names
                // animates with no polling — the SpeedTestAction precedent.
                ctx.variables.set("${prefix}Phase", progress.phase)
                ctx.variables.set("${prefix}Pct", progress.percent.toString())
                ctx.variables.set("${prefix}Records", progress.records.toString())
                ctx.variables.set("${prefix}Inserted", progress.inserted.toString())
                ctx.variables.set("${prefix}Stream", progress.stream)
            },
        )

        return when (outcome) {
            is BandSyncOutcome.Ok -> {
                val text = listOfNotNull(outcome.summary, outcome.warning).joinToString(" — ")
                parsed.store?.let { ctx.variables.set(it, text) }
                ctx.variables.set("${prefix}Summary", text)
                ctx.variables.set("${prefix}SyncId", outcome.syncId.toString())
                ctx.logger("Band sync: $text")
                ActionResult.Success
            }
            is BandSyncOutcome.Skipped -> {
                // Skip, not Failure: TaskRunner treats it as non-failing, and pressing Sync twice
                // while one is in flight is a normal thing to do. The reason goes to the variables
                // so the run is still explainable.
                parsed.store?.let { ctx.variables.set(it, outcome.reason) }
                ctx.variables.set("${prefix}Summary", outcome.reason)
                ActionResult.Skip
            }
            is BandSyncOutcome.Failed -> {
                parsed.store?.let { ctx.variables.set(it, outcome.reason) }
                ctx.variables.set("${prefix}Summary", outcome.reason)
                ActionResult.Failure(outcome.reason)
            }
        }
    }
}
