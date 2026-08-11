package com.opentasker.core.external

import android.content.Context

/** Side-effect classification exposed to an AppFunctions caller. */
enum class AppFunctionSideEffect {
    READ_ONLY,
    TASK_EXECUTION,
}

/** The deliberately small capability surface available to the prototype. */
data class AppFunctionCapability(
    val functionId: String,
    val sideEffect: AppFunctionSideEffect,
    val requiresUserApproval: Boolean,
    val acceptsSecretArguments: Boolean,
    val parameterNames: Set<String>,
)

object AppFunctionPrototypeContract {
    const val MIN_API = 36
    const val SCHEMA_VERSION = 1

    const val FUNCTION_ID_RUN_APPROVED_TASK =
        "com.opentasker.appfunctions.runApprovedTask"
    const val PARAMETER_TASK_ID = "task_id"

    const val RESULT_SCHEMA_VERSION = "schema_version"
    const val RESULT_STATUS = "status"
    const val RESULT_CAPABILITY_ID = "capability_id"
    const val RESULT_EXECUTION_ID = "execution_id"
    const val RESULT_STATUS_ACCEPTED = "accepted"

    const val EXECUTE_APP_FUNCTIONS_PERMISSION =
        "android.permission.EXECUTE_APP_FUNCTIONS"

    /** Metadata is intentionally narrower than the task engine's variable/action model. */
    val capabilities: List<AppFunctionCapability> = listOf(
        AppFunctionCapability(
            functionId = FUNCTION_ID_RUN_APPROVED_TASK,
            sideEffect = AppFunctionSideEffect.TASK_EXECUTION,
            requiresUserApproval = true,
            acceptsSecretArguments = false,
            parameterNames = setOf(PARAMETER_TASK_ID),
        ),
    )

    fun capabilityFor(functionId: String): AppFunctionCapability? =
        capabilities.firstOrNull { it.functionId == functionId }
}

enum class AppFunctionAvailability {
    SUPPORTED,
    UNSUPPORTED_API,
}

object AppFunctionSupport {
    fun availability(apiLevel: Int): AppFunctionAvailability =
        if (apiLevel >= AppFunctionPrototypeContract.MIN_API) {
            AppFunctionAvailability.SUPPORTED
        } else {
            AppFunctionAvailability.UNSUPPORTED_API
        }

    fun unsupportedMessage(apiLevel: Int): String =
        "AppFunctions require Android 16 (API 36); this device is API $apiLevel."
}

enum class AppFunctionExecutionGate {
    ALLOWED,
    DISABLED,
    TASK_NOT_APPROVED,
    INVALID_TASK_ID,
}

object AppFunctionExecutionPolicy {
    fun evaluate(
        enabled: Boolean,
        approvedTaskIds: Set<Long>,
        taskId: Long,
    ): AppFunctionExecutionGate = when {
        taskId <= 0L -> AppFunctionExecutionGate.INVALID_TASK_ID
        !enabled -> AppFunctionExecutionGate.DISABLED
        taskId !in approvedTaskIds -> AppFunctionExecutionGate.TASK_NOT_APPROVED
        else -> AppFunctionExecutionGate.ALLOWED
    }
}

object AppFunctionCallerPolicy {
    /** The system broker normally supplies an empty/unknown package on newer platform releases. */
    fun isTrustedCaller(
        executePermissionGranted: Boolean,
        callingPackage: String,
        servicePackage: String,
    ): Boolean = executePermissionGranted &&
        (callingPackage.isBlank() || callingPackage != servicePackage)
}

/**
 * Credential-protected policy state for the prototype.
 *
 * The mutator is intentionally named for its required origin: a future in-app approval flow must
 * call it only after the user has selected the task. No AppFunction argument can add a task or
 * supply variables, credentials, or other secret values.
 */
class AppFunctionPrototypePolicyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    val isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)

    val approvedTaskIds: Set<Long>
        get() = preferences.getStringSet(KEY_APPROVED_TASK_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }
            .toSet()

    fun isTaskApproved(taskId: Long): Boolean = taskId in approvedTaskIds

    /** Must be called by a user-mediated in-app approval flow, not by an external caller. */
    fun setFromUser(enabled: Boolean, approvedTaskIds: Set<Long>) {
        val normalizedIds = approvedTaskIds
            .filter { it > 0L }
            .map(Long::toString)
            .toSet()
        preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putStringSet(KEY_APPROVED_TASK_IDS, normalizedIds)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME = "app_function_prototype"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_APPROVED_TASK_IDS = "approved_task_ids"
    }
}
