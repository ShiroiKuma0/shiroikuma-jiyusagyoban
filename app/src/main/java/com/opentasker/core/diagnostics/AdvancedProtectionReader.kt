package com.opentasker.core.diagnostics

import android.content.Context
import android.os.Build

/**
 * Detects Android 16 (API 36) Advanced Protection Mode. When enabled, APM revokes Accessibility
 * from apps that do not declare the "Accessibility Tool" use and restricts other automation-class
 * capabilities, so an automation app should surface it instead of failing opaquely.
 *
 * The platform API is read via reflection so the app compiles and fails closed on any device or SDK
 * where the manager or method is unavailable (returning "not enabled" rather than crashing). Below
 * API 36 the check is a no-op.
 */
object AdvancedProtectionReader {
    private const val ADVANCED_PROTECTION_SERVICE = "advanced_protection"
    private const val MANAGER_CLASS = "android.security.advancedprotection.AdvancedProtectionManager"
    private const val IS_ENABLED_METHOD = "isAdvancedProtectionEnabled"

    /** True only when running on API 36+ with Advanced Protection Mode actively enabled. */
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            val managerClass = Class.forName(MANAGER_CLASS)
            val manager = context.getSystemService(ADVANCED_PROTECTION_SERVICE) ?: return false
            if (!managerClass.isInstance(manager)) return false
            val method = managerClass.getMethod(IS_ENABLED_METHOD)
            method.invoke(manager) as? Boolean ?: false
        }.getOrDefault(false)
    }

    /**
     * Pure mapping used for the Setup/health warning: warn only on API 36+ when APM is enabled.
     * Below API 36 the signal does not exist, so no warning is shown regardless of [apmEnabled].
     */
    internal fun shouldWarn(sdkInt: Int, apmEnabled: Boolean): Boolean =
        sdkInt >= 36 && apmEnabled
}
