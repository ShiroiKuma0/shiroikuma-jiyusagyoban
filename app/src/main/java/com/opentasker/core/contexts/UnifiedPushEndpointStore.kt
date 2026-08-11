package com.opentasker.core.contexts

import android.content.Context
import android.net.Uri
import org.unifiedpush.android.connector.data.PushEndpoint

enum class UnifiedPushRegistrationStatus {
    IDLE,
    REGISTERING,
    REGISTERED,
    UNREGISTERED,
    TEMPORARILY_UNAVAILABLE,
    REGISTRATION_FAILED,
}

data class UnifiedPushRegistrationState(
    val instance: String = UnifiedPushConnector.DEFAULT_INSTANCE,
    val status: UnifiedPushRegistrationStatus = UnifiedPushRegistrationStatus.IDLE,
    val endpoint: String? = null,
    val publicKey: String? = null,
    val auth: String? = null,
    val temporaryEndpoint: Boolean = false,
    val failureReason: String? = null,
    val distributor: String? = null,
) {
    val endpointHost: String?
        get() = endpoint?.let { value ->
            runCatching { Uri.parse(value).host?.takeIf(String::isNotBlank) }.getOrNull()
        }
}

/** Stores the connector endpoint and the last protocol status without retaining message content. */
class UnifiedPushEndpointStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun state(): UnifiedPushRegistrationState = synchronized(preferences) {
        UnifiedPushRegistrationState(
            instance = preferences.getString(KEY_INSTANCE, UnifiedPushConnector.DEFAULT_INSTANCE)
                .orEmpty()
                .ifBlank { UnifiedPushConnector.DEFAULT_INSTANCE },
            status = preferences.getString(KEY_STATUS, null)
                ?.let { value -> runCatching { UnifiedPushRegistrationStatus.valueOf(value) }.getOrNull() }
                ?: UnifiedPushRegistrationStatus.IDLE,
            endpoint = preferences.getString(KEY_ENDPOINT, null)?.takeIf(String::isNotBlank),
            publicKey = preferences.getString(KEY_PUBLIC_KEY, null)?.takeIf(String::isNotBlank),
            auth = preferences.getString(KEY_AUTH, null)?.takeIf(String::isNotBlank),
            temporaryEndpoint = preferences.getBoolean(KEY_TEMPORARY, false),
            failureReason = preferences.getString(KEY_FAILURE_REASON, null)?.takeIf(String::isNotBlank),
            distributor = preferences.getString(KEY_DISTRIBUTOR, null)?.takeIf(String::isNotBlank),
        )
    }

    fun markRegistering(instance: String = UnifiedPushConnector.DEFAULT_INSTANCE) {
        update(
            state().copy(
                instance = instance,
                status = UnifiedPushRegistrationStatus.REGISTERING,
                endpoint = null,
                publicKey = null,
                auth = null,
                temporaryEndpoint = false,
                failureReason = null,
            ),
        )
    }

    fun saveEndpoint(instance: String, endpoint: PushEndpoint) {
        val keySet = endpoint.pubKeySet
        update(
            state().copy(
                instance = instance,
                status = UnifiedPushRegistrationStatus.REGISTERED,
                endpoint = endpoint.url.take(MAX_ENDPOINT_CHARS),
                publicKey = keySet?.pubKey?.take(MAX_KEY_CHARS),
                auth = keySet?.auth?.take(MAX_KEY_CHARS),
                temporaryEndpoint = endpoint.temporary,
                failureReason = null,
            ),
        )
    }

    fun markFailure(instance: String, reason: String) {
        update(
            state().copy(
                instance = instance,
                status = UnifiedPushRegistrationStatus.REGISTRATION_FAILED,
                endpoint = null,
                publicKey = null,
                auth = null,
                temporaryEndpoint = false,
                failureReason = reason.take(MAX_FAILURE_CHARS),
            ),
        )
    }

    fun markTemporarilyUnavailable(instance: String) {
        update(
            state().copy(
                instance = instance,
                status = UnifiedPushRegistrationStatus.TEMPORARILY_UNAVAILABLE,
                failureReason = null,
            ),
        )
    }

    fun markUnregistered(instance: String) {
        update(
            state().copy(
                instance = instance,
                status = UnifiedPushRegistrationStatus.UNREGISTERED,
                endpoint = null,
                publicKey = null,
                auth = null,
                temporaryEndpoint = false,
                failureReason = null,
            ),
        )
    }

    fun setDistributor(distributor: String?) {
        update(state().copy(distributor = distributor?.takeIf(String::isNotBlank)))
    }

    private fun update(state: UnifiedPushRegistrationState) {
        synchronized(preferences) {
            preferences.edit()
                .putString(KEY_INSTANCE, state.instance)
                .putString(KEY_STATUS, state.status.name)
                .putNullable(KEY_ENDPOINT, state.endpoint)
                .putNullable(KEY_PUBLIC_KEY, state.publicKey)
                .putNullable(KEY_AUTH, state.auth)
                .putBoolean(KEY_TEMPORARY, state.temporaryEndpoint)
                .putNullable(KEY_FAILURE_REASON, state.failureReason)
                .putNullable(KEY_DISTRIBUTOR, state.distributor)
                .apply()
        }
    }

    private fun android.content.SharedPreferences.Editor.putNullable(key: String, value: String?): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val PREFERENCES = "unified_push_registration"
        const val KEY_INSTANCE = "instance"
        const val KEY_STATUS = "status"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_PUBLIC_KEY = "public_key"
        const val KEY_AUTH = "auth"
        const val KEY_TEMPORARY = "temporary"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val KEY_DISTRIBUTOR = "distributor"
        const val MAX_ENDPOINT_CHARS = 1_000
        const val MAX_KEY_CHARS = 256
        const val MAX_FAILURE_CHARS = 64
    }
}
