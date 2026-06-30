package com.opentasker.core.actions

import android.content.Intent
import com.opentasker.core.dialog.DialogActivity
import com.opentasker.core.dialog.DialogBridge
import com.opentasker.core.dialog.DialogOutcome
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// Wave 4 — dialogs. A suspended action launches the transparent DialogActivity and awaits the
// user's answer via DialogBridge. From a background trigger this needs "display over other apps".
// ---------------------------------------------------------------------------------------------

internal suspend fun showDialog(
    ctx: ActionContext,
    timeoutSec: Int?,
    configure: Intent.() -> Unit,
): DialogOutcome {
    val id = UUID.randomUUID().toString()
    val deferred = DialogBridge.register(id)
    val intent = Intent(ctx.app, DialogActivity::class.java).apply {
        putExtra(DialogActivity.EXTRA_ID, id)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        configure()
    }
    try {
        ctx.app.startActivity(intent)
    } catch (e: Exception) {
        DialogBridge.cancel(id)
        return DialogOutcome.Cancelled
    }
    return if (timeoutSec != null && timeoutSec > 0) {
        withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
            ?: run { DialogBridge.cancel(id); DialogOutcome.Cancelled }
    } else {
        deferred.await()
    }
}

/** `Input Dialog` (Tasker 360) — prompt for text; store the result. */
class InputDialogAction : Action {
    override val id = "dialog.input"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim()?.takeIf { it.isNotEmpty() } ?: "input"
        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_INPUT)
            putExtra(DialogActivity.EXTRA_TITLE, args["title"].orEmpty())
            putExtra(DialogActivity.EXTRA_TEXT, args["text"].orEmpty())
            putExtra(DialogActivity.EXTRA_DEFAULT, args["default"].orEmpty())
            putExtra(DialogActivity.EXTRA_INPUT_TYPE, args["input_type"].orEmpty())
        }
        when (outcome) {
            is DialogOutcome.Confirmed -> ctx.variables.set(store, outcome.value)
            DialogOutcome.Cancelled -> ctx.variables.set(store, "")
        }
        ctx.logger("Input dialog → %$store")
        return ActionResult.Success
    }
}

/** `List Dialog` (Tasker 378) — pick one item; store its text and index. */
class ListDialogAction : Action {
    override val id = "dialog.list"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim()?.takeIf { it.isNotEmpty() } ?: "selected"
        val separator = args["separator"]?.takeUnless { it.isNullOrEmpty() } ?: ","
        val items = args["items"].orEmpty().split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return ActionResult.Failure("no items to show")
        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_LIST)
            putExtra(DialogActivity.EXTRA_TITLE, args["title"].orEmpty())
            putExtra(DialogActivity.EXTRA_ITEMS, items.toTypedArray())
        }
        when (outcome) {
            is DialogOutcome.Confirmed -> {
                ctx.variables.set(store, outcome.value)
                args["store_index"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { ctx.variables.set(it, outcome.index.toString()) }
            }
            DialogOutcome.Cancelled -> ctx.variables.set(store, "")
        }
        ctx.logger("List dialog → %$store")
        return ActionResult.Success
    }
}

/** `Text Dialog` (Tasker 377) — show text with OK/Cancel; store which was pressed. */
class TextDialogAction : Action {
    override val id = "dialog.text"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val outcome = showDialog(ctx, args["timeout"]?.toIntOrNull()) {
            putExtra(DialogActivity.EXTRA_TYPE, DialogActivity.TYPE_TEXT)
            putExtra(DialogActivity.EXTRA_TITLE, args["title"].orEmpty())
            putExtra(DialogActivity.EXTRA_TEXT, args["text"].orEmpty())
            putExtra(DialogActivity.EXTRA_OK, args["ok"].orEmpty())
            putExtra(DialogActivity.EXTRA_CANCEL, args["cancel"].orEmpty())
        }
        args["store"]?.trim()?.takeIf { it.isNotEmpty() }?.let { store ->
            ctx.variables.set(store, if (outcome is DialogOutcome.Confirmed) "true" else "false")
        }
        ctx.logger("Text dialog: ${if (outcome is DialogOutcome.Confirmed) "OK" else "Cancel"}")
        return ActionResult.Success
    }
}
