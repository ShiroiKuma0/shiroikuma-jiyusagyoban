package com.opentasker.core.actions

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Switch to the previous / next app — like the system quick-switch. Builds an MRU list of recently
 * foregrounded apps from UsageStats and steps a cursor through a stable snapshot: "previous" goes to an
 * older app, "next" comes back toward the current one. The snapshot is retaken whenever the foreground
 * is an app we didn't launch (i.e. the user switched apps themselves), so cycling stays coherent. Needs
 * Usage access; "previous" is the common alt-tab-to-last-app.
 */
internal object RecentAppsSwitcher {
    private var snapshot: List<String> = emptyList()
    private var pos: Int = 0
    private var lastLaunched: String? = null

    @Synchronized
    fun navigate(context: Context, forward: Boolean): Boolean {
        val mru = recentPackages(context)
        if (mru.size < 2) return false
        // Re-snapshot when the foreground app isn't the one we last launched (the user switched apps).
        if (snapshot.size < 2 || mru.firstOrNull() != lastLaunched) {
            snapshot = mru
            pos = 0
        }
        pos = (if (forward) pos - 1 else pos + 1).coerceIn(0, snapshot.size - 1)
        val target = snapshot.getOrNull(pos) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(target)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false
        return runCatching { context.startActivity(intent); lastLaunched = target; true }.getOrDefault(false)
    }

    private fun recentPackages(context: Context): List<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60 * 60 * 1000L, now) }.getOrNull() ?: return emptyList()
        val ev = UsageEvents.Event()
        val ordered = ArrayList<Pair<String, Long>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val t = ev.eventType
            @Suppress("DEPRECATION")
            val fg = t == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && t == UsageEvents.Event.ACTIVITY_RESUMED)
            if (fg) ordered += ev.packageName.orEmpty() to ev.timeStamp
        }
        val self = context.packageName
        val launcher = launcherPackage(context)
        // Most-recent-first, deduped, excluding ourselves / the launcher / system UI.
        return ordered.asReversed().asSequence()
            .map { it.first }
            .filter { it.isNotEmpty() && it != self && it != launcher && it != "com.android.systemui" }
            .distinct()
            .toList()
    }

    private fun launcherPackage(context: Context): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()
}

/** `Previous app` — switch to the most recent app before the current one (alt-tab to last app). */
class PreviousAppAction : Action {
    override val id = "app.previous"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        if (RecentAppsSwitcher.navigate(ctx.app, forward = false)) {
            ctx.logger("Previous app"); ActionResult.Success
        } else ActionResult.Failure("No previous app (needs Usage access; open a couple of apps first)")
}

/** `Next app` — step forward through the recent-apps cycle. */
class NextAppAction : Action {
    override val id = "app.next"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        if (RecentAppsSwitcher.navigate(ctx.app, forward = true)) {
            ctx.logger("Next app"); ActionResult.Success
        } else ActionResult.Failure("No next app (needs Usage access)")
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
        fun allowedSchemes(): Set<String> = ALLOWED_SCHEMES
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
        val path = args["path"]?.takeIf { it.isNotBlank() }
            ?: ctx.app.getExternalFilesDir(null)?.resolve("screenshot_${System.currentTimeMillis()}.png")?.absolutePath
            ?: return ActionResult.Failure("no writable path for the screenshot")
        requireShizuku("Screenshot")?.let { return it }
        val result = runCatching { withContext(Dispatchers.IO) { ShizukuShell.exec("screencap -p '$path'") } }
            .getOrElse { return ActionResult.Failure("Shizuku exec failed: ${it.message}") }
        if (result.exitCode != 0) {
            return ActionResult.Failure("screencap failed: ${result.stderr.trim().take(160).ifBlank { "exit ${result.exitCode}" }}")
        }
        ctx.variables.set(args["store"]?.trim()?.takeIf { it.isNotEmpty() } ?: "screenshot_path", path)
        ctx.logger("Screenshot: $path")
        return ActionResult.Success
    }
}
