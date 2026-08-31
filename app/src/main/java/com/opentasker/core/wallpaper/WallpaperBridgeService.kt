package com.opentasker.core.wallpaper

import android.content.ComponentName
import android.os.IBinder

/**
 * The privileged half of `wallpaper.live`, running in a Shizuku-spawned process as `shell`.
 *
 * Setting a LIVE wallpaper is not something an ordinary app may do: `setWallpaperComponent` is hidden
 * and guarded by SET_WALLPAPER_COMPONENT, which is `signature|privileged`. The public route is the
 * `ACTION_CHANGE_LIVE_WALLPAPER` preview screen, and the user taps "set" — which is exactly why the
 * Tasker original drove that screen with AutoInput across twenty-six actions, complete with an If/Else
 * on the system locale because the button's label changes with it.
 *
 * None of that is necessary here: `shell` holds SET_WALLPAPER_COMPONENT (verified on this device —
 * `dumpsys package com.android.shell` lists it granted), so from inside a Shizuku UserService the
 * call simply succeeds. No preview, no tapping, no locale branch.
 *
 * Reflective because the method is hidden. A miss returns a readable reason rather than throwing, so
 * the action can tell 白い熊 what happened.
 */
class WallpaperBridgeService : IWallpaperBridge.Stub {

    @Suppress("unused")
    constructor() : super()

    override fun destroy() {
        System.exit(0)
    }

    override fun setLiveWallpaper(packageName: String?, className: String?): String {
        val pkg = packageName?.trim().orEmpty()
        val cls = className?.trim().orEmpty()
        if (pkg.isEmpty() || cls.isEmpty()) return "empty wallpaper component"

        // NOT WallpaperManager: a Shizuku UserService has no Context, so the manager cannot be built
        // here at all. The binder is the way in, exactly as the telephony bridge reaches ISub.
        val service = wallpaperService() ?: return "system service 'wallpaper' is not available"
        val component = ComponentName(pkg, cls)

        // The AIDL has changed shape across releases: a bare setWallpaperComponent(ComponentName) on
        // older builds, and setWallpaperComponentChecked(ComponentName, String, int, int) since the
        // per-screen flags arrived. Try them in that order rather than guessing this device's.
        val plain = service.javaClass.methods.firstOrNull {
            it.name == "setWallpaperComponent" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == ComponentName::class.java
        }
        if (plain != null) return invoke { plain.invoke(service, component) }

        val checked = service.javaClass.methods.firstOrNull {
            it.name == "setWallpaperComponentChecked" && it.parameterTypes.size == 4
        } ?: return "neither setWallpaperComponent nor setWallpaperComponentChecked exists on this build"

        // (component, callingPackage, which, userId). FLAG_SYSTEM or FLAG_LOCK = 3 sets both screens,
        // which is what picking a live wallpaper from the system picker does.
        return invoke { checked.invoke(service, component, SHELL_PACKAGE, FLAG_BOTH, 0) }
    }

    private inline fun invoke(call: () -> Unit): String = try {
        call()
        ""
    } catch (t: Throwable) {
        val cause = t.cause ?: t
        "${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}"
    }

    /** `IWallpaperManager.Stub.asInterface(ServiceManager.getService("wallpaper"))`, by reflection. */
    private fun wallpaperService(): Any? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "wallpaper") as? IBinder
        if (binder == null) {
            null
        } else {
            Class.forName("android.app.IWallpaperManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }
    } catch (t: Throwable) {
        null
    }

    private companion object {
        /** The call is made as shell, and the framework checks the name against the calling uid. */
        const val SHELL_PACKAGE = "com.android.shell"

        /** WallpaperManager.FLAG_SYSTEM or FLAG_LOCK. */
        const val FLAG_BOTH = 3
    }
}
