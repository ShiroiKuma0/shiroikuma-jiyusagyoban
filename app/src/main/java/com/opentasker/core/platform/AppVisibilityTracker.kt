package com.opentasker.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local activity visibility, for Android 17 audio eligibility decisions and for monitors
 * that need to re-check something when the user comes back to the app or closes a prompt over it.
 */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityCount = AtomicInteger(0)
    private val resumeListeners = CopyOnWriteArraySet<() -> Unit>()

    val isAppVisible: Boolean
        get() = startedActivityCount.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Called on the main thread each time one of the app's activities resumes: on the way back
     * from the background, and when a permission prompt drawn over the app closes. The prompt
     * pauses the activity without stopping it, so the app never stops being visible and a
     * return-to-the-app signal would miss the grant.
     */
    fun addResumeListener(listener: () -> Unit) {
        resumeListeners += listener
    }

    fun removeResumeListener(listener: () -> Unit) {
        resumeListeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    override fun onActivityResumed(activity: Activity) {
        resumeListeners.forEach { listener -> runCatching(listener) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
