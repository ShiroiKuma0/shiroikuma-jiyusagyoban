package com.opentasker.core.actions

import android.content.Intent
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.share.ShareAppsActivity

/**
 * Opens the "Share apps" screen — the per-target share-relay generator (pick app → edit name → pick
 * icon → Generate). Modeled on the "Make Launcher Tasks" action: it just launches the screen, so it
 * can be dropped into a task and run like any other generative action.
 */
class GenerateShareRelaysAction : Action {
    override val id = "share.relays"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val intent = Intent(ctx.app, ShareAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.app.startActivity(intent) }
            .fold({ ActionResult.Success }, { ActionResult.Failure("could not open Share apps: ${it.message}") })
    }
}
