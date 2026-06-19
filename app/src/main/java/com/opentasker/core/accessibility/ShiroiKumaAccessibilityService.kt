package com.opentasker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service. We use it only for [performGlobalAction] (Back, Recents, the
 * notification/quick-settings panels, the power dialog, lock screen) — the framework's only way for
 * a non-privileged app to drive those system gestures. The connected instance is held statically so
 * built-in actions can dispatch a global action when the user has enabled the service in Android
 * Settings. No window content is read.
 */
class ShiroiKumaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* global actions only */ }

    override fun onInterrupt() { /* global actions only */ }

    companion object {
        @Volatile
        private var instance: ShiroiKumaAccessibilityService? = null

        /** True when the user has enabled and the system has bound the service. */
        val isConnected: Boolean get() = instance != null

        /** The connected service (an AccessibilityService Context), for adding a TYPE_ACCESSIBILITY_OVERLAY. */
        val service: AccessibilityService? get() = instance

        /** Dispatch a GLOBAL_ACTION_* via the connected service; false if not enabled or it failed. */
        fun perform(globalAction: Int): Boolean = instance?.performGlobalAction(globalAction) ?: false
    }
}
