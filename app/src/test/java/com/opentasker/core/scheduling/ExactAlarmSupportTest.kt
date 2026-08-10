package com.opentasker.core.scheduling

import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmSupportTest {
    private val context = object : ContextWrapper(null) {
        override fun getPackageName(): String = "com.opentasker.test"
    }

    @Test
    fun settingsDecisionTargetsThePlatformPermissionScreen() {
        assertEquals(
            if (Build.VERSION.SDK_INT >= 31) {
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            } else {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            },
            ExactAlarmSupport.settingsAction(Build.VERSION.SDK_INT),
        )
        assertEquals("package:com.opentasker.test", ExactAlarmSupport.settingsPackageUri(context.packageName))
    }

    @Test
    fun preAndroidSDevicesAlwaysSupportExactAlarms() {
        if (Build.VERSION.SDK_INT < 31) {
            assertTrue(ExactAlarmSupport.canScheduleExactAlarms(context))
            assertEquals(AlarmSchedulePrecision.Exact, ExactAlarmSupport.schedulePrecision(context))
        }
    }
}
