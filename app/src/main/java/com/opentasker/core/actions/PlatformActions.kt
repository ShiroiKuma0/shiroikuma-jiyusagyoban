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
import android.widget.Toast
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

// ---------------------------------------------------------------------------------------------
// Wave 2 — easy platform actions (clean intents / framework APIs, no elevated privileges).
// ---------------------------------------------------------------------------------------------

private fun onMain(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

/** `Flash` (Tasker 548) — show a toast. */
class FlashAction : Action {
    override val id = "flash"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val text = args["text"] ?: return ActionResult.Failure("missing text")
        val dur = if (truthy(args["long"])) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        onMain { Toast.makeText(ctx.app, text, dur).show() }
        ctx.logger("Flash: $text")
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

/** `Set Wallpaper` (Tasker 109) — set the home-screen wallpaper from an image in the sandbox. */
class SetWallpaperAction : Action {
    override val id = "wallpaper.set"
    override val category = ActionCategory.SYSTEM
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        val file = safeUserFile(ctx, path, mustExist = true) ?: return ActionResult.Failure("path is outside 白い熊 自由作業盤 files")
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return ActionResult.Failure("not a readable image")
        return try {
            android.app.WallpaperManager.getInstance(ctx.app).setBitmap(bitmap)
            ctx.logger("Wallpaper set from ${file.name}")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("set wallpaper failed: ${e.message}")
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
