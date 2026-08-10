package com.opentasker.core.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

// ---------------------------------------------------------------------------------------------
// Wave 2 — easy platform actions (clean intents / framework APIs, no elevated privileges).
// ---------------------------------------------------------------------------------------------

private fun onMain(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

/**
 * `Flash` (Tasker 548) — show a styled overlay "flash". Defaults (background, text, border colours,
 * width, corner, size, weight) come from the 白い熊 自由作業盤 UI "Flash / toast" settings; per-action
 * fields override colours and position, and an HTML toggle interprets HTML in the text.
 */
class FlashAction : Action {
    override val id = "flash"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val raw = args["text"] ?: return ActionResult.Failure("missing text")
        val prefs = com.opentasker.ui.theme.ThemeStore.state.value
        val text: CharSequence = if (truthy(args["html"])) {
            android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_COMPACT)
        } else {
            raw
        }
        val gravity = flashGravity(args["position"])
        onMain {
            FlashOverlay.show(
                context = ctx.app,
                text = text,
                backgroundColor = parseColorOr(args["background_color"], prefs.flashBackground),
                textColor = parseColorOr(args["text_color"], prefs.flashText),
                borderColor = parseColorOr(args["border_color"], prefs.flashBorder),
                borderWidthDp = prefs.flashBorderWidthDp,
                cornerRadiusDp = prefs.flashCornerRadiusDp,
                textSizeSp = prefs.flashTextSizeSp,
                fontWeight = prefs.flashFontWeight,
                gravity = gravity,
                xDp = args["x"]?.trim()?.toIntOrNull() ?: 0,
                yDp = args["y"]?.trim()?.toIntOrNull() ?: 0,
                longDuration = truthy(args["long"]),
            )
        }
        ctx.logger("Flash: $raw")
        return ActionResult.Success
    }
}

/** `Anchor` / Comment (Tasker 300) — a labelled no-op for documenting a task. */
class CommentAction : Action {
    override val id = "flow.comment"
    override val category = ActionCategory.FLOW
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Comment: ${args["text"].orEmpty()}")
        return ActionResult.Success
    }
}

/** `Set Clipboard` (Tasker 105). */
class SetClipboardAction : Action {
    override val id = "clipboard.set"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"].orEmpty()
        val cm = ctx.app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ActionResult.Failure("clipboard service not available")
        onMain { cm.setPrimaryClip(ClipData.newPlainText("白い熊 自由作業盤", text)) }
        ctx.logger("Clipboard set")
        return ActionResult.Success
    }
}

/** `Get Clipboard` (Tasker 402) — read clipboard text into a variable. */
class GetClipboardAction : Action {
    override val id = "clipboard.get"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim().orEmpty()
        if (store.isEmpty()) return ActionResult.Failure("missing store variable")
        val cm = ctx.app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ActionResult.Failure("clipboard service not available")
        val clip = cm.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx.app)?.toString().orEmpty()
        ctx.variables.set(store, text)
        ctx.logger("Clipboard → %$store")
        return ActionResult.Success
    }
}

/** `Compose Email` (Tasker 125) — open the email composer prefilled. */
class ComposeEmailAction : Action {
    override val id = "email.compose"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            args["to"]?.takeIf { it.isNotBlank() }
                ?.let { putExtra(Intent.EXTRA_EMAIL, it.split(",", ";").map(String::trim).toTypedArray()) }
            args["cc"]?.takeIf { it.isNotBlank() }
                ?.let { putExtra(Intent.EXTRA_CC, it.split(",", ";").map(String::trim).toTypedArray()) }
            args["subject"]?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            args["body"]?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.app.startActivity(intent)
            ctx.logger("Compose email")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("no email app: ${e.message}")
        }
    }
}

/**
 * `Set Wallpaper` (Tasker 109) — set the wallpaper from an image, on either screen or both.
 *
 * `where` exists because the lock screen is the interesting target and the API will not do it by
 * accident: `setBitmap(bitmap)` with no flags sets the home screen and leaves the lock screen alone.
 * Setting a one-pixel black PNG as the LOCK wallpaper is the whole point of 白い熊's Tasker original.
 *
 * `shared` follows `file.write`: off, the path is the app's own files; on, it resolves under /sdcard,
 * which is where a wallpaper actually lives.
 */
class SetWallpaperAction : Action {
    override val id = "wallpaper.set"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val shared = args["shared"]?.trim()?.lowercase() in setOf("true", "1", "yes", "on")
        val file = safeTarget(ctx, path, shared = shared)?.takeIf { it.isFile }
            ?: return ActionResult.Failure("no readable image at \"$path\"")
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return ActionResult.Failure("not a readable image")

        val manager = android.app.WallpaperManager.getInstance(ctx.app)
        val flags = when (args["where"]?.trim()?.lowercase()) {
            "lock", "lockscreen" -> android.app.WallpaperManager.FLAG_LOCK
            "home", "system" -> android.app.WallpaperManager.FLAG_SYSTEM
            "both", "all" -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
            null, "" -> android.app.WallpaperManager.FLAG_SYSTEM
            else -> return ActionResult.Failure("where must be home, lock or both")
        }
        return try {
            manager.setBitmap(bitmap, null, true, flags)
            ctx.logger("Wallpaper set from ${file.name} (${args["where"] ?: "home"})")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("set wallpaper failed: ${e.message}")
        }
    }
}

/**
 * `Set Live Wallpaper` — switch the live wallpaper to a component, without the picker where possible.
 *
 * See [com.opentasker.core.wallpaper.LiveWallpaper]: with Shizuku this is silent, because `shell`
 * holds the permission the framework reserves for privileged apps. Without it, the system preview
 * opens and the wallpaper is applied by a confirming tap — which the action reports honestly rather
 * than claiming success.
 */
class SetLiveWallpaperAction : Action {
    override val id = "wallpaper.live"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        val cls = args["class"]?.trim().orEmpty()
        if (pkg.isEmpty() || cls.isEmpty()) return ActionResult.Failure("package and class are both required")

        val component = if (cls.startsWith(".")) "$pkg$cls" else cls
        // %WALLPAPER_LIVE says WHICH path ran — "set" is silent and done, "confirm" means the picker
        // is on screen waiting. Without it a task cannot tell the two apart, and neither could I.
        return when (val outcome = com.opentasker.core.wallpaper.LiveWallpaper.set(ctx.app, pkg, component)) {
            is com.opentasker.core.wallpaper.LiveWallpaper.Outcome.Set -> {
                ctx.variables.set("WALLPAPER_LIVE", "set")
                ctx.variables.set("WALLPAPER_LIVE_WHY", "")
                ctx.logger("Live wallpaper set to $pkg/$component")
                ActionResult.Success
            }
            is com.opentasker.core.wallpaper.LiveWallpaper.Outcome.NeedsConfirm -> {
                ctx.variables.set("WALLPAPER_LIVE", "confirm")
                ctx.variables.set("WALLPAPER_LIVE_WHY", outcome.reason)
                ctx.logger("Live wallpaper picker opened (${outcome.reason}) — confirm on screen")
                ActionResult.Success
            }
            is com.opentasker.core.wallpaper.LiveWallpaper.Outcome.Failed -> {
                ctx.variables.set("WALLPAPER_LIVE", "failed")
                ctx.variables.set("WALLPAPER_LIVE_WHY", outcome.reason)
                ActionResult.Failure(outcome.reason)
            }
        }
    }
}

/** `WiFi Settings` (Tasker 206) — open the system Wi-Fi settings screen. */
class WifiSettingsAction : Action {
    override val id = "wifi.settings"
    override val category = ActionCategory.SETTINGS
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        return try {
            ctx.app.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ctx.logger("Opened Wi-Fi settings")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("could not open Wi-Fi settings: ${e.message}")
        }
    }
}

/** `List Apps` (Tasker 815) — list installed apps into array variable(s). */
class ListAppsAction : Action {
    override val id = "apps.list"
    override val category = ActionCategory.APP
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["packages"]?.trim().orEmpty()
        if (store.isEmpty()) return ActionResult.Failure("missing packages array name")
        val includeSystem = truthy(args["include_system"])
        val pm = ctx.app.packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        ctx.variables.setArray(store, apps.map { it.packageName })
        args["labels"]?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { labelsVar -> ctx.variables.setArray(labelsVar, apps.map { pm.getApplicationLabel(it).toString() }) }
        ctx.logger("Listed ${apps.size} apps → %$store")
        return ActionResult.Success
    }
}

/** `Input Method Select` (Tasker 804) — show the keyboard (IME) picker. */
class ImePickerAction : Action {
    override val id = "ime.pick"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val imm = ctx.app.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return ActionResult.Failure("input method service not available")
        onMain { imm.showInputMethodPicker() }
        ctx.logger("Showed IME picker")
        return ActionResult.Success
    }
}

// (Removed ShowKeyboardAction / keyboard.show — Android won't let one app force another app's IME to show;
// the edge-bar up-swipe re-focuses Termux instead. See the キーボード表示 task. 白い熊)
