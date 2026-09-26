package com.opentasker.core.platform

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTrackerTest {
    @Test
    fun resumeListenersHearAPromptClosingOverTheApp() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        val activity = Activity()
        AppVisibilityTracker.addResumeListener(listener)
        try {
            AppVisibilityTracker.onActivityStarted(activity)
            AppVisibilityTracker.onActivityResumed(activity)
            assertEquals("coming back to the app is a resume", 1, calls)

            // Setup's permission prompt pauses the activity and resumes it with no stop between,
            // so the app stays visible the whole time and only the resume says the grant is in.
            AppVisibilityTracker.onActivityPaused(activity)
            assertTrue(AppVisibilityTracker.isAppVisible)
            AppVisibilityTracker.onActivityResumed(activity)
            assertEquals("a prompt closing over the app is a resume", 2, calls)

            AppVisibilityTracker.onActivityPaused(activity)
            AppVisibilityTracker.onActivityStopped(activity)
            assertFalse(AppVisibilityTracker.isAppVisible)

            AppVisibilityTracker.removeResumeListener(listener)
            AppVisibilityTracker.onActivityStarted(activity)
            AppVisibilityTracker.onActivityResumed(activity)
            AppVisibilityTracker.onActivityPaused(activity)
            AppVisibilityTracker.onActivityStopped(activity)
            assertEquals("a removed listener must not be called", 2, calls)
        } finally {
            AppVisibilityTracker.removeResumeListener(listener)
        }
    }
}
