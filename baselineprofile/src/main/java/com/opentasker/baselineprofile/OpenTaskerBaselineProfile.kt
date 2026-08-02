package com.opentasker.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        device.findObject(By.text("Tasks")).click()
    }

    private companion object {
        const val PACKAGE_NAME = "com.opentasker.app"
    }
}
