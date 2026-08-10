package com.opentasker.core.actions

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.StorageJson
import java.util.concurrent.TimeUnit

/**
 * Applies a supported reversible setting for a bounded duration and restores the value that was
 * present immediately before the action ran. The pending restore is a unique WorkManager job, so
 * it survives process death and remains visible through WorkManager inspection.
 *
 * Required args:
 *   - target_action: brightness.set, volume.set, ringer.set, or dnd.set
 *   - target_args: JSON object containing the target action's arguments
 *   - key: stable channel name; a later temporary state on the same key replaces the old timer
 *   - duration_sec: 1..604800 seconds
 */
class TemporaryStateAction : DeclaredAction(ActionCatalog.require(ACTION_ID)) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val plan = TemporaryStatePlan.parse(args).getOrElse { return ActionResult.Failure(it.message ?: "invalid temporary state") }
        val target = TemporaryStateTarget.forAction(plan.targetAction)
            ?: return ActionResult.Failure("temporary state does not support ${plan.targetAction}")
        val targetAction = ActionRegistry.get(plan.targetAction)
            ?: return ActionResult.Failure("unknown target action: ${plan.targetAction}")
        val restoreArgs = target.capture(ctx.app, plan.targetAction, plan.targetArgs)
            ?: return ActionResult.Failure("current ${plan.targetAction} state is unavailable")

        when (val result = targetAction.run(ctx, plan.targetArgs)) {
            ActionResult.Success -> {
                TemporaryStateScheduler.enqueue(
                    context = ctx.app,
                    key = plan.key,
                    targetAction = plan.targetAction,
                    restoreArgs = restoreArgs,
                    durationSec = plan.durationSec,
                )
                ctx.logger("Temporary ${plan.targetAction} state scheduled for ${plan.durationSec}s")
                return ActionResult.Success
            }
            ActionResult.Skip -> return ActionResult.Skip
            is ActionResult.Failure -> return result
        }
    }

    companion object {
        const val ACTION_ID = "state.temporary"
    }
}

internal data class TemporaryStatePlan(
    val targetAction: String,
    val targetArgs: Map<String, String>,
    val key: String,
    val durationSec: Long,
) {
    companion object {
        private const val MAX_TARGET_ARGS = 4_096
        private const val MAX_KEY_LENGTH = 80
        private const val MAX_ARGUMENT_COUNT = 12

        fun parse(args: Map<String, String>): Result<TemporaryStatePlan> = runCatching {
            val targetAction = args["target_action"]?.trim().orEmpty()
            require(targetAction in TemporaryStateTarget.SUPPORTED_ACTIONS) {
                "target_action must be one of ${TemporaryStateTarget.SUPPORTED_ACTIONS.joinToString() }"
            }
            val rawTargetArgs = args["target_args"]?.trim().orEmpty()
            require(rawTargetArgs.length in 2..MAX_TARGET_ARGS) { "target_args must be a bounded JSON object" }
            val targetArgs = StorageJson.decodeFromString<Map<String, String>>(rawTargetArgs)
            require(targetArgs.size <= MAX_ARGUMENT_COUNT) { "target_args has too many fields" }
            require(targetArgs.keys.all { it.length in 1..64 && it.all { char -> char.isLetterOrDigit() || char == '_' } }) {
                "target_args contains an invalid field name"
            }
            require(targetArgs.values.all { it.length <= 512 }) { "target_args contains an oversized value" }
            val key = args["key"]?.trim().orEmpty()
            require(key.length in 1..MAX_KEY_LENGTH && key.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                "key must be a bounded identifier"
            }
            val durationSec = args["duration_sec"]?.toLongOrNull()
                ?: error("duration_sec must be an integer")
            require(durationSec in 1..MAX_DURATION_SEC) { "duration_sec must be between 1 and $MAX_DURATION_SEC" }
            TemporaryStatePlan(targetAction, targetArgs, key, durationSec)
        }

        const val MAX_DURATION_SEC = 7 * 24 * 60 * 60L
    }
}

internal object TemporaryStateTarget {
    val SUPPORTED_ACTIONS = setOf("brightness.set", "volume.set", "ringer.set", "dnd.set")

    fun forAction(actionId: String): TemporaryStateTarget? =
        actionId.takeIf { it in SUPPORTED_ACTIONS }?.let { this }

    /**
     * [args] carries the target action's own arguments, so the action id must be passed
     * separately; there is deliberately no overload that reads it back out of [args].
     */
    fun capture(context: Context, actionId: String, args: Map<String, String>): Map<String, String>? = when (actionId) {
        "brightness.set" -> captureBrightness(context)
        "volume.set" -> captureVolume(context, args["stream"] ?: "music")
        "ringer.set" -> captureRinger(context)
        "dnd.set" -> captureDnd(context)
        else -> null
    }

    private fun captureBrightness(context: Context): Map<String, String>? {
        val resolver = context.contentResolver
        val mode = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) return mapOf("brightness" to "auto")
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return brightness.takeIf { it >= 0 }?.let { mapOf("brightness" to it.toString()) }
    }

    private fun captureVolume(context: Context, stream: String): Map<String, String>? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val streamType = streamType(stream) ?: return null
        return mapOf("stream" to stream, "level" to audio.getStreamVolume(streamType).toString())
    }

    private fun captureRinger(context: Context): Map<String, String>? {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val mode = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_SILENT -> "silent"
            else -> return null
        }
        return mapOf("mode" to mode)
    }

    private fun captureDnd(context: Context): Map<String, String>? {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return null
        val mode = when (manager.currentInterruptionFilter) {
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
            else -> return null
        }
        return mapOf("mode" to mode)
    }

    private fun streamType(value: String): Int? = when (value.lowercase()) {
        "music" -> AudioManager.STREAM_MUSIC
        "alarm" -> AudioManager.STREAM_ALARM
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        "voice" -> AudioManager.STREAM_VOICE_CALL
        else -> null
    }
}

internal object TemporaryStateScheduler {
    private const val WORK_PREFIX = "temporary_state_revert_"
    const val INPUT_TARGET_ACTION = "target_action"
    const val INPUT_RESTORE_ARGS = "restore_args"

    fun workName(key: String): String = WORK_PREFIX + key

    fun enqueue(
        context: Context,
        key: String,
        targetAction: String,
        restoreArgs: Map<String, String>,
        durationSec: Long,
    ) {
        val input = workDataOf(
            INPUT_TARGET_ACTION to targetAction,
            INPUT_RESTORE_ARGS to StorageJson.encodeToString(restoreArgs),
        )
        val request = OneTimeWorkRequestBuilder<TemporaryStateRevertWorker>()
            .setInputData(input)
            .setInitialDelay(durationSec, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(key),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun inspect(context: Context, key: String): WorkInfo? =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName(key)).get().firstOrNull()
}

class TemporaryStateRevertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val targetActionId = inputData.getString(TemporaryStateScheduler.INPUT_TARGET_ACTION)
            ?: return Result.failure()
        val restoreJson = inputData.getString(TemporaryStateScheduler.INPUT_RESTORE_ARGS)
            ?: return Result.failure()
        val restoreArgs = runCatching { StorageJson.decodeFromString<Map<String, String>>(restoreJson) }
            .getOrElse { return Result.failure() }
        val action = ActionRegistry.get(targetActionId) ?: return Result.failure()
        return when (val result = action.run(ActionContext(applicationContext, VariableStore(), logger = { AppLogger.info(TAG, it) }), restoreArgs)) {
            ActionResult.Success, ActionResult.Skip -> Result.success()
            is ActionResult.Failure -> {
                AppLogger.error(TAG, "Temporary state restore failed: ${result.message}")
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure(Data.Builder().putString("error", result.message).build())
            }
        }
    }

    companion object {
        private const val TAG = "TemporaryStateRevert"
        private const val MAX_RETRY_ATTEMPTS = 2
    }
}
