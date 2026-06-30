package com.opentasker.core.actions

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
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
 * Freeze (disable) an app so it can't run — `pm disable-user`. The app vanishes from the launcher
 * until unfrozen. Needs Shizuku (app-management is privileged; there is no non-privileged equivalent).
 *
 * Args:
 *   - "package": package name (an installed-apps picker fills it; a %var also works)
 */
class FreezeAppAction : Action {
    override val id = "app.freeze"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        if (!ShizukuShell.available()) return ActionResult.Failure("Freeze needs Shizuku")
        val ok = runCatching {
            withContext(Dispatchers.IO) { ShizukuShell.exec("pm disable-user --user 0 $pkg").exitCode == 0 }
        }.getOrDefault(false)
        ctx.logger(if (ok) "Froze $pkg" else "Freeze failed: $pkg")
        return if (ok) ActionResult.Success else ActionResult.Failure("could not freeze $pkg")
    }
}

/**
 * Unfreeze (re-enable) a frozen app — `pm enable`. Needs Shizuku.
 *
 * Args:
 *   - "package": package name
 */
class UnfreezeAppAction : Action {
    override val id = "app.unfreeze"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        if (!ShizukuShell.available()) return ActionResult.Failure("Unfreeze needs Shizuku")
        val ok = runCatching {
            withContext(Dispatchers.IO) { ShizukuShell.exec("pm enable $pkg").exitCode == 0 }
        }.getOrDefault(false)
        ctx.logger(if (ok) "Unfroze $pkg" else "Unfreeze failed: $pkg")
        return if (ok) ActionResult.Success else ActionResult.Failure("could not unfreeze $pkg")
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

    enum class NavResult { SWITCHED, NO_RECENT, LAUNCH_FAILED }

    @Synchronized
    fun navigate(context: Context, forward: Boolean): NavResult {
        val mru = recentPackages(context) // most-recent-first, incl. the current app, excl. launcher/systemui
        if (mru.isEmpty()) return NavResult.NO_RECENT
        val self = context.packageName
        val current = mru.first()                  // the foreground app right now
        val switchable = mru.filter { it != self }  // apps we can alt-tab to (never our own)
        if (switchable.isEmpty()) return NavResult.NO_RECENT
        // Anchor the cursor to the current app whenever the user is somewhere we didn't send them; the
        // current app's index is -1 when it's our own app (so "previous" lands on switchable[0]).
        if (snapshot.isEmpty() || current != lastLaunched) {
            snapshot = switchable
            pos = snapshot.indexOf(current)
        }
        pos = (if (forward) pos - 1 else pos + 1).coerceIn(0, snapshot.size - 1)
        val target = snapshot.getOrNull(pos) ?: return NavResult.NO_RECENT
        val intent = context.packageManager.getLaunchIntentForPackage(target)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return NavResult.LAUNCH_FAILED
        return runCatching { context.startActivity(intent); lastLaunched = target; NavResult.SWITCHED }
            .getOrDefault(NavResult.LAUNCH_FAILED)
    }

    /** Whether the user has granted "Usage access" (PACKAGE_USAGE_STATS) — required to read the MRU list. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun toast(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun recentPackages(context: Context): List<String> {
        val launchers = launcherPackages(context)
        // Keep our own package here (the caller uses it to detect the current foreground app); only
        // drop the launcher(s) + system UI. The switchable list filters out self.
        fun keep(p: String) = p.isNotEmpty() && p !in launchers && p != "com.android.systemui"

        // Primary: the accessibility service's foreground history — it sees every app the user switches
        // to, including ones UsageStats omits (e.g. emacs). Used once it has warmed up a couple entries.
        val acc = ShiroiKumaAccessibilityService.recentApps.asSequence().filter { keep(it) }.distinct().toList()
        if (acc.size >= 2) return acc

        // Fallback (service off, or its history not warmed yet): UsageStats.
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return acc
        val now = System.currentTimeMillis()

        // 1) Precise most-recent-first order from foreground events over the last day.
        val events = runCatching { usm.queryEvents(now - 24 * 60 * 60 * 1000L, now) }.getOrNull()
        if (events != null) {
            val ev = UsageEvents.Event()
            val ordered = ArrayList<String>()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val t = ev.eventType
                @Suppress("DEPRECATION")
                val fg = t == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && t == UsageEvents.Event.ACTIVITY_RESUMED)
                if (fg) ev.packageName?.let { ordered += it }
            }
            val mru = ordered.asReversed().asSequence().filter { keep(it) }.distinct().toList()
            if (mru.size >= 2) return mru
        }
        // 2) Fallback: aggregated last-used over the last week (catches a sparse event stream).
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7 * 24 * 60 * 60 * 1000L, now)
        }.getOrNull().orEmpty()
        return stats.asSequence()
            .filter { keep(it.packageName) && it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .distinct()
            .toList()
    }

    // ALL packages that can act as Home (Huawei ships more than one) — so none leak into the app cycle.
    private fun launcherPackages(context: Context): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

// Shared app-switch: report the precise reason as a toast (so a failed swipe isn't a silent no-op).
private fun switchApp(ctx: ActionContext, forward: Boolean, label: String): ActionResult {
    if (!RecentAppsSwitcher.hasUsageAccess(ctx.app)) {
        RecentAppsSwitcher.toast(ctx.app, "アプリ切替には「使用状況へのアクセス」(Usage access) の許可が必要です")
        return ActionResult.Failure("Switch apps needs Usage access")
    }
    return when (RecentAppsSwitcher.navigate(ctx.app, forward)) {
        RecentAppsSwitcher.NavResult.SWITCHED -> { ctx.logger(label); ActionResult.Success }
        RecentAppsSwitcher.NavResult.NO_RECENT -> {
            RecentAppsSwitcher.toast(ctx.app, "切替先の最近のアプリがありません（先に他のアプリを2つ以上開いてください）")
            ActionResult.Failure("No app to switch to")
        }
        RecentAppsSwitcher.NavResult.LAUNCH_FAILED -> {
            RecentAppsSwitcher.toast(ctx.app, "アプリの起動がブロックされました（背景起動制限）")
            ActionResult.Failure("App launch blocked")
        }
    }
}

/** `Previous app` — switch to the most recent app before the current one (alt-tab to last app). */
class PreviousAppAction : Action {
    override val id = "app.previous"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        switchApp(ctx, forward = false, label = "Previous app")
}

/** `Next app` — step forward through the recent-apps cycle. */
class NextAppAction : Action {
    override val id = "app.next"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult =
        switchApp(ctx, forward = true, label = "Next app")
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
