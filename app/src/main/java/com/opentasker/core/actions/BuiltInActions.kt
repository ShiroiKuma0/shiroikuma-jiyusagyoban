package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Notification action — display a toast or heads-up notification.
 *
 * Args:
 *   - "title": notification title
 *   - "text": notification body
 *   - "duration": "short" or "long" (Toast duration only)
 */
class NotifyAction : Action {
    override val id = "notify.show"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val title = args["title"] ?: "Notification"
        val text = args["text"] ?: ""
        ctx.logger("Notify: $title | $text")
        // TODO: Implement Toast/NotificationCompat
        return ActionResult.Success
    }
}

/**
 * Variable set action.
 *
 * Args:
 *   - "name": variable name
 *   - "value": new value (supports %expansion)
 */
class SetVariableAction : Action {
    override val id = "var.set"
    override val category = ActionCategory.VARIABLE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"] ?: return ActionResult.Failure("missing name")
        val value = args["value"] ?: ""
        ctx.variables.set(name, value)
        ctx.logger("Set \$$name = $value")
        return ActionResult.Success
    }
}

/**
 * Say (text-to-speech) action.
 *
 * Args:
 *   - "text": text to speak
 */
class SayAction : Action {
    override val id = "tts.speak"
    override val category = ActionCategory.NOTIFICATION

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"] ?: ""
        ctx.logger("TTS: $text")
        // TODO: Use TextToSpeech engine
        return ActionResult.Success
    }
}

/**
 * Wait action — pause task execution.
 *
 * Args:
 *   - "millis": milliseconds to wait
 */
class WaitAction : Action {
    override val id = "flow.wait"
    override val category = ActionCategory.FLOW

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val ms = args["millis"]?.toLongOrNull() ?: 0L
        if (ms > 0) {
            ctx.logger("Wait ${ms}ms")
            kotlinx.coroutines.delay(ms)
        }
        return ActionResult.Success
    }
}

/**
 * Intent launch action.
 *
 * Args:
 *   - "package": target package
 *   - "action": intent action (optional, defaults to MAIN)
 *   - "category": intent category (optional)
 */
class LaunchIntentAction : Action {
    override val id = "intent.launch"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"] ?: return ActionResult.Failure("missing package")
        ctx.logger("Intent: $pkg")
        // TODO: Resolve and launch intent
        return ActionResult.Success
    }
}
