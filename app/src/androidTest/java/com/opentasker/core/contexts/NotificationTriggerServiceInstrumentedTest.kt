package com.opentasker.core.contexts

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the notification listener hands the matcher, with real Notification extras (a Bundle can't
 * be built in a JVM test). The service is called directly, so nothing is posted and no listener
 * access is needed: it only reads, on any phone.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTriggerServiceInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun posted(notification: Notification): Map<String, String> = runBlocking {
        val received = async(start = CoroutineStart.UNDISPATCHED) { NotificationContextEvents.events.first() }
        NotificationTriggerService().onNotificationPosted(statusBar(notification))
        withTimeout(5_000) { received.await() }.metadata
    }

    @Suppress("DEPRECATION")
    private fun statusBar(notification: Notification) = StatusBarNotification(
        SENDER, SENDER, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis(),
    )

    private fun builder() = Notification.Builder(context, "opentasker-test").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Test
    fun theTitleAndTextReachTheMatcherUnderTheSendersPackage() {
        val metadata = posted(builder().setContentTitle("Door  bell").setContentText("Someone\nis here").build())

        assertEquals(
            mapOf("event" to "notification", "package" to SENDER, "title" to "Door bell", "body" to "Someone is here"),
            metadata,
        )
    }

    @Test
    fun aNotificationWithOnlyBigTextUsesItAsTheBody() {
        val metadata = posted(
            builder().setContentTitle("Mail").setStyle(Notification.BigTextStyle().bigText("The long version")).build(),
        )

        assertEquals("The long version", metadata["body"])
    }

    @Test
    fun theShortTextWinsOverBigTextWhenBothAreThere() {
        val metadata = posted(
            builder().setContentTitle("Mail").setContentText("Short")
                .setStyle(Notification.BigTextStyle().bigText("The long version")).build(),
        )

        assertEquals("Short", metadata["body"])
    }

    private companion object {
        const val SENDER = "com.example.sender"
    }
}
