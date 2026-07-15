package com.opentasker.core.permissions

import android.content.Context
import androidx.core.content.edit

data class RuntimePermissionRequestState(
    val attemptCount: Int = 0,
    val settingsRequired: Boolean = false,
)

enum class RuntimePermissionOutcome {
    Granted,
    DeniedCanRetry,
    SettingsRequired,
}

data class RuntimePermissionDecision(
    val state: RuntimePermissionRequestState,
    val outcome: RuntimePermissionOutcome,
)

object RuntimePermissionRecoveryPolicy {
    fun afterRequest(state: RuntimePermissionRequestState): RuntimePermissionRequestState =
        state.copy(attemptCount = (state.attemptCount + 1).coerceAtMost(MAX_ATTEMPTS))

    fun afterResult(
        state: RuntimePermissionRequestState,
        granted: Boolean,
        shouldShowRationale: Boolean,
    ): RuntimePermissionDecision {
        if (granted) {
            return RuntimePermissionDecision(RuntimePermissionRequestState(), RuntimePermissionOutcome.Granted)
        }
        val settingsRequired = state.attemptCount >= 2 && !shouldShowRationale
        return RuntimePermissionDecision(
            state = state.copy(settingsRequired = settingsRequired),
            outcome = if (settingsRequired) {
                RuntimePermissionOutcome.SettingsRequired
            } else {
                RuntimePermissionOutcome.DeniedCanRetry
            },
        )
    }

    private const val MAX_ATTEMPTS = 100
}

/** Persists request attempts so process recreation cannot erase permanent-denial recovery state. */
class RuntimePermissionRequestHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordRequest(permission: String): RuntimePermissionRequestState {
        val requested = RuntimePermissionRecoveryPolicy.afterRequest(state(permission))
        persist(permission, requested)
        return requested
    }

    fun recordResult(
        permission: String,
        granted: Boolean,
        shouldShowRationale: Boolean,
    ): RuntimePermissionDecision {
        val decision = RuntimePermissionRecoveryPolicy.afterResult(
            state = state(permission),
            granted = granted,
            shouldShowRationale = shouldShowRationale,
        )
        persist(permission, decision.state)
        return decision
    }

    fun requiresSettings(permission: String): Boolean = state(permission).settingsRequired

    fun clear(permission: String) {
        prefs.edit {
            remove(attemptKey(permission))
            remove(settingsKey(permission))
        }
    }

    private fun state(permission: String): RuntimePermissionRequestState = RuntimePermissionRequestState(
        attemptCount = prefs.getInt(attemptKey(permission), 0),
        settingsRequired = prefs.getBoolean(settingsKey(permission), false),
    )

    private fun persist(permission: String, state: RuntimePermissionRequestState) {
        prefs.edit {
            putInt(attemptKey(permission), state.attemptCount)
            putBoolean(settingsKey(permission), state.settingsRequired)
        }
    }

    private fun attemptKey(permission: String): String = "attempts:$permission"

    private fun settingsKey(permission: String): String = "settings:$permission"

    companion object {
        private const val PREFS_NAME = "runtime_permission_request_history"
    }
}
