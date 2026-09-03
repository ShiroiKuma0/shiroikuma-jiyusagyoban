package com.opentasker.core.contexts

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The broadcast trigger against a real registered receiver and a real broadcast.
 *
 * The JVM tests cover the sanitiser and the matcher as pure functions. What only a device can show
 * is that the receiver is actually registered for the declared action, that the platform delivers
 * to it, that the sender identity arrives, and that unregistering really stops delivery.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastContextEventsInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        BroadcastContextEvents.sync(context, emptySet())
    }

    @Test
    fun aDeclaredActionIsDeliveredWithBoundedExtrasAndNoSenderByDefault() = runBlocking {
        BroadcastContextEvents.sync(context, setOf(ACTION))
        assertEquals(setOf(ACTION), BroadcastContextEvents.listeningActions())

        val event = coroutineScope {
            // UNDISPATCHED so the collector has subscribed before the broadcast is sent: the flow
            // has no replay, and a late subscriber would simply miss it and time out.
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(DELIVERY_TIMEOUT_MS) { BroadcastContextEvents.events.first() }
            }
            context.sendBroadcast(
                Intent(ACTION)
                    .putExtra("msg", "hi")
                    .putExtra("count", 3)
                    // Dropped by the sanitiser: no Parcelable is unparcelled from an exported
                    // receiver, so this must arrive as a lossy note rather than as a value.
                    .putExtra("payload", intArrayOf(1, 2, 3)),
            )
            received.await()
        }

        assertEquals(BroadcastContextEvents.EVENT_BROADCAST, event.metadata["event"])
        assertEquals(ACTION, event.metadata["broadcast_action"])
        assertEquals("hi", event.metadata["broadcast_extra_msg"])
        assertEquals("3", event.metadata["broadcast_extra_count"])
        assertNull("an int array must not survive", event.metadata["broadcast_extra_payload"])
        assertEquals("true", event.metadata["broadcast_extras_lossy"])

        // The sender is empty even on API 34+, because a plain sendBroadcast does not opt in to
        // sharing identity. This is the normal case, not an edge case: `adb shell am broadcast`
        // and almost every real app land here, which is why the editor warns that a sender filter
        // refuses what it cannot identify. The opt-in path is the positive control below.
        assertEquals("", event.metadata["broadcast_sender"])
    }

    @Test
    fun aSenderThatSharesItsIdentityIsNamed() = runBlocking {
        assumeTrue(
            "sender identity needs API ${BroadcastContextEvents.SENDER_IDENTITY_API}",
            Build.VERSION.SDK_INT >= BroadcastContextEvents.SENDER_IDENTITY_API,
        )
        BroadcastContextEvents.sync(context, setOf(ACTION))

        val event = coroutineScope {
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(DELIVERY_TIMEOUT_MS) { BroadcastContextEvents.events.first() }
            }
            context.sendBroadcast(
                Intent(ACTION),
                null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
            )
            received.await()
        }

        // Without this the "sender is empty" assertion above would also pass if the receiver
        // simply never read the sender at all.
        assertEquals(context.packageName, event.metadata["broadcast_sender"])
    }

    @Test
    fun anUndeclaredActionIsNeverDelivered() = runBlocking {
        BroadcastContextEvents.sync(context, setOf(ACTION))

        val event = coroutineScope {
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(SILENCE_TIMEOUT_MS) { BroadcastContextEvents.events.first() }
            }
            context.sendBroadcast(Intent("$ACTION.OTHER").putExtra("msg", "hi"))
            received.await()
        }

        assertNull("a different action must not reach the trigger", event)
    }

    @Test
    fun disablingTheLastProfileUnregistersTheReceiver() = runBlocking {
        BroadcastContextEvents.sync(context, setOf(ACTION))
        assertTrue(BroadcastContextEvents.listeningActions().isNotEmpty())

        BroadcastContextEvents.sync(context, emptySet())
        assertEquals(emptySet<String>(), BroadcastContextEvents.listeningActions())

        val event = coroutineScope {
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(SILENCE_TIMEOUT_MS) { BroadcastContextEvents.events.first() }
            }
            context.sendBroadcast(Intent(ACTION).putExtra("msg", "hi"))
            received.await()
        }

        assertNull("an unregistered receiver must hear nothing", event)
    }

    @Test
    fun changingTheActionSetMovesTheRegistration() = runBlocking {
        BroadcastContextEvents.sync(context, setOf(ACTION))
        BroadcastContextEvents.sync(context, setOf(SECOND_ACTION))
        assertEquals(setOf(SECOND_ACTION), BroadcastContextEvents.listeningActions())

        val event = coroutineScope {
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(DELIVERY_TIMEOUT_MS) { BroadcastContextEvents.events.first() }
            }
            // The old action first: if the re-registration leaked the previous filter, this
            // arrives and the assertion below fails on the wrong action.
            context.sendBroadcast(Intent(ACTION))
            delay(500)
            context.sendBroadcast(Intent(SECOND_ACTION))
            received.await()
        }

        assertEquals(SECOND_ACTION, event.metadata["broadcast_action"])
    }

    private companion object {
        const val ACTION = "com.opentasker.test.PING"
        const val SECOND_ACTION = "com.opentasker.test.PONG"
        const val DELIVERY_TIMEOUT_MS = 15_000L
        const val SILENCE_TIMEOUT_MS = 4_000L
    }
}
