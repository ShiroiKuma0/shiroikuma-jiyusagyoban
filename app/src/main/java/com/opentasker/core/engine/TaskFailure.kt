package com.opentasker.core.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The machine-readable description of the action failure that stopped a task.
 *
 * [actionIndex] is one-based because it is presented to people in the run log and fallback task.
 * An id of zero means the action came from an older/imported task that did not assign a stable
 * action id; the type and index are still always present.
 */
@Serializable
data class StructuredTaskError(
    val taskId: Long,
    val taskName: String,
    val actionId: Long,
    val actionIndex: Int,
    val actionType: String,
    val message: String,
    val attemptCount: Int,
    val originatingProfileId: Long? = null,
    val originatingProfileName: String? = null,
)

/** Stable variable names exposed to flow.catch and fallback tasks. */
object TaskFailureVariables {
    const val JSON = "FLOW_ERROR_JSON"
    const val TASK_ID = "FLOW_ERROR_TASK_ID"
    const val TASK_NAME = "FLOW_ERROR_TASK_NAME"
    const val ACTION_ID = "FLOW_ERROR_ACTION_ID"
    const val ACTION = "FLOW_ERROR_ACTION"
    const val ACTION_INDEX = "FLOW_ERROR_INDEX"
    const val ACTION_TYPE = "FLOW_ERROR_TYPE"
    const val MESSAGE = "FLOW_ERROR_MESSAGE"
    const val ATTEMPT = "FLOW_ERROR_ATTEMPT"
    const val RETRYING = "FLOW_ERROR_RETRYING"
    const val RETRY_REASON = "FLOW_ERROR_RETRY_REASON"
    const val ORIGINATING_PROFILE_ID = "FLOW_ERROR_PROFILE_ID"
    const val ORIGINATING_PROFILE_NAME = "FLOW_ERROR_PROFILE_NAME"
    const val CAUGHT = "FLOW_ERROR_CAUGHT"
}

/** Bounded JSON transport for passing the error object into a user-authored fallback task. */
object StructuredTaskErrorCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(error: StructuredTaskError): String = json.encodeToString(error.bounded())

    private fun StructuredTaskError.bounded(): StructuredTaskError = copy(
        taskName = taskName.take(MAX_ERROR_NAME_LENGTH),
        actionType = actionType.take(MAX_ERROR_NAME_LENGTH),
        message = message.take(MAX_ERROR_MESSAGE_LENGTH),
        originatingProfileName = originatingProfileName?.take(MAX_ERROR_NAME_LENGTH),
        attemptCount = attemptCount.coerceAtLeast(1),
    )
}

/** Initial variable payload for a user-authored fallback task. */
fun StructuredTaskError.toFailureVariables(): Map<String, String> = mapOf(
    TaskFailureVariables.JSON to StructuredTaskErrorCodec.encode(this),
    TaskFailureVariables.TASK_ID to taskId.toString(),
    TaskFailureVariables.TASK_NAME to taskName,
    TaskFailureVariables.ACTION_ID to actionId.toString(),
    TaskFailureVariables.ACTION to actionType,
    TaskFailureVariables.ACTION_INDEX to actionIndex.toString(),
    TaskFailureVariables.ACTION_TYPE to actionType,
    TaskFailureVariables.MESSAGE to message,
    TaskFailureVariables.ATTEMPT to attemptCount.toString(),
    TaskFailureVariables.RETRYING to "false",
    TaskFailureVariables.RETRY_REASON to "",
    TaskFailureVariables.ORIGINATING_PROFILE_ID to originatingProfileId?.toString().orEmpty(),
    TaskFailureVariables.ORIGINATING_PROFILE_NAME to originatingProfileName.orEmpty(),
    TaskFailureVariables.CAUGHT to "false",
)

private const val MAX_ERROR_NAME_LENGTH = 160
private const val MAX_ERROR_MESSAGE_LENGTH = 2_048
