package com.sysadmindoc.ruledeck.core.capabilities

import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt

/**
 * Whether any task in the workspace uses the locale switch. Its CHANGE_CONFIGURATION grant needs
 * adb, Shizuku or root, so the Setup tab asks for it only on a phone whose tasks really switch the
 * locale; elsewhere the item is listed as optional and raises no warning.
 */
object LocaleSwitchUse {
    const val ACTION_TYPE = "system.set_locale"

    /** True until the workspace has been looked at, so nothing needed is ever under-reported. */
    @Volatile var used: Boolean = true

    /** Re-read from the database (for callers with no task list at hand). */
    suspend fun refresh() {
        runCatching { RuleDeckApp_NoHilt.db.taskDao().getAll().any { ACTION_TYPE in it.actionsJson } }
            .onSuccess { used = it }
    }
}
