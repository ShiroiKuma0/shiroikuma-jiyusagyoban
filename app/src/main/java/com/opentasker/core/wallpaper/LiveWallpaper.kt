package com.opentasker.core.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.opentasker.app.BuildConfig
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Sets the live wallpaper, through Shizuku when it can and through the system's own preview screen
 * when it cannot.
 *
 * The privileged path is the whole point: `shell` holds SET_WALLPAPER_COMPONENT, so a Shizuku
 * UserService can set the wallpaper outright. Without Shizuku the only route an app has is
 * ACTION_CHANGE_LIVE_WALLPAPER — the preview with a confirm button — which still beats nothing and is
 * what the fallback opens.
 */
object LiveWallpaper {

    private const val TAG = "LiveWallpaper"
    private const val BIND_TIMEOUT_MS = 8_000L

    /** What happened, in a form the action can report verbatim. */
    sealed interface Outcome {
        /** Set outright; nothing appeared on screen. */
        data object Set : Outcome

        /** The preview was opened and 白い熊 has to confirm. [reason] says why it came to that. */
        data class NeedsConfirm(val reason: String) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    suspend fun set(context: Context, packageName: String, className: String): Outcome {
        val app = context.applicationContext
        val component = ComponentName(packageName, className)

        if (ShizukuShell.available()) {
            val bridge = bind(app)
            if (bridge != null) {
                val error = runCatching { bridge.setLiveWallpaper(packageName, className) }
                    .getOrElse { "bridge call failed: ${it.message}" }
                if (error.isEmpty()) {
                    AppLogger.info(TAG, "live wallpaper set to $component")
                    return Outcome.Set
                }
                AppLogger.warn(TAG, "privileged set failed ($error); falling back to the picker")
                return openPreview(app, component, "privileged set failed: $error")
            }
            return openPreview(app, component, "the Shizuku bridge did not bind")
        }
        return openPreview(app, component, "Shizuku is not available")
    }

    /** The public route: the system preview, where the wallpaper is applied by a confirming tap. */
    private fun openPreview(context: Context, component: ComponentName, reason: String): Outcome {
        val intent = Intent(WallpaperConstants.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperConstants.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            Outcome.NeedsConfirm(reason)
        }.getOrElse { Outcome.Failed("$reason, and the wallpaper picker would not open: ${it.message}") }
    }

    @Volatile
    private var service: IWallpaperBridge? = null

    private fun userServiceArgs(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, WallpaperBridgeService::class.java.name))
            .daemon(false)
            .processNameSuffix("wallbridge")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private suspend fun bind(context: Context): IWallpaperBridge? {
        service?.let { return it }
        val ready = CompletableDeferred<IWallpaperBridge?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = binder?.takeIf { it.pingBinder() }?.let(IWallpaperBridge.Stub::asInterface)
                service = bound
                if (!ready.isCompleted) ready.complete(bound)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val started = runCatching { Shizuku.bindUserService(userServiceArgs(context.applicationContext), connection) }
            .onFailure { AppLogger.warn(TAG, "bindUserService failed: ${it.message}") }
            .isSuccess
        if (!started) return null
        // Bound per call, like the telephony bridge: a standing privileged process for something used
        // once in a while is surface for nothing.
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
    }
}

/** The two framework constants involved, which are public API but not on WallpaperManager's face. */
private object WallpaperConstants {
    const val ACTION_CHANGE_LIVE_WALLPAPER = "android.service.wallpaper.CHANGE_LIVE_WALLPAPER"
    const val EXTRA_LIVE_WALLPAPER_COMPONENT = "android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT"
}
