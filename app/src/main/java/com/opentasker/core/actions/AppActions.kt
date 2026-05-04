package com.opentasker.core.actions

import android.content.Intent
import android.net.Uri
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Launch an application.
 *
 * Args:
 *   - "package": package name (e.g., "com.spotify.music")
 */
class LaunchAppAction : Action {
    override val id = "app.launch"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"] ?: return ActionResult.Failure("missing package")
        return try {
            val intent = ctx.app.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ActionResult.Failure("app not found: $pkg")
            ctx.app.startActivity(intent)
            ctx.logger("Launch: $pkg")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("launch failed: ${e.message}")
        }
    }
}

/**
 * Kill (force-stop) an application.
 *
 * Args:
 *   - "package": package name
 */
class KillAppAction : Action {
    override val id = "app.kill"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"] ?: return ActionResult.Failure("missing package")
        ctx.logger("Kill: $pkg")
        // TODO: Implement via ActivityManager.killBackgroundProcesses
        return ActionResult.Success
    }
}

/**
 * Go to home screen (dismiss notifications, etc.).
 */
class GoHomeAction : Action {
    override val id = "home.go"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.app.startActivity(intent)
        ctx.logger("Go to home")
        return ActionResult.Success
    }
}

/**
 * Open a URL in the browser.
 *
 * Args:
 *   - "url": URL to open
 */
class OpenUrlAction : Action {
    override val id = "browser.open"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            ctx.app.startActivity(intent)
            ctx.logger("Open URL: $url")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("open failed: ${e.message}")
        }
    }
}

/**
 * Send an SMS.
 *
 * Args:
 *   - "number": recipient phone number
 *   - "message": SMS text
 */
class SendSmsAction : Action {
    override val id = "sms.send"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val number = args["number"] ?: return ActionResult.Failure("missing number")
        val message = args["message"] ?: ""
        ctx.logger("SMS to $number: $message")
        // TODO: Implement via SmsManager.sendTextMessage
        return ActionResult.Success
    }
}

/**
 * Take a screenshot.
 *
 * Args:
 *   - "path": optional output file path
 */
class ScreenshotAction : Action {
    override val id = "screenshot.take"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: "/sdcard/screenshot.png"
        ctx.logger("Screenshot: $path")
        // TODO: Implement via shell screencap command or MediaProjection
        return ActionResult.Success
    }
}
