package com.sysadmindoc.ruledeck.core.actions

import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.model.VariableNamePolicy

class ImeInfoAction : DeclaredAction(ActionCatalog.require("ime.info")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val baseName = args["var"]?.trim().orEmpty().ifBlank { DEFAULT_VARIABLE }
        if (VariableNamePolicy.normalize(baseName) == null) {
            return ActionResult.Failure("invalid output variable name: $baseName")
        }
        val manager = ctx.app.getSystemService(InputMethodManager::class.java)
            ?: return ActionResult.Failure("input-method service not available")
        val enabled = manager.enabledInputMethodList
        val current = Settings.Secure.getString(
            ctx.app.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        val ids = enabled.map(InputMethodInfo::getId)
        ctx.variables.set("${baseName}_CURRENT", current)
        ctx.variables.set("${baseName}_ENABLED", ids.joinToString(","))
        ctx.variables.set("${baseName}_COUNT", ids.size.toString())
        ctx.logger("IME info: ${ids.size} enabled, current=${current.ifBlank { "none" }}")
        return ActionResult.Success
    }

    companion object {
        private const val DEFAULT_VARIABLE = "IME"
    }
}

class ImeSetAction : DeclaredAction(ActionCatalog.require("ime.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val requested = args["ime_id"]?.trim().orEmpty()
        if (requested.isBlank()) return ActionResult.Failure("missing ime_id")
        val manager = ctx.app.getSystemService(InputMethodManager::class.java)
            ?: return ActionResult.Failure("input-method service not available")
        return switchKeyboard(
            requested = requested,
            enabledIds = manager.enabledInputMethodList.map(InputMethodInfo::getId),
            currentIme = {
                Settings.Secure.getString(
                    ctx.app.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                ).orEmpty()
            },
            openPicker = manager::showInputMethodPicker,
            log = ctx.logger,
        )
    }
}

/**
 * What `ime.set` decides once Android has been asked. An ordinary app can open the keyboard
 * picker but can't choose for the user, so every real switch ends as a Failure that names the
 * picker: reporting Success there would claim a change that never happened. Split from the action
 * so a test can drive it without an InputMethodManager.
 */
internal fun switchKeyboard(
    requested: String,
    enabledIds: List<String>,
    currentIme: () -> String,
    openPicker: () -> Unit,
    log: (String) -> Unit,
): ActionResult {
    val target = resolveImeTarget(requested, enabledIds)
        ?: return ActionResult.Failure("IME is not enabled or is ambiguous: $requested")
    if (currentIme() == target) {
        log("IME already selected: $target")
        return ActionResult.Success
    }

    return try {
        openPicker()
        ActionResult.Failure(
            "Android requires user selection to switch keyboards; opened the IME picker for $target",
        )
    } catch (error: RuntimeException) {
        ActionResult.Failure("could not open the IME picker: ${error.message}", error)
    }
}

internal fun resolveImeTarget(requested: String, enabledIds: List<String>): String? {
    val exact = enabledIds.filter { it == requested }
    if (exact.size == 1) return exact.single()
    val byPackage = enabledIds.filter { id -> id.substringBefore('/').equals(requested, ignoreCase = true) }
    return byPackage.singleOrNull()
}
