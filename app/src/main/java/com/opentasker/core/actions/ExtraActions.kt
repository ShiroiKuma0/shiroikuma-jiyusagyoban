package com.opentasker.core.actions

import android.provider.Settings
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.storage.toEntity

// ---------------------------------------------------------------------------------------------
// Step 1 small wins — profile enable/disable and read/write of System settings.
// ---------------------------------------------------------------------------------------------

/** `Profile Status` (Tasker 159) — enable / disable / toggle a profile by name. */
class ToggleProfileAction : Action {
    override val id = "profile.toggle"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["profile"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing profile name")
        val db = OpenTaskerApp_NoHilt.db
        val entity = db.profileDao().getAll().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return ActionResult.Failure("no profile named \"$name\"")
        val profile = entity.toDomain()
        val target = when (args["state"]?.trim()?.lowercase()) {
            "on", "enable", "enabled", "true", "1" -> true
            "off", "disable", "disabled", "false", "0" -> false
            "toggle", null, "" -> !profile.enabled
            else -> return ActionResult.Failure("state must be on / off / toggle")
        }
        // The automation engine observes the profile flow, so a DB update is enough to apply it.
        db.profileDao().update(profile.copy(enabled = target).toEntity())
        ctx.logger("Profile \"$name\" ${if (target) "enabled" else "disabled"}")
        return ActionResult.Success
    }
}

/** `Custom Setting` read (Tasker 235) — read a System/Secure/Global setting into a variable. */
class GetSettingAction : Action {
    override val id = "setting.get"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val key = args["name"]?.trim().orEmpty()
        if (key.isEmpty()) return ActionResult.Failure("missing setting name")
        val store = args["store"]?.trim().orEmpty()
        if (store.isEmpty()) return ActionResult.Failure("missing store variable")
        val cr = ctx.app.contentResolver
        val value = when (args["namespace"]?.trim()?.lowercase()) {
            "secure" -> Settings.Secure.getString(cr, key)
            "global" -> Settings.Global.getString(cr, key)
            else -> Settings.System.getString(cr, key)
        }
        ctx.variables.set(store, value.orEmpty())
        ctx.logger("Setting $key → %$store")
        return ActionResult.Success
    }
}

/** `Custom Setting` write (Tasker 235) — write a System setting (WRITE_SETTINGS; System namespace only). */
class PutSettingAction : Action {
    override val id = "setting.put"
    override val category = ActionCategory.SETTINGS

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val key = args["name"]?.trim().orEmpty()
        if (key.isEmpty()) return ActionResult.Failure("missing setting name")
        val namespace = args["namespace"]?.trim()?.lowercase() ?: "system"
        if (namespace != "system") {
            return ActionResult.Failure("only the System namespace is writable without elevated (Shizuku) access")
        }
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        return try {
            Settings.System.putString(ctx.app.contentResolver, key, args["value"].orEmpty())
            ctx.logger("Setting $key set")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed to write setting: ${e.message}")
        }
    }
}
