package com.opentasker.core.permissions

import android.Manifest
import android.content.Context
import android.os.Build
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
class RuntimePermissionRequestHistory(context: Context, sdkInt: Int = Build.VERSION.SDK_INT) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        forgetIgnoredPreciseLocationRequests(sdkInt)
    }

    /**
     * Setup used to ask for precise location on its own. Android 12+ ignores that request and
     * reports a denial with no rationale, so two taps recorded a permanent denial nobody made, and
     * the fixed request stayed hidden behind "Open app settings". Those records are dropped once;
     * a real denial comes back after the next two attempts, as it did the first time.
     */
    private fun forgetIgnoredPreciseLocationRequests(sdkInt: Int) {
        if (sdkInt < Build.VERSION_CODES.S || prefs.getBoolean(PRECISE_LOCATION_RECORDS_FORGOTTEN, false)) return
        prefs.edit {
            remove(attemptKey(Manifest.permission.ACCESS_FINE_LOCATION))
            remove(settingsKey(Manifest.permission.ACCESS_FINE_LOCATION))
            putBoolean(PRECISE_LOCATION_RECORDS_FORGOTTEN, true)
        }
    }

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
        private const val PRECISE_LOCATION_RECORDS_FORGOTTEN = "forgotten:precise_location_alone"
    }
}
