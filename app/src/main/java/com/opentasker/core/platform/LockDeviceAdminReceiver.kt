package com.opentasker.core.platform

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * The single device-admin component OpenTasker registers, and it exists for one action.
 *
 * `DevicePolicyManager.lockNow` is the only way a normal app can lock the screen without root,
 * Shizuku, or an accessibility service, and it survives Android 17 Advanced Protection because it
 * is not an accessibility capability. Locking is also the only policy this component asks for:
 * `res/xml/device_admin.xml` declares `force-lock` and nothing else, so activating it cannot wipe
 * data, set a password policy, or read anything.
 *
 * The cost is real and worth stating in Setup: Android will not let a device-admin app be
 * uninstalled until its admin is deactivated first.
 */
class LockDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, LockDeviceAdminReceiver::class.java)

        /**
         * Whether the user has activated the admin.
         *
         * Reads through the platform every time rather than caching. Deactivation happens in
         * Android's own settings, with no callback to this app, so a cached answer would go stale
         * silently and the action would claim a capability it no longer has.
         */
        fun isActive(context: Context): Boolean {
            val manager = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            return runCatching { manager.isAdminActive(component(context)) }.getOrDefault(false)
        }
    }
}
