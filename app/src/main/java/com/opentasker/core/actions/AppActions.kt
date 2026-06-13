package com.opentasker.core.actions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
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
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        return ActionResult.Failure("Killing apps is not supported without privileged app-management access")
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
    override val id = "url.open"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val url = args["url"] ?: return ActionResult.Failure("missing url")
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && scheme !in ALLOWED_SCHEMES) {
            return ActionResult.Failure("blocked URI scheme: $scheme (allowed: ${ALLOWED_SCHEMES.joinToString()})")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.app.startActivity(intent)
            ctx.logger("Open URL: $url")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("open failed: ${e.message}")
        }
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("https", "http", "tel", "mailto", "geo")
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
        if (!BuildConfig.SMS_ACTION_AVAILABLE) {
            return ActionResult.Failure("SMS action is unavailable in this distribution because SMS permissions are omitted for Play policy compliance")
        }
        val number = args["number"] ?: return ActionResult.Failure("missing number")
        val message = args["message"] ?: ""
        if (ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ActionResult.Failure("SMS permission is not granted")
        }
        if (message.isBlank()) return ActionResult.Failure("missing message")
        return try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                ctx.app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return ActionResult.Failure("SMS service not available")
            smsManager.sendTextMessage(number, null, message, null, null)
            ctx.logger("SMS sent to $number")
            ActionResult.Success
        } catch (ex: Exception) {
            ActionResult.Failure("SMS send failed: ${ex.message}", ex)
        }
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
        val path = args["path"]
            ?: ctx.app.getExternalFilesDir(null)?.resolve("screenshot.png")?.absolutePath
            ?: "app-specific external storage/screenshot.png"
        ctx.logger("Screenshot: $path")
        return ActionResult.Failure("Screenshot capture requires MediaProjection consent or privileged shell access")
    }
}
