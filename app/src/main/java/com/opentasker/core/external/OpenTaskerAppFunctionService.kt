package com.opentasker.core.external

import android.app.appfunctions.AppFunctionException
import android.app.appfunctions.AppFunctionService
import android.app.appfunctions.ExecuteAppFunctionRequest
import android.app.appfunctions.ExecuteAppFunctionResponse
import android.app.appsearch.GenericDocument
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import com.opentasker.core.engine.DirectBootTriggerStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Platform-only AppFunctions adapter.
 *
 * The platform's metadata indexer keeps the function disabled by default. The second policy gate
 * below is deliberate defense in depth: even if a system caller enables the metadata entry, an
 * execution still needs the app's user-mediated approval and can only carry a task id.
 */
@RequiresApi(AppFunctionPrototypeContract.MIN_API)
class OpenTaskerAppFunctionService : AppFunctionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onExecuteFunction(
        request: ExecuteAppFunctionRequest,
        callingPackage: String,
        _signingInfo: SigningInfo,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<ExecuteAppFunctionResponse, AppFunctionException>,
    ) {
        if (Build.VERSION.SDK_INT < AppFunctionPrototypeContract.MIN_API) {
            callback.onError(
                AppFunctionException(
                    AppFunctionException.ERROR_FUNCTION_NOT_FOUND,
                    AppFunctionSupport.unsupportedMessage(Build.VERSION.SDK_INT),
                ),
            )
            return
        }

        val permissionGranted = checkCallingPermission(
            AppFunctionPrototypeContract.EXECUTE_APP_FUNCTIONS_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!AppFunctionCallerPolicy.isTrustedCaller(permissionGranted, callingPackage, packageName)) {
            callback.onError(
                AppFunctionException(
                    AppFunctionException.ERROR_DENIED,
                    "The AppFunction caller is not trusted.",
                ),
            )
            return
        }

        if (request.targetPackageName != packageName) {
            callback.onError(
                AppFunctionException(
                    AppFunctionException.ERROR_INVALID_ARGUMENT,
                    "The request target does not match this application.",
                ),
            )
            return
        }

        if (request.functionIdentifier != AppFunctionPrototypeContract.FUNCTION_ID_RUN_APPROVED_TASK) {
            callback.onError(
                AppFunctionException(
                    AppFunctionException.ERROR_FUNCTION_NOT_FOUND,
                    "The requested AppFunction is not exposed.",
                ),
            )
            return
        }

        val taskId = parseTaskId(request)
        if (taskId == null) {
            callback.onError(
                AppFunctionException(
                    AppFunctionException.ERROR_INVALID_ARGUMENT,
                    "Only one positive task_id argument is accepted.",
                ),
            )
            return
        }

        val policy = AppFunctionPrototypePolicyStore(this)
        when (AppFunctionExecutionPolicy.evaluate(policy.isEnabled, policy.approvedTaskIds, taskId)) {
            AppFunctionExecutionGate.ALLOWED -> Unit
            AppFunctionExecutionGate.DISABLED -> {
                callback.onError(
                    AppFunctionException(
                        AppFunctionException.ERROR_DISABLED,
                        "AppFunction execution is disabled until the user enables it.",
                    ),
                )
                return
            }
            AppFunctionExecutionGate.TASK_NOT_APPROVED -> {
                callback.onError(
                    AppFunctionException(
                        AppFunctionException.ERROR_DENIED,
                        "The task requires explicit in-app approval.",
                    ),
                )
                return
            }
            AppFunctionExecutionGate.INVALID_TASK_ID -> {
                callback.onError(
                    AppFunctionException(
                        AppFunctionException.ERROR_INVALID_ARGUMENT,
                        "The task id is invalid.",
                    ),
                )
                return
            }
        }

        val completed = AtomicBoolean(false)
        fun reportError(code: Int, message: String) {
            if (completed.compareAndSet(false, true)) {
                callback.onError(AppFunctionException(code, message))
            }
        }

        val job = serviceScope.launch {
            try {
                if (cancellationSignal.isCanceled) throw CancellationException()
                if (!DirectBootTriggerStore.isUserUnlocked(applicationContext)) {
                    reportError(
                        AppFunctionException.ERROR_DISABLED,
                        "Unlock the device before running an approved task.",
                    )
                    return@launch
                }

                val executionId = UUID.randomUUID().toString()
                val taskIntent = AutomationTargetContract.internalRunTaskIntent(
                    context = applicationContext,
                    taskId = taskId,
                    source = InternalTaskRunSource.APP_FUNCTION,
                    executionId = executionId,
                )
                sendBroadcast(taskIntent, AutomationTargetContract.PERMISSION)

                if (completed.compareAndSet(false, true)) {
                    callback.onResult(buildAcceptedResponse(executionId))
                }
            } catch (_: CancellationException) {
                reportError(
                    AppFunctionException.ERROR_CANCELLED,
                    "The AppFunction request was cancelled.",
                )
            } catch (_: Exception) {
                reportError(
                    AppFunctionException.ERROR_APP_UNKNOWN_ERROR,
                    "The approved task could not be submitted.",
                )
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun parseTaskId(request: ExecuteAppFunctionRequest): Long? {
        val parameters = request.parameters
        if (parameters.getPropertyNames() != setOf(AppFunctionPrototypeContract.PARAMETER_TASK_ID)) {
            return null
        }
        val taskIds = parameters.getPropertyLongArray(AppFunctionPrototypeContract.PARAMETER_TASK_ID)
        if (taskIds == null || taskIds.size != 1 || taskIds[0] <= 0L) return null
        return taskIds[0]
    }

    private fun buildAcceptedResponse(executionId: String): ExecuteAppFunctionResponse {
        val resultJson = buildString {
            append('{')
            append('"').append(AppFunctionPrototypeContract.RESULT_SCHEMA_VERSION).append("\":")
            append(AppFunctionPrototypeContract.SCHEMA_VERSION)
            append(",\"").append(AppFunctionPrototypeContract.RESULT_STATUS).append("\":\"")
            append(AppFunctionPrototypeContract.RESULT_STATUS_ACCEPTED).append("\"")
            append(",\"").append(AppFunctionPrototypeContract.RESULT_CAPABILITY_ID).append("\":\"")
            append(AppFunctionPrototypeContract.FUNCTION_ID_RUN_APPROVED_TASK).append("\"")
            append(",\"").append(AppFunctionPrototypeContract.RESULT_EXECUTION_ID).append("\":\"")
            append(executionId).append("\"}")
        }
        val resultDocument = GenericDocument.Builder<GenericDocument.Builder<*>>(
            packageName,
            "app_function_result",
            "com.opentasker.AppFunctionResult",
        )
            .setPropertyString(ExecuteAppFunctionResponse.PROPERTY_RETURN_VALUE, resultJson)
            .build()
        return ExecuteAppFunctionResponse(resultDocument)
    }
}
