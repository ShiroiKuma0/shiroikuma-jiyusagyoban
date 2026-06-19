package com.opentasker.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AutomationTargetContract {
    const val PERMISSION = "com.opentasker.permission.AUTOMATION"

    const val ACTION_RUN_TASK = "com.opentasker.action.RUN_TASK"
    const val ACTION_SET_PROFILE_ENABLED = "com.opentasker.action.SET_PROFILE_ENABLED"
    const val ACTION_QUERY_STATUS = "com.opentasker.action.QUERY_STATUS"

    const val EXTRA_TASK_ID = "com.opentasker.extra.TASK_ID"
    const val EXTRA_TASK_NAME = "com.opentasker.extra.TASK_NAME"
    const val EXTRA_PROFILE_ID = "com.opentasker.extra.PROFILE_ID"
    const val EXTRA_PROFILE_NAME = "com.opentasker.extra.PROFILE_NAME"
    const val EXTRA_ENABLED = "com.opentasker.extra.ENABLED"
    const val EXTRA_ERROR = "com.opentasker.extra.ERROR"
    const val EXTRA_TASK_SUCCESS = "com.opentasker.extra.TASK_SUCCESS"
    const val EXTRA_TASK_DURATION_MS = "com.opentasker.extra.TASK_DURATION_MS"
    const val EXTRA_PROFILE_FOUND = "com.opentasker.extra.PROFILE_FOUND"
    const val EXTRA_PROFILE_ENABLED = "com.opentasker.extra.PROFILE_ENABLED"
    const val EXTRA_PROFILE_CONTEXT_COUNT = "com.opentasker.extra.PROFILE_CONTEXT_COUNT"
    const val EXTRA_TASK_COUNT = "com.opentasker.extra.TASK_COUNT"
    const val EXTRA_PROFILE_COUNT = "com.opentasker.extra.PROFILE_COUNT"
    const val EXTRA_ENABLED_PROFILE_COUNT = "com.opentasker.extra.ENABLED_PROFILE_COUNT"

    const val VARIABLE_EXTRA_PREFIX = "com.opentasker.var."
    private val variableNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    fun isValidVariableName(name: String): Boolean = variableNamePattern.matches(name)

    fun variableExtraName(variableName: String): String {
        require(isValidVariableName(variableName)) { "Invalid variable name." }
        return VARIABLE_EXTRA_PREFIX + variableName
    }
}

class AutomationTargetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val response = runCatching {
                when (intent.action) {
                    AutomationTargetContract.ACTION_RUN_TASK -> runTask(context.applicationContext, intent)
                    AutomationTargetContract.ACTION_SET_PROFILE_ENABLED -> setProfileEnabled(intent)
                    AutomationTargetContract.ACTION_QUERY_STATUS -> queryStatus(intent)
                    else -> failure("Unsupported action: ${intent.action}")
                }
            }.getOrElse { failure(it.message ?: "Automation target request failed") }
            try {
                pending.setResultCode(response.resultCode)
                pending.setResultExtras(response.extras)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish automation target result", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runTask(appContext: Context, intent: Intent): TargetResponse {
        val db = OpenTaskerApp_NoHilt.db
        val task = resolveTask(intent)
            ?: return failure("Task not found. Provide ${AutomationTargetContract.EXTRA_TASK_ID} or ${AutomationTargetContract.EXTRA_TASK_NAME}.")

        val suppliedVariables = extractVariables(intent.extras)
        val result = executeAndLogTask(
            appContext = appContext,
            db = db,
            task = task,
            source = "External intent",
            metadata = listOf("Variables: ${suppliedVariables.size} provided"),
            initialVariables = suppliedVariables,
            logTag = TAG,
        )

        return TargetResponse(
            if (result.report.success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putBoolean(AutomationTargetContract.EXTRA_TASK_SUCCESS, result.report.success)
                putLong(AutomationTargetContract.EXTRA_TASK_DURATION_MS, result.report.durationMs)
            },
        )
    }

    private suspend fun setProfileEnabled(intent: Intent): TargetResponse {
        val db = OpenTaskerApp_NoHilt.db
        val profile = resolveProfile(intent)
            ?: return failure("Profile not found. Provide ${AutomationTargetContract.EXTRA_PROFILE_ID} or ${AutomationTargetContract.EXTRA_PROFILE_NAME}.")
        val enabled = intent.getBooleanExtra(AutomationTargetContract.EXTRA_ENABLED, profile.enabled)
        db.profileDao().update(profile.copy(enabled = enabled).toEntity())
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, true)
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, enabled)
            },
        )
    }

    private suspend fun queryStatus(intent: Intent): TargetResponse {
        val db = OpenTaskerApp_NoHilt.db
        val profiles = db.profileDao().getAll().map { it.toDomain() }
        val tasks = db.taskDao().getAll()
        val profile = resolveProfile(intent, profiles)
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putInt(AutomationTargetContract.EXTRA_TASK_COUNT, tasks.size)
                putInt(AutomationTargetContract.EXTRA_PROFILE_COUNT, profiles.size)
                putInt(AutomationTargetContract.EXTRA_ENABLED_PROFILE_COUNT, profiles.count { it.enabled })
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, profile != null)
                profile?.let {
                    putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, it.enabled)
                    putInt(AutomationTargetContract.EXTRA_PROFILE_CONTEXT_COUNT, it.contexts.size)
                }
            },
        )
    }

    private suspend fun resolveTask(intent: Intent) =
        intent.getLongExtra(AutomationTargetContract.EXTRA_TASK_ID, 0L)
            .takeIf { it > 0 }
            ?.let { OpenTaskerApp_NoHilt.db.taskDao().getById(it)?.toDomain() }
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_TASK_NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    OpenTaskerApp_NoHilt.db.taskDao().getAll()
                        .map { it.toDomain() }
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                }

    private suspend fun resolveProfile(intent: Intent) =
        resolveProfile(intent, OpenTaskerApp_NoHilt.db.profileDao().getAll().map { it.toDomain() })

    private fun resolveProfile(intent: Intent, profiles: List<com.opentasker.core.model.Profile>) =
        intent.getLongExtra(AutomationTargetContract.EXTRA_PROFILE_ID, 0L)
            .takeIf { it > 0 }
            ?.let { id -> profiles.firstOrNull { it.id == id } }
            ?: intent.getStringExtra(AutomationTargetContract.EXTRA_PROFILE_NAME)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { name -> profiles.firstOrNull { it.name.equals(name, ignoreCase = true) } }

    private fun extractVariables(extras: Bundle?): Map<String, String> {
        if (extras == null) return emptyMap()
        return extras.keySet()
            .filter { it.startsWith(AutomationTargetContract.VARIABLE_EXTRA_PREFIX) }
            .mapNotNull { key ->
                val name = key.removePrefix(AutomationTargetContract.VARIABLE_EXTRA_PREFIX)
                if (!AutomationTargetContract.isValidVariableName(name)) return@mapNotNull null
                val value = extras.getString(key) ?: return@mapNotNull null
                name to value.take(MAX_VARIABLE_VALUE_CHARS)
            }
            .toMap()
    }

    private fun failure(message: String): TargetResponse {
        Log.w(TAG, message)
        return TargetResponse(
            Activity.RESULT_CANCELED,
            Bundle().apply { putString(AutomationTargetContract.EXTRA_ERROR, message) },
        )
    }

    companion object {
        private const val TAG = "AutomationTargetReceiver"
        private const val MAX_VARIABLE_VALUE_CHARS = 4_096
    }
}

private data class TargetResponse(
    val resultCode: Int,
    val extras: Bundle,
)
