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
import com.opentasker.core.logging.AppLogger
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
        rebindGaveUpAt = 0L
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
    // misses some, e.g. emacs). Only a real launchable app entering through an Activity of its own is
    // recorded; widgets, overlays, IME and dialogs are skipped.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Dormant, not disabled, while the app is stopped. disableSelf() would work, but it drops the
        // accessibility grant — 白い熊 would have to re-enable the service by hand in system settings —
        // so the service stays bound and simply stops feeding the engine.
        if (EngineShutdown.isStopped(this)) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // A window-state change is NOT proof that `pkg` came to the front. Anything that adds or replaces
        // a window inside somebody else's screen fires one tagged with its OWN package: an app widget on
        // the launcher's desktop, a popup, a toast — and our own scene / bubble overlays. On 白い熊's
        // widget-dense 雷起動盤 desktop %APP_PACKAGE therefore flipped within seconds of landing on the
        // home screen (observed: mahojutan, com.android.settings, and jiyusagyoban itself) while
        // raikidoban held the only application window, so every per-app rule keyed on it read the wrong
        // app — the 相撲字時計 blacklist never matched raikidoban and the clock stayed up over it.
        // A real switch names an Activity of `pkg`; a widget or an overlay names a plain View class.
        if (!isActivityOf(pkg, event.className?.toString())) return
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

    /**
     * True when [className] is an Activity (or activity-alias) that [pkg] itself declares — the mark of a
     * real app switch. A widget, popup or overlay window names a View class instead, which no manifest
     * declares as an activity, so the lookup fails and the event is ignored. The answer is a manifest
     * fact for as long as the app is installed, so it is cached per class; a package this app cannot see
     * would answer "no" to everything, which is why the manifest holds QUERY_ALL_PACKAGES.
     */
    private fun isActivityOf(pkg: String, className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        return activityCache.getOrPut("$pkg/$className") {
            val declared = runCatching {
                packageManager.getActivityInfo(ComponentName(pkg, className), 0)
            }.isSuccess
            // Once per class, not per event — the desktop's widgets would otherwise flood the log.
            if (!declared) AppLogger.debug(TAG, "foreground: ignoring $pkg/$className — not an activity")
            declared
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

        private const val TAG = "OpenTasker"
        private const val MRU_CAP = 30
        private val mru = ArrayList<String>() // most-recent-first foreground apps (incl. our own when opened)
        private val launchable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val imeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val activityCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
         * Await the live binding, tolerating the transient unbind→rebind gap.
         *
         * Returns true at once when already bound, and false at once when the toggle is off — that is
         * genuinely not enabled, and the caller should block honestly rather than stall.
         *
         * The interesting case is **toggle on, nothing bound**, which is two different situations that
         * look identical from inside the app:
         *
         * 1. The transient unbind→rebind gap. EMUI drops the binding across a configuration change or a
         *    memory-pressure reap and puts it back a second or two later. Waiting is exactly right.
         * 2. The framework has marked the service **crashed**. `dumpsys accessibility` then shows it
         *    under `Crashed services` and *not* under `Bound services`, and Android will never re-bind
         *    it on its own — the user has to toggle it off and on. Waiting is futile, forever.
         *
         * Observed on 白い熊's Mate XT on 2026-08-08: state 2, ten minutes after a boot. Every blocked
         * task paid the full [timeoutMs] before failing, on a workspace whose tasks fire by the second.
         *
         * Since the two cannot be told apart, this pays the wait **once** and then backs off for
         * [REBIND_BACKOFF_MS]. A real rebind clears the backoff through [onServiceConnected], so case 1
         * still recovers within a second of the service coming back.
         */
        suspend fun awaitConnected(context: Context, timeoutMs: Long = 3000L): Boolean {
            if (isConnected) return true
            if (!isEnabledInSettings(context)) return false
            val now = SystemClock.elapsedRealtime()
            if (rebindGaveUpAt != 0L && now - rebindGaveUpAt < REBIND_BACKOFF_MS) return false
            val deadline = now + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(POLL_INTERVAL_MS)
                if (isConnected) return true
            }
            rebindGaveUpAt = SystemClock.elapsedRealtime()
            return false
        }

        /**
         * True when the user has switched the service ON but nothing is bound — the state that makes
         * "enable it in Settings" the wrong thing to tell somebody, because they already have.
         */
        fun isEnabledButNotRunning(context: Context): Boolean =
            !isConnected && isEnabledInSettings(context)

        /** When the last rebind wait gave up, so the next caller does not pay for it again. */
        @Volatile
        private var rebindGaveUpAt = 0L

        /** How long to believe "it is not coming back" before paying for another wait. */
        private const val REBIND_BACKOFF_MS = 60_000L

        private const val POLL_INTERVAL_MS = 100L

        /** The connected service (an AccessibilityService Context), for adding a TYPE_ACCESSIBILITY_OVERLAY. */
        val service: AccessibilityService? get() = instance

        /** Dispatch a GLOBAL_ACTION_* via the connected service; false if not enabled or it failed. */
        fun perform(globalAction: Int): Boolean = instance?.performGlobalAction(globalAction) ?: false
    }
}
