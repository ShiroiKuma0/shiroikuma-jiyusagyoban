package com.opentasker.core.contexts

import android.net.Uri
import com.opentasker.core.logging.AppLogger
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives connector-delivered, RFC 8291-decrypted bytes and leaves acknowledgement to the SDK. */
class UnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        UnifiedPushEndpointStore(this).saveEndpoint(instance, endpoint)
        AppLogger.info(TAG, "UnifiedPush endpoint registered instance=$instance host=${safeHost(endpoint.url)}")
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) {
            AppLogger.warn(TAG, "Ignoring non-decrypted UnifiedPush message instance=$instance bytes=${message.content.size}")
            return
        }
        val accepted = PushContextEvents.publishUnifiedPushMessage(message.content)
        AppLogger.debug(TAG, "UnifiedPush message accepted=$accepted instance=$instance bytes=${message.content.size}")
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        UnifiedPushEndpointStore(this).markFailure(instance, reason.name)
        AppLogger.warn(TAG, "UnifiedPush registration failed instance=$instance reason=${reason.name}")
    }

    override fun onUnregistered(instance: String) {
        UnifiedPushEndpointStore(this).markUnregistered(instance)
        AppLogger.info(TAG, "UnifiedPush unregistered instance=$instance")
    }

    override fun onTempUnavailable(instance: String) {
        UnifiedPushEndpointStore(this).markTemporarilyUnavailable(instance)
        AppLogger.warn(TAG, "UnifiedPush temporarily unavailable instance=$instance")
    }

    private fun safeHost(endpoint: String): String =
        runCatching { Uri.parse(endpoint).host?.take(128) }.getOrNull() ?: "unknown"

    private companion object {
        const val TAG = "UnifiedPushService"
    }
}
