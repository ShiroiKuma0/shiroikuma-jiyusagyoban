package com.opentasker.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.opentasker.core.contexts.AppForegroundChangedContextEvents
import com.opentasker.core.engine.EngineShutdown
import kotlinx.coroutines.delay

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
        // Dormant, not disabled, while the app is stopped. disableSelf() would work, but it drops the
        // accessibility grant — 白い熊 would have to re-enable the service by hand in system settings —
        // so the service stays bound and simply stops feeding the engine.
        if (EngineShutdown.isStopped(this)) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Record only real APPLICATION windows — never the IME (keyboard), system tools (screenshot), or
        // overlays. Prefer the window type; if it can't be read, at least exclude IME packages.
        val type = runCatching { windows.firstOrNull { it.id == event.windowId }?.type }.getOrNull()
        if (type != null && type != AccessibilityWindowInfo.TYPE_APPLICATION) return
        if (type == null && isIme(pkg)) return
        val isApp = launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }
        if (!isApp) return
        val changed: Boolean
        synchronized(mru) {
            changed = mru.firstOrNull() != pkg
            mru.remove(pkg)
            mru.add(0, pkg)
            while (mru.size > MRU_CAP) mru.removeAt(mru.lastIndex)
        }
        // Feed the app_foreground EVENT trigger (sets %APP_PACKAGE). Accessibility is reliable on EMUI
        // where UsageStats yields nothing; publish() dedups so the UsageStats poll can't double-fire.
        if (changed) AppForegroundChangedContextEvents.publish(pkg)
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

        /**
         * True when the service is ENABLED in system settings, regardless of whether it is bound right
         * now. The system toggle and the live [isConnected] binding are two different states: EMUI tears
         * the service down and rebinds it across a configuration change (a locale switch) or a
         * memory-pressure reap, so the toggle can read on while [instance] is momentarily null.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, ShiroiKumaAccessibilityService::class.java)
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(flat) }
            return splitter.any { ComponentName.unflattenFromString(it) == me }
        }

        /**
         * Await the live binding, tolerating the transient unbind→rebind gap. Returns true at once when
         * already bound; when the toggle is on but the service isn't bound yet (the EMUI config-change /
         * reap window), polls up to [timeoutMs] for the rebind; returns false immediately when the toggle
         * is off — genuinely not enabled, so the caller should block honestly rather than stall.
         */
        suspend fun awaitConnected(context: Context, timeoutMs: Long = 3000L): Boolean {
            if (isConnected) return true
            if (!isEnabledInSettings(context)) return false
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_INTERVAL_MS)
                if (isConnected) return true
            }
            return isConnected
        }

        private const val POLL_INTERVAL_MS = 100L

        /** The connected service (an AccessibilityService Context), for adding a TYPE_ACCESSIBILITY_OVERLAY. */
        val service: AccessibilityService? get() = instance

        /** Dispatch a GLOBAL_ACTION_* via the connected service; false if not enabled or it failed. */
        fun perform(globalAction: Int): Boolean = instance?.performGlobalAction(globalAction) ?: false
    }
}
