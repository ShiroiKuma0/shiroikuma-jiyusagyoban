package com.opentasker.core.scenes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import kotlinx.serialization.json.Json

/**
 * Foreground service that displays a [Scene] as a draggable floating overlay
 * using [WindowManager] with [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 */
class SceneOverlayService : Service() {

    private var overlayView: View? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            AppLogger.warn(TAG, message = "Received null intent, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            AppLogger.error(TAG, message = "Overlay permission not granted, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val sceneJson = intent.getStringExtra(EXTRA_SCENE_JSON)
        if (sceneJson == null) {
            AppLogger.error(TAG, message = "Missing scene JSON extra, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val scene = try {
            Json.decodeFromString<Scene>(sceneJson)
        } catch (e: Exception) {
            AppLogger.error(TAG, message = "Failed to deserialize scene", throwable = e)
            stopSelf()
            return START_NOT_STICKY
        }

        AppLogger.info(TAG, message = "Showing scene overlay: ${scene.name} (id=${scene.id})")

        // Remove any existing overlay before showing a new one
        removeOverlay()
        showOverlay(scene)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun showOverlay(scene: Scene) {
        val density = resources.displayMetrics.density
        val widthPx = (scene.widthDp * density).toInt()
        val heightPx = (scene.heightDp * density).toInt()

        val headerHeightPx = (HEADER_HEIGHT_DP * density).toInt()
        val closeButtonSizePx = (CLOSE_BUTTON_SIZE_DP * density).toInt()

        // Root container: header + scene content
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(OVERLAY_BACKGROUND)
        }

        // Header bar (draggable area + close button)
        val header = FrameLayout(this).apply {
            setBackgroundColor(HEADER_BACKGROUND)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                headerHeightPx,
            )
        }

        val closeButton = TextView(this).apply {
            text = CLOSE_LABEL
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(closeButtonSizePx, closeButtonSizePx).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { stopSelf() }
        }
        header.addView(closeButton)
        root.addView(header)

        // Scene content area
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val padPx = (4 * density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }

        for (element in scene.elements) {
            val view = buildElementView(element)
            content.addView(view)
        }
        root.addView(content)

        // WindowManager layout params
        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        // Dragging via the header
        setupDrag(header, params)

        windowManager.addView(root, params)
        overlayView = root
    }

    private fun buildElementView(element: SceneElement): View {
        return when (element.type) {
            SceneElementType.BUTTON -> Button(this).apply {
                text = element.config["label"] ?: "Button"
                setOnClickListener {
                    element.tapTaskId?.let { taskId -> fireRunTask(taskId) }
                }
                element.longPressTaskId?.let { longTaskId ->
                    setOnLongClickListener {
                        fireRunTask(longTaskId)
                        true
                    }
                }
            }

            SceneElementType.TEXT -> TextView(this).apply {
                text = element.config["text"] ?: ""
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }

            SceneElementType.SLIDER -> SeekBar(this).apply {
                val minVal = element.config["min"]?.toIntOrNull() ?: 0
                val maxVal = element.config["max"]?.toIntOrNull() ?: 100
                val progressVal = element.config["progress"]?.toIntOrNull() ?: minVal
                min = minVal
                max = maxVal
                progress = progressVal.coerceIn(minVal, maxVal)
            }

            else -> TextView(this).apply {
                text = "[${element.type.name}]"
                setTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val padPx = (2 * resources.displayMetrics.density).toInt()
                setPadding(padPx, padPx, padPx, padPx)
            }
        }
    }

    private fun setupDrag(dragHandle: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun fireRunTask(taskId: Long) {
        AppLogger.info(TAG, message = "Scene element firing task $taskId")
        val intent = Intent(AutomationTargetContract.ACTION_RUN_TASK).apply {
            setPackage(packageName)
            putExtra(AutomationTargetContract.EXTRA_TASK_ID, taskId)
        }
        sendBroadcast(intent, AutomationTargetContract.PERMISSION)
    }

    private fun removeOverlay() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
                .onFailure { e -> AppLogger.warn(TAG, message = "Failed to remove overlay view", throwable = e) }
            overlayView = null
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Scene overlay active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "SceneOverlayService"

        const val EXTRA_SCENE_ID = "com.opentasker.extra.SCENE_ID"
        const val EXTRA_SCENE_JSON = "com.opentasker.extra.SCENE_JSON"
        const val CHANNEL_ID = "opentasker.scenes"
        const val CHANNEL_NAME = "Scene overlays"
        const val NOTIFICATION_ID = 1002

        private const val HEADER_HEIGHT_DP = 28
        private const val CLOSE_BUTTON_SIZE_DP = 28
        private const val CLOSE_LABEL = "✕"
        private const val OVERLAY_BACKGROUND = 0xE0_1E_1E_2E.toInt()  // Catppuccin Mocha base ~88% alpha
        private const val HEADER_BACKGROUND = 0xFF_31_32_44.toInt()   // Catppuccin Mocha surface0

        /** Start the overlay service, displaying the given [scene]. */
        fun show(context: Context, scene: Scene) {
            if (!Settings.canDrawOverlays(context)) {
                AppLogger.warn(TAG, message = "Cannot show scene overlay: overlay permission not granted")
                return
            }
            val intent = Intent(context, SceneOverlayService::class.java).apply {
                putExtra(EXTRA_SCENE_ID, scene.id)
                putExtra(EXTRA_SCENE_JSON, Json.encodeToString(Scene.serializer(), scene))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Dismiss the current scene overlay. */
        fun dismiss(context: Context) {
            context.stopService(Intent(context, SceneOverlayService::class.java))
        }
    }
}
