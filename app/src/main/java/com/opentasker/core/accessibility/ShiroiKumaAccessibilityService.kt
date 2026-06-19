package com.opentasker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

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

    // Maintain a foreground-app history from window-state changes — accurate for ALL apps (UsageStats
    // misses some, e.g. emacs). Only real launchable apps are recorded; overlays / IME / dialogs are skipped.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Record only real APPLICATION windows — never the IME (keyboard), system tools (screenshot), or
        // overlays. Prefer the window type; if it can't be read, at least exclude IME packages.
        val type = runCatching { windows.firstOrNull { it.id == event.windowId }?.type }.getOrNull()
        if (type != null && type != AccessibilityWindowInfo.TYPE_APPLICATION) return
        if (type == null && isIme(pkg)) return
        val isApp = launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }
        if (!isApp) return
        synchronized(mru) {
            mru.remove(pkg)
            mru.add(0, pkg)
            while (mru.size > MRU_CAP) mru.removeAt(mru.lastIndex)
        }
    }

    private fun isIme(pkg: String): Boolean = imeCache.getOrPut(pkg) {
        (getSystemService(InputMethodManager::class.java)?.enabledInputMethodList ?: emptyList())
            .any { it.packageName == pkg }
    }

    override fun onInterrupt() { /* global actions only */ }

    companion object {
        @Volatile
        private var instance: ShiroiKumaAccessibilityService? = null

        private const val MRU_CAP = 30
        private val mru = ArrayList<String>() // most-recent-first foreground apps (incl. our own when opened)
        private val launchable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val imeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /** Foreground-app history, most-recent-first — captures every real app the user switches to. */
        val recentApps: List<String> get() = synchronized(mru) { mru.toList() }

        /** True when the user has enabled and the system has bound the service. */
        val isConnected: Boolean get() = instance != null

        /** The connected service (an AccessibilityService Context), for adding a TYPE_ACCESSIBILITY_OVERLAY. */
        val service: AccessibilityService? get() = instance

        /** Dispatch a GLOBAL_ACTION_* via the connected service; false if not enabled or it failed. */
        fun perform(globalAction: Int): Boolean = instance?.performGlobalAction(globalAction) ?: false
    }
}
