package com.opentasker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenTaskerBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndPrimaryNavigation() = baselineProfileRule.collect(PACKAGE_NAME) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.findObject(By.text("Continue without a template")), ONBOARDING_TIMEOUT_MS)?.click()
        val tasks = device.wait(Until.findObject(By.text("Tasks")), NAVIGATION_TIMEOUT_MS)
        checkNotNull(tasks) {
            "Tasks navigation did not appear after startup."
        }.click()
    }

    private companion object {
        const val PACKAGE_NAME = "com.opentasker.app"
        const val ONBOARDING_TIMEOUT_MS = 2_000L
        const val NAVIGATION_TIMEOUT_MS = 10_000L
    }
}
