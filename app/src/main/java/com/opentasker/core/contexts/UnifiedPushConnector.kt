package com.opentasker.core.contexts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import org.unifiedpush.android.connector.UnifiedPush

/** Thin app-facing registration boundary around the official UnifiedPush connector SDK. */
object UnifiedPushConnector {
    const val DEFAULT_INSTANCE = "default"
    const val DISTRIBUTOR_DISCOVERY_URI = "unifiedpush://link"

    private const val REGISTRATION_MESSAGE = "OpenTasker push triggers"

    fun chooseDistributor(context: Context, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val activity = context.findActivity()
        if (activity == null) {
            onResult(false)
            return
        }
        runCatching {
            UnifiedPush.tryPickDistributor(activity) { selected ->
                UnifiedPushEndpointStore(appContext).setDistributor(
                    UnifiedPush.getSavedDistributor(appContext),
                )
                onResult(selected)
            }
        }.onFailure {
            onResult(false)
        }
    }

    fun register(context: Context, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val store = UnifiedPushEndpointStore(appContext)
        store.markRegistering(DEFAULT_INSTANCE)
        val activity = context.findActivity()
        if (activity == null) {
            store.markFailure(DEFAULT_INSTANCE, FAILURE_NO_DISTRIBUTOR)
            onResult(false)
            return
        }
        runCatching {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { available ->
                store.setDistributor(UnifiedPush.getSavedDistributor(appContext))
                if (!available) {
                    store.markFailure(DEFAULT_INSTANCE, FAILURE_NO_DISTRIBUTOR)
                    onResult(false)
                    return@tryUseCurrentOrDefaultDistributor
                }
                runCatching {
                    // The connector selects FLAG_SHARE_IDENTITY on SDK 34+ and the immutable
                    // PendingIntent identity fallback below it.
                    UnifiedPush.register(
                        appContext,
                        DEFAULT_INSTANCE,
                        REGISTRATION_MESSAGE,
                        null,
                    )
                }.onSuccess {
                    onResult(true)
                }.onFailure {
                    store.markFailure(DEFAULT_INSTANCE, FAILURE_INTERNAL_ERROR)
                    onResult(false)
                }
            }
        }.onFailure {
            store.markFailure(DEFAULT_INSTANCE, FAILURE_INTERNAL_ERROR)
            onResult(false)
        }
    }

    fun unregister(context: Context) {
        val appContext = context.applicationContext
        // The protocol removes the token before the distributor acknowledgement arrives.
        UnifiedPushEndpointStore(appContext).markUnregistered(DEFAULT_INSTANCE)
        runCatching { UnifiedPush.unregister(appContext, DEFAULT_INSTANCE) }
    }

    const val FAILURE_NO_DISTRIBUTOR = "NO_DISTRIBUTOR"
    const val FAILURE_INTERNAL_ERROR = "INTERNAL_ERROR"

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        repeat(8) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }
}
