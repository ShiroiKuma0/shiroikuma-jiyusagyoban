package com.opentasker.core.wallpaper;

/**
 * Setting a LIVE wallpaper, run in a Shizuku-spawned privileged process (uid 2000 / shell).
 *
 * `setWallpaperComponent` is hidden and guarded by SET_WALLPAPER_COMPONENT, which is
 * `signature|privileged` — an ordinary app cannot call it, which is why the public route is the
 * preview screen with a confirming tap. Shell holds it granted on this device, so from here the call
 * simply works.
 *
 * Bound with Shizuku.bindUserService, exactly like ITelephonyBridge — nothing is copied to
 * /data/local/tmp. Every method carries an explicit id, because AIDL demands all or none and the
 * Shizuku server reserves the one for destroy().
 */
interface IWallpaperBridge {
    /** Destroy id reserved by the Shizuku server (called on unbind-with-remove). */
    void destroy() = 16777114;

    /** Set the live wallpaper. Returns "" on success, else a human-readable reason. */
    String setLiveWallpaper(String packageName, String className) = 1;
}
