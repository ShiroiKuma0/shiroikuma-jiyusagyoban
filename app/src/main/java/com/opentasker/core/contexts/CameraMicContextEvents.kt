package com.opentasker.core.contexts

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

object CameraMicContextEvents {
    private val events = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 16)
    val flow: SharedFlow<ContextEvent> = events.asSharedFlow()

    @Volatile private var cameraCallback: AppOpsManager.OnOpActiveChangedListener? = null
    @Volatile private var micCallback: AppOpsManager.OnOpActiveChangedListener? = null
    private val started = AtomicBoolean(false)

    // start/stop are synchronized so an interleaved stop cannot observe started=true
    // with the callback fields still unassigned — that would leak both AppOps watchers
    // and register a duplicate pair on the next start.
    @Synchronized
    fun start(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (!started.compareAndSet(false, true)) return true

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps == null) {
            started.set(false)
            return false
        }

        val camCb = AppOpsManager.OnOpActiveChangedListener { _, _, packageName, active ->
            events.tryEmitPulse("camera/mic", 
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
            events.tryEmitPulse("camera/mic", 
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

        return try {
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
            true
        } catch (error: RuntimeException) {
            runCatching { appOps.stopWatchingActive(camCb) }
            runCatching { appOps.stopWatchingActive(micCb) }
            started.set(false)
            false
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!started.compareAndSet(true, false)) return
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
