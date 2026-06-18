package com.opentasker.core.contexts

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object CameraMicContextEvents {
    private val events = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 16)
    val flow: SharedFlow<ContextEvent> = events.asSharedFlow()

    @Volatile private var cameraCallback: AppOpsManager.OnOpActiveChangedListener? = null
    @Volatile private var micCallback: AppOpsManager.OnOpActiveChangedListener? = null

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return

        stop(appOps)

        val camCb = AppOpsManager.OnOpActiveChangedListener { _, _, packageName, active ->
            events.tryEmit(
                ContextEvent(
                    type = "event",
                    matched = true,
                    metadata = mapOf(
                        "event" to "camera",
                        "active" to active.toString(),
                        "package" to packageName,
                    ),
                ),
            )
        }

        val micCb = AppOpsManager.OnOpActiveChangedListener { _, _, packageName, active ->
            events.tryEmit(
                ContextEvent(
                    type = "event",
                    matched = true,
                    metadata = mapOf(
                        "event" to "mic",
                        "active" to active.toString(),
                        "package" to packageName,
                    ),
                ),
            )
        }

        appOps.startWatchingActive(
            arrayOf(AppOpsManager.OPSTR_CAMERA),
            context.mainExecutor,
            camCb,
        )
        appOps.startWatchingActive(
            arrayOf(AppOpsManager.OPSTR_RECORD_AUDIO),
            context.mainExecutor,
            micCb,
        )

        cameraCallback = camCb
        micCallback = micCb
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
        stop(appOps)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun stop(appOps: AppOpsManager) {
        cameraCallback?.let { appOps.stopWatchingActive(it) }
        micCallback?.let { appOps.stopWatchingActive(it) }
        cameraCallback = null
        micCallback = null
    }
}
