package com.sysadmindoc.ruledeck.core.actions

import android.content.Intent
import com.sysadmindoc.ruledeck.core.engine.Action
import com.sysadmindoc.ruledeck.core.engine.ActionCategory
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.share.ShareAppsActivity

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
            .fold({ ActionResult.Success }, { ActionResult.Failure("could not open Share tiles: ${it.message}") })
    }
}
