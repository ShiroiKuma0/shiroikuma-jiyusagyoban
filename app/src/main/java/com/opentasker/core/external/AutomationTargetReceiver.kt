package com.opentasker.core.external

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.opentasker.app.OpenTaskerApp_NoHilt
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class InternalTaskRunSource(
    val wireValue: String,
    val runLogLabel: String,
) {
    LOCALE_PLUGIN("locale_plugin", "Locale plugin"),
    SCENE_OVERLAY("scene_overlay", "Scene overlay"),
    QUICK_SETTINGS_TILE("quick_settings_tile", "Quick Settings tile"),
}

object AutomationTargetContract {
    const val PERMISSION = "com.opentasker.permission.AUTOMATION"

    // Home Assistant Companion notification commands use these field names when a
    // command_broadcast_intent action targets this receiver. They are aliases for the
    // namespaced OpenTasker extras, not replacements, so protocol-v2 callers remain stable.
    const val HOME_ASSISTANT_COMMAND_BROADCAST_INTENT = "command_broadcast_intent"
    const val HOME_ASSISTANT_FIELD_MESSAGE = "message"
    const val HOME_ASSISTANT_FIELD_DATA = "data"
    const val HOME_ASSISTANT_FIELD_INTENT_PACKAGE_NAME = "intent_package_name"
    const val HOME_ASSISTANT_FIELD_INTENT_ACTION = "intent_action"
    const val HOME_ASSISTANT_FIELD_INTENT_EXTRAS = "intent_extras"

    const val ACTION_RUN_TASK = "com.opentasker.action.RUN_TASK"
    const val ACTION_SET_PROFILE_ENABLED = "com.opentasker.action.SET_PROFILE_ENABLED"
    const val ACTION_QUERY_STATUS = "com.opentasker.action.QUERY_STATUS"
    const val ACTION_QUERY_EXECUTION = "com.opentasker.action.QUERY_EXECUTION"

    /**
     * Protocol version a RUN_TASK caller must declare.
     *
     * v1 held the broadcast open with goAsync() until the whole task finished and returned its
     * terminal success. Android expects broadcast work to finish in roughly 10 seconds while a
     * task can wait up to 30 minutes, so a v1 caller was reading a result the system could kill
     * mid-run. v2 validates, enqueues to the foreground service, and returns "accepted" plus an
     * execution id the caller polls with ACTION_QUERY_EXECUTION.
     */
    const val PROTOCOL_VERSION = 2

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
    const val EXTRA_PROTOCOL_VERSION = "com.opentasker.extra.PROTOCOL_VERSION"
    const val EXTRA_ACCEPTED = "com.opentasker.extra.ACCEPTED"
    const val EXTRA_EXECUTION_ID = "com.opentasker.extra.EXECUTION_ID"
    const val EXTRA_EXECUTION_STATE = "com.opentasker.extra.EXECUTION_STATE"
    const val EXTRA_EXECUTION_TERMINAL = "com.opentasker.extra.EXECUTION_TERMINAL"
    const val EXTRA_RUN_SOURCE = "com.opentasker.extra.RUN_SOURCE"

    /**
     * Fork: true in a [ACTION_QUERY_STATUS] reply when the user has shut the app down from its
     * overflow menu. While it is true [ACTION_RUN_TASK] is refused; only opening 白い熊 自由作業盤
     * (or a reboot, when its boot setting is on) clears it.
     */
    const val EXTRA_STOPPED = "com.opentasker.extra.STOPPED"

    const val VARIABLE_EXTRA_PREFIX = "com.opentasker.var."
    const val DEFAULT_RUN_SOURCE = "External intent"
    const val MAX_VARIABLE_VALUE_CHARS = 4_096
    const val MAX_SUPPLIED_VARIABLES = 64
    private val variableNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    fun isValidVariableName(name: String): Boolean = variableNamePattern.matches(name)

    fun variableExtraName(variableName: String): String {
        require(isValidVariableName(variableName)) { "Invalid variable name." }
        return VARIABLE_EXTRA_PREFIX + variableName
    }

    /**
     * The only supported constructor for trusted in-app RUN_TASK broadcasts.
     *
     * Keeping protocol versioning and variable encoding here prevents an internal producer from
     * silently drifting behind the exported receiver contract again.
     */
    fun internalRunTaskIntent(
        context: Context,
        taskId: Long,
        source: InternalTaskRunSource,
        variables: Map<String, String> = emptyMap(),
    ): Intent {
        require(taskId > 0) { "Task id must be positive." }
        variables.keys.forEach { variableName ->
            require(isValidVariableName(variableName)) { "Invalid variable name: $variableName" }
        }
        return Intent(context, AutomationTargetReceiver::class.java).apply {
            action = ACTION_RUN_TASK
            putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_RUN_SOURCE, source.wireValue)
            variables.entries
                .sortedBy { it.key }
                .take(MAX_SUPPLIED_VARIABLES)
                .forEach { (name, value) ->
                    putExtra(variableExtraName(name), value.take(MAX_VARIABLE_VALUE_CHARS))
                }
        }
    }

    /** Maps only known in-app source tokens/labels; callers cannot inject arbitrary run-log text. */
    fun runSourceLabel(rawSource: String?): String =
        InternalTaskRunSource.entries
            .firstOrNull { it.wireValue == rawSource || it.runLogLabel == rawSource }
            ?.runLogLabel
            ?: DEFAULT_RUN_SOURCE

    /**
     * Extracts only the bounded, string-valued variable extras accepted by RUN_TASK. Keeping this
     * parser beside the public contract lets tests and future producers exercise the same boundary
     * without invoking a receiver or touching the database.
     */
    fun extractVariableExtras(extras: Bundle?): Map<String, String> {
        if (extras == null) return emptyMap()
        return extractVariableExtras(extras.keySet().associateWith { key -> extras.getString(key) })
    }

    internal fun extractVariableExtras(values: Map<String, String?>): Map<String, String> = values.keys
            .asSequence()
            .filter { it.startsWith(VARIABLE_EXTRA_PREFIX) }
            .sorted()
            .mapNotNull { key ->
                val name = key.removePrefix(VARIABLE_EXTRA_PREFIX)
                if (!isValidVariableName(name)) return@mapNotNull null
                val value = values[key] ?: return@mapNotNull null
                name to value.take(MAX_VARIABLE_VALUE_CHARS)
            }
            .take(MAX_SUPPLIED_VARIABLES)
            .toMap()
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
                    AutomationTargetContract.ACTION_QUERY_EXECUTION -> queryExecution(context.applicationContext, intent)
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

    /**
     * Validates and hands the run to the foreground service, then returns immediately.
     *
     * The receiver never waits for the task: a run that outlives the broadcast window would be
     * killed mid-task with no run-log entry, and returning its "success" from inside that window
     * meant returning a result that had not happened yet.
     */
    private suspend fun runTask(appContext: Context, intent: Intent): TargetResponse {
        // Fork: a sister-app intent does not wake an app the user has shut down from the overflow
        // menu. Checked before the protocol handshake, so a stopped app answers "I am stopped"
        // rather than a version complaint the caller cannot act on.
        if (EngineShutdown.refuse(appContext, "external intent (run task)")) {
            return failure("白い熊 自由作業盤 is stopped — open the app to start it again.")
        }
        val requestedVersion = intent.getIntExtra(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, 0)
        if (requestedVersion != AutomationTargetContract.PROTOCOL_VERSION) {
            return failure(
                "RUN_TASK requires ${AutomationTargetContract.EXTRA_PROTOCOL_VERSION}=" +
                    "${AutomationTargetContract.PROTOCOL_VERSION}. Runs are asynchronous: the reply " +
                    "carries an execution id to poll with ${AutomationTargetContract.ACTION_QUERY_EXECUTION}, " +
                    "not a task result.",
                extras = Bundle().apply {
                    putInt(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, AutomationTargetContract.PROTOCOL_VERSION)
                },
            )
        }

        val task = resolveTask(intent)
            ?: return failure("Task not found. Provide ${AutomationTargetContract.EXTRA_TASK_ID} or ${AutomationTargetContract.EXTRA_TASK_NAME}.")

        val suppliedVariables = AutomationTargetContract.extractVariableExtras(intent.extras)
        val runSource = AutomationTargetContract.runSourceLabel(
            intent.getStringExtra(AutomationTargetContract.EXTRA_RUN_SOURCE),
        )
        val executionId = ExternalExecutions.accept(appContext, task.id, task.name)

        val serviceIntent = Intent(appContext, AutomationService::class.java).apply {
            action = AutomationService.ACTION_RUN_EXTERNAL_TASK
            putExtra(AutomationTargetContract.EXTRA_EXECUTION_ID, executionId)
            putExtra(AutomationTargetContract.EXTRA_TASK_ID, task.id)
            putExtra(AutomationTargetContract.EXTRA_RUN_SOURCE, runSource)
            suppliedVariables.forEach { (name, value) ->
                putExtra(AutomationTargetContract.variableExtraName(name), value)
            }
        }
        return try {
            ContextCompat.startForegroundService(appContext, serviceIntent)
            TargetResponse(
                Activity.RESULT_OK,
                Bundle().apply {
                    putInt(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, AutomationTargetContract.PROTOCOL_VERSION)
                    putBoolean(AutomationTargetContract.EXTRA_ACCEPTED, true)
                    putString(AutomationTargetContract.EXTRA_EXECUTION_ID, executionId)
                    putString(
                        AutomationTargetContract.EXTRA_EXECUTION_STATE,
                        ExternalExecutionState.ACCEPTED.name,
                    )
                    putBoolean(AutomationTargetContract.EXTRA_EXECUTION_TERMINAL, false)
                },
            )
        } catch (e: Exception) {
            ExternalExecutions.update(
                appContext,
                executionId,
                ExternalExecutionState.FAILED,
                error = "Engine service could not be started",
            )
            failure("Engine service could not be started: ${e.message}")
        }
    }

    private fun queryExecution(appContext: Context, intent: Intent): TargetResponse {
        val executionId = intent.getStringExtra(AutomationTargetContract.EXTRA_EXECUTION_ID)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return failure("Provide ${AutomationTargetContract.EXTRA_EXECUTION_ID}.")

        val record = ExternalExecutions.get(appContext, executionId)
        val state = record?.state ?: ExternalExecutionState.UNKNOWN
        return TargetResponse(
            // An unknown id is a caller error, not a task failure; a still-running execution is a
            // valid answer, so only a genuinely failed run reports CANCELED.
            if (state == ExternalExecutionState.UNKNOWN || state == ExternalExecutionState.FAILED) {
                Activity.RESULT_CANCELED
            } else {
                Activity.RESULT_OK
            },
            Bundle().apply {
                putInt(AutomationTargetContract.EXTRA_PROTOCOL_VERSION, AutomationTargetContract.PROTOCOL_VERSION)
                putString(AutomationTargetContract.EXTRA_EXECUTION_ID, executionId)
                putString(AutomationTargetContract.EXTRA_EXECUTION_STATE, state.name)
                putBoolean(AutomationTargetContract.EXTRA_EXECUTION_TERMINAL, state.isTerminal)
                record?.let {
                    putLong(AutomationTargetContract.EXTRA_TASK_ID, it.taskId)
                    putLong(AutomationTargetContract.EXTRA_TASK_DURATION_MS, it.durationMs)
                    putBoolean(
                        AutomationTargetContract.EXTRA_TASK_SUCCESS,
                        it.state == ExternalExecutionState.SUCCEEDED,
                    )
                    it.error?.let { error -> putString(AutomationTargetContract.EXTRA_ERROR, error) }
                }
                if (record == null) {
                    putString(
                        AutomationTargetContract.EXTRA_ERROR,
                        "Unknown execution id (never issued, or aged out of the retained results).",
                    )
                }
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
                // Fork: deliberately NOT gated — "are you stopped?" has to be answerable precisely
                // when the answer is yes, so a caller can dim its shortcuts instead of firing.
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

    private fun failure(message: String, extras: Bundle = Bundle()): TargetResponse {
        AppLogger.warn(TAG, message)
        return TargetResponse(
            Activity.RESULT_CANCELED,
            extras.apply { putString(AutomationTargetContract.EXTRA_ERROR, message) },
        )
    }

    companion object {
        private const val TAG = "AutomationTargetReceiver"
    }
}

private data class TargetResponse(
    val resultCode: Int,
    val extras: Bundle,
)
