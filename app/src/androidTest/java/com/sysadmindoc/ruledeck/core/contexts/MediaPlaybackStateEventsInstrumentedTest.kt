package com.sysadmindoc.ruledeck.core.contexts

import com.opentasker.core.contexts.NotificationTriggerService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The media state reads active sessions through the notification listener. Asked with no listener,
 * Android wants MEDIA_CONTENT_CONTROL, so the package list stayed empty on every device.
 * Notification access is granted for the test and put back after, so on a device that had it off the
 * listener is bound for a moment; no notification is posted here.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.O_MR1)
class MediaPlaybackStateEventsInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val listener: ComponentName
        get() = ComponentName(context, NotificationTriggerService::class.java)

    @Test
    fun aPlayingSessionShowsItsPackageWithNotificationAccess() {
        val hadAccess = listenerEnabled()
        if (!hadAccess) shell("cmd notification allow_listener ${listener.flattenToString()}")
        val session = MediaSession(context, "ruledeck-media-test")
        try {
            assertTrue(listenerEnabled())
            session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
            session.isActive = true

            val snapshot = MediaPlaybackStateEvents.snapshot(context)

            assertTrue(
                "media_package was '${snapshot["media_package"]}'",
                MediaPlaybackStateEvents.packageMatches(snapshot.getValue("media_package"), context.packageName),
            )
            assertEquals("true", snapshot["media_active"])
        } finally {
            session.release()
            if (!hadAccess) shell("cmd notification disallow_listener ${listener.flattenToString()}")
        }
    }

    // The approved list media sessions check. Settings.Secure only catches up with it afterward.
    private fun listenerEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)

    private fun shell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
