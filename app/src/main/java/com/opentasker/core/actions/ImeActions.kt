package com.opentasker.core.actions

import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.VariableNamePolicy

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
        val target = resolveImeTarget(requested, manager.enabledInputMethodList.map(InputMethodInfo::getId))
            ?: return ActionResult.Failure("IME is not enabled or is ambiguous: $requested")
        val current = Settings.Secure.getString(
            ctx.app.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        if (current == target) {
            ctx.logger("IME already selected: $target")
            return ActionResult.Success
        }

        return try {
            manager.showInputMethodPicker()
            ActionResult.Failure(
                "Android requires user selection to switch keyboards; opened the IME picker for $target",
            )
        } catch (error: RuntimeException) {
            ActionResult.Failure("could not open the IME picker: ${error.message}", error)
        }
    }
}

internal fun resolveImeTarget(requested: String, enabledIds: List<String>): String? {
    val exact = enabledIds.filter { it == requested }
    if (exact.size == 1) return exact.single()
    val byPackage = enabledIds.filter { id -> id.substringBefore('/').equals(requested, ignoreCase = true) }
    return byPackage.singleOrNull()
}
