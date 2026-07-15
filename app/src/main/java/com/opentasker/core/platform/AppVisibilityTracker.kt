package com.opentasker.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/** Process-local activity visibility used only for Android 17 audio eligibility decisions. */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val startedActivityCount = AtomicInteger(0)

    val isAppVisible: Boolean
        get() = startedActivityCount.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
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
