package com.sysadmindoc.ruledeck.core.plugins.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.sysadmindoc.ruledeck.core.contexts.ContextEvent
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.core.platform.ForeignParcels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

object LocalePluginRequestQueryEvents {
    private const val TAG = "PluginRequestQuery"
    private const val EVENT_NAME = "locale_request_query"
    private const val MAX_ACTIVITY_CLASS_CHARS = 240
    private val classNamePattern = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+$")

    /** Whether an event matcher for [requestedEvent] can fire on a `REQUEST_QUERY`: one for this event, or for any event (blank). */
    fun isRequested(requestedEvent: String?): Boolean =
        requestedEvent.isNullOrBlank() || requestedEvent.trim().equals(EVENT_NAME, ignoreCase = true)

    private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sharedLock = Any()
    private var shared: Flow<ContextEvent>? = null

    /** `REQUEST_QUERY` events through one exported receiver for the whole process, however many matchers listen. */
    fun events(app: Context): Flow<ContextEvent> = synchronized(sharedLock) {
        // The application context: the first caller can be the service, and a stop unregisters its receivers.
        shared ?: receiverEvents(app.applicationContext ?: app)
            .shareIn(
                scope = sharedScope,
                // Unregisters once the last matcher stops, and rides out a profile reload's brief gap.
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                replay = 0,
            )
            .also { shared = it }
    }

    private fun receiverEvents(app: Context): Flow<ContextEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val event = intent?.let(::buildEventFromIntent) ?: return
                trySend(event)
            }
        }
        val filter = IntentFilter(LocalePluginContract.ACTION_REQUEST_QUERY)

        try {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (ex: RuntimeException) {
            // Shared, so a refusal here would reach no matcher and take the process down instead.
            AppLogger.warn(TAG, "Plugin query requests aren't heard: ${ex.message}")
            close()
            return@callbackFlow
        }

        awaitClose {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    fun buildEvent(
        activityClassName: String,
        bundleValues: Map<String, String> = emptyMap(),
    ): ContextEvent? {
        val normalizedClass = normalizeActivityClassName(activityClassName) ?: return null
        return ContextEvent(
            type = "event",
            matched = true,
            metadata = mapOf(
                "event" to EVENT_NAME,
                "activityClass" to normalizedClass,
                "bundleJson" to LocalePluginBundleCodec.encodeStringBundle(bundleValues),
            ),
        )
    }

    fun buildEventFromIntent(intent: Intent): ContextEvent? {
        if (intent.action != LocalePluginContract.ACTION_REQUEST_QUERY) return null
        ForeignParcels.guard(intent)
        return runCatching {
            val activityClassName = requestedActivityClass(intent::getStringExtra)
            val bundle = intent.getBundleExtra(LocalePluginContract.EXTRA_BUNDLE)
            val bundleValues = bundle?.let(LocalePluginBundleCodec::fromBundle).orEmpty()
            buildEvent(activityClassName, bundleValues)
        }.getOrNull()
    }

    /** The class a `REQUEST_QUERY` names, read from the standard extra first, then the spelled-out one. */
    fun requestedActivityClass(extra: (String) -> String?): String =
        listOf(
            LocalePluginContract.EXTRA_STRING_ACTIVITY_CLASS_NAME,
            LocalePluginContract.EXTRA_ACTIVITY_CLASS_NAME_SPELLED_OUT,
        ).firstNotNullOfOrNull { key -> extra(key)?.takeIf { it.isNotBlank() } }.orEmpty()

    fun normalizeActivityClassName(value: String): String? {
        val normalized = value.trim().take(MAX_ACTIVITY_CLASS_CHARS)
        if (normalized.isBlank()) return null
        return normalized.takeIf { classNamePattern.matches(it) }
    }
}
