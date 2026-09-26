package com.opentasker.core.permissions

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Setup's old precise-only location request is ignored on Android 12+ and reads as a denial, so
 * upgraded installs carry a "use app settings" record for it that the user never earned.
 */
@RunWith(AndroidJUnit4::class)
class RuntimePermissionRequestHistoryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("runtime_permission_request_history", Context.MODE_PRIVATE)
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private lateinit var saved: Map<String, *>

    @Before
    fun saveAndClear() {
        saved = prefs.all.toMap()
        prefs.edit().clear().commit()
    }

    @After
    fun restore() {
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.commit()
    }

    @Test
    fun theDenialLeftByTheIgnoredRequestIsForgottenOnceOnAndroid12AndLater() {
        prefs.edit().putInt("attempts:$fine", 2).putBoolean("settings:$fine", true).commit()
        prefs.edit().putInt("attempts:${Manifest.permission.CAMERA}", 2)
            .putBoolean("settings:${Manifest.permission.CAMERA}", true).commit()

        val history = RuntimePermissionRequestHistory(context, sdkInt = Build.VERSION_CODES.S)

        assertFalse("the stale precise-location denial must not force app settings", history.requiresSettings(fine))
        assertTrue("other permissions keep their records", history.requiresSettings(Manifest.permission.CAMERA))

        // A real denial recorded after the cleanup has to survive the next construction.
        history.recordRequest(fine)
        history.recordRequest(fine)
        history.recordResult(fine, granted = false, shouldShowRationale = false)
        assertTrue(RuntimePermissionRequestHistory(context, sdkInt = Build.VERSION_CODES.S).requiresSettings(fine))
        assertEquals(2, prefs.getInt("attempts:$fine", 0))
    }

    @Test
    fun androidElevenAndOlderKeepTheirRecordsBecauseTheRequestWorkedThere() {
        prefs.edit().putInt("attempts:$fine", 2).putBoolean("settings:$fine", true).commit()

        val history = RuntimePermissionRequestHistory(context, sdkInt = Build.VERSION_CODES.R)

        assertTrue(history.requiresSettings(fine))
    }
}
