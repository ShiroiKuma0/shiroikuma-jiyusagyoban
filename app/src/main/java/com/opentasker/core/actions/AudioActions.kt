package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Start a voice recording (AAC/m4a). Args:
 *   - "dir": output directory (e.g. `%PKEY_DIR`); blank → app-external Recordings.
 * No-op if a recording is already in progress. The file is finalized by [AudioRecordStopAction].
 */
class AudioRecordStartAction : Action {
    override val id = "audio.record.start"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val dir = args["dir"].orEmpty()
        return withContext(Dispatchers.IO) {
            AudioRecorderManager.start(ctx.app, dir).fold(
                onSuccess = {
                    ctx.logger(if (AudioRecorderManager.isRecording) "Recording started" else "Recording (no-op)")
                    ActionResult.Success
                },
                onFailure = { ActionResult.Failure("record start failed: ${it.message}") },
            )
        }
    }
}

/**
 * Stop the in-progress voice recording and save it to the configured directory. Exposes the saved
 * path as the `path` return value. A no-op (Skip) when nothing is recording.
 */
class AudioRecordStopAction : Action {
    override val id = "audio.record.stop"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        return withContext(Dispatchers.IO) {
            AudioRecorderManager.stop(ctx.app).fold(
                onSuccess = { path ->
                    ctx.returns["path"] = path
                    ctx.logger("Recording saved: $path")
                    ActionResult.Success
                },
                onFailure = {
                    if (it.message == "not recording") ActionResult.Skip
                    else ActionResult.Failure("record stop failed: ${it.message}")
                },
            )
        }
    }
}
