package com.opentasker.core.contexts

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/** Bridges Android 15 screen-recording visibility callbacks into event-context pulses. */
object ScreenRecordingContextEvents {
    const val EVENT_SCREEN_RECORDING = "screen_recording"
    const val STATE_VISIBLE = "visible"
    const val STATE_NOT_VISIBLE = "not_visible"

    private val events_ = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    @Volatile private var windowManager: WindowManager? = null
    @Volatile private var callback: Consumer<Int>? = null
    private val started = AtomicBoolean(false)

    /** Starts the API 35 callback, or succeeds as a no-op on older Android releases. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_15_API) return true
        if (!started.compareAndSet(false, true)) return true

        val manager = context.getSystemService(WindowManager::class.java)
        if (manager == null) {
            started.set(false)
            return false
        }
        val stateCallback = Consumer<Int> { state ->
            events_.tryEmit(buildEvent(state))
        }
        return try {
            register(manager, context.mainExecutor, stateCallback)
            windowManager = manager
            callback = stateCallback
            true
        } catch (_: RuntimeException) {
            started.set(false)
            false
        }
    }

    @Synchronized
    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < ANDROID_15_API) return
        if (!started.compareAndSet(true, false)) return

        val manager = windowManager ?: context.getSystemService(WindowManager::class.java)
        callback?.let { stateCallback ->
            if (manager != null) {
                runCatching { unregister(manager, stateCallback) }
            }
        }
        windowManager = null
        callback = null
    }

    fun buildEvent(isVisible: Boolean): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to EVENT_SCREEN_RECORDING,
            "state" to if (isVisible) STATE_VISIBLE else STATE_NOT_VISIBLE,
            "recording" to isVisible.toString(),
        ),
    )

    @RequiresApi(35)
    fun buildEvent(state: Int): ContextEvent = buildEvent(
        isVisible = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE,
    )

    @RequiresApi(35)
    private fun register(
        manager: WindowManager,
        executor: java.util.concurrent.Executor,
        stateCallback: Consumer<Int>,
    ) {
        manager.addScreenRecordingCallback(executor, stateCallback)
    }

    @RequiresApi(35)
    private fun unregister(manager: WindowManager, stateCallback: Consumer<Int>) {
        manager.removeScreenRecordingCallback(stateCallback)
    }

    private const val ANDROID_15_API = 35
}
