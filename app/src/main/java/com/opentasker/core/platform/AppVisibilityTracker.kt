package com.opentasker.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local activity visibility, for Android 17 audio eligibility decisions and for monitors
 * that need to re-check something when the user returns to the app.
 */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityCount = AtomicInteger(0)
    private val foregroundListeners = CopyOnWriteArraySet<() -> Unit>()

    val isAppVisible: Boolean
        get() = startedActivityCount.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    /** Called on the main thread each time the app goes from no visible activity to one. */
    fun addForegroundListener(listener: () -> Unit) {
        foregroundListeners += listener
    }

    fun removeForegroundListener(listener: () -> Unit) {
        foregroundListeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount.incrementAndGet() == 1) {
            foregroundListeners.forEach { listener -> runCatching(listener) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
