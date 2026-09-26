package com.opentasker.core.platform

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityTrackerTest {
    @Test
    fun foregroundListenersFireOnlyWhenTheFirstActivityBecomesVisible() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        val first = Activity()
        val second = Activity()
        AppVisibilityTracker.addForegroundListener(listener)
        try {
            AppVisibilityTracker.onActivityStarted(first)
            AppVisibilityTracker.onActivityStarted(second)
            assertEquals("a second visible activity is not a return to the app", 1, calls)

            AppVisibilityTracker.onActivityStopped(second)
            AppVisibilityTracker.onActivityStopped(first)
            AppVisibilityTracker.onActivityStarted(first)
            assertEquals(2, calls)
            AppVisibilityTracker.onActivityStopped(first)

            AppVisibilityTracker.removeForegroundListener(listener)
            AppVisibilityTracker.onActivityStarted(first)
            AppVisibilityTracker.onActivityStopped(first)
            assertEquals("a removed listener must not be called", 2, calls)
        } finally {
            AppVisibilityTracker.removeForegroundListener(listener)
        }
    }
}
