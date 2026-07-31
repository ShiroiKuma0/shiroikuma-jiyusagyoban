package com.opentasker.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.logging.AppLogger
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

    /**
     * True in a [ACTION_QUERY_STATUS] reply when the user has shut the app down from its overflow menu.
     * While it is true, [ACTION_RUN_TASK] is refused with [Activity.RESULT_CANCELED] + [EXTRA_ERROR];
     * only opening 白い熊 自由作業盤 (or a reboot, when its boot setting is on) clears it.
     */
    const val EXTRA_STOPPED = "com.opentasker.extra.STOPPED"

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
                    AutomationTargetContract.ACTION_QUERY_STATUS -> queryStatus(context.applicationContext, intent)
                    else -> failure("Unsupported action: ${intent.action}")
                }
            }.getOrElse { failure(it.message ?: "Automation target request failed") }
            try {
                pending.setResultCode(response.resultCode)
                pending.setResultExtras(response.extras)
            } catch (e: Exception) {
                AppLogger.error(TAG, "Failed to publish automation target result", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runTask(appContext: Context, intent: Intent): TargetResponse {
        // Sister-app token intents do not wake a stopped app: they are told so, rather than silently
        // failing or quietly restarting the engine behind the user's back.
        if (EngineShutdown.refuse(appContext, "external intent (run task)")) {
            return failure("白い熊 自由作業盤 is stopped — open the app to start it again.")
        }
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
        if (enabled && profile.requiresRiskAcknowledgement) {
            return failure("Imported profile requires in-app power review before its first enable.")
        }
        db.profileDao().update(profile.copy(enabled = enabled).toEntity())
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_FOUND, true)
                putBoolean(AutomationTargetContract.EXTRA_PROFILE_ENABLED, enabled)
            },
        )
    }

    private suspend fun queryStatus(appContext: Context, intent: Intent): TargetResponse {
        val db = OpenTaskerApp_NoHilt.db
        val profileEntities = db.profileDao().getAll()
        val tasks = db.taskDao().getAll()
        val profile = resolveProfileEntity(intent, profileEntities)?.toDomain()
        return TargetResponse(
            Activity.RESULT_OK,
            Bundle().apply {
                // Deliberately NOT gated by the shutdown flag: a status query is a read, and asking
                // "are you stopped?" has to be answerable precisely when the answer is yes. This is how
                // a caller (e.g. the 雷起動盤 launcher) can grey out a task shortcut instead of firing
                // one that will be refused.
                putBoolean(AutomationTargetContract.EXTRA_STOPPED, EngineShutdown.isStopped(appContext))
                putInt(AutomationTargetContract.EXTRA_TASK_COUNT, tasks.size)
                putInt(AutomationTargetContract.EXTRA_PROFILE_COUNT, profileEntities.size)
                putInt(
                    AutomationTargetContract.EXTRA_ENABLED_PROFILE_COUNT,
                    profileEntities.count { it.enabled && !it.requiresRiskAcknowledgement },
                )
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
                        .firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?.toDomain()
                }

    private suspend fun resolveProfile(intent: Intent) =
        resolveProfileEntity(intent, OpenTaskerApp_NoHilt.db.profileDao().getAll())?.toDomain()

    private fun resolveProfileEntity(
        intent: Intent,
        profiles: List<com.opentasker.core.storage.ProfileEntity>,
    ) =
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
            .asSequence()
            .filter { it.startsWith(AutomationTargetContract.VARIABLE_EXTRA_PREFIX) }
            .sorted() // deterministic which variables survive the cap
            .mapNotNull { key ->
                val name = key.removePrefix(AutomationTargetContract.VARIABLE_EXTRA_PREFIX)
                if (!AutomationTargetContract.isValidVariableName(name)) return@mapNotNull null
                val value = extras.getString(key) ?: return@mapNotNull null
                name to value.take(MAX_VARIABLE_VALUE_CHARS)
            }
            .take(MAX_SUPPLIED_VARIABLES)
            .toMap()
    }

    private fun failure(message: String): TargetResponse {
        AppLogger.warn(TAG, message)
        return TargetResponse(
            Activity.RESULT_CANCELED,
            Bundle().apply { putString(AutomationTargetContract.EXTRA_ERROR, message) },
        )
    }

    companion object {
        private const val TAG = "AutomationTargetReceiver"
        private const val MAX_VARIABLE_VALUE_CHARS = 4_096
        private const val MAX_SUPPLIED_VARIABLES = 64
    }
}

private data class TargetResponse(
    val resultCode: Int,
    val extras: Bundle,
)
