package com.opentasker.core.contexts

/**
 * The registration state machine, separated from SharedPreferences so it can be tested.
 *
 * Two things here are load-bearing and were previously only exercised by running the connector:
 * every transition away from REGISTERED clears the endpoint and its keys, and every stored string
 * is length-capped so a hostile or broken distributor cannot fill preferences.
 */
object UnifiedPushRegistrationTransitions {
    const val MAX_ENDPOINT_CHARS = 1_000
    const val MAX_KEY_CHARS = 256
    const val MAX_FAILURE_CHARS = 64

    fun decodeStatus(raw: String?): UnifiedPushRegistrationStatus =
        raw?.let { value -> runCatching { UnifiedPushRegistrationStatus.valueOf(value) }.getOrNull() }
            ?: UnifiedPushRegistrationStatus.IDLE

    fun registering(current: UnifiedPushRegistrationState, instance: String): UnifiedPushRegistrationState =
        current.cleared(instance, UnifiedPushRegistrationStatus.REGISTERING)

    fun registered(
        current: UnifiedPushRegistrationState,
        instance: String,
        endpoint: String,
        publicKey: String?,
        auth: String?,
        temporary: Boolean,
    ): UnifiedPushRegistrationState = current.copy(
        instance = instance,
        status = UnifiedPushRegistrationStatus.REGISTERED,
        endpoint = endpoint.take(MAX_ENDPOINT_CHARS),
        publicKey = publicKey?.take(MAX_KEY_CHARS),
        auth = auth?.take(MAX_KEY_CHARS),
        temporaryEndpoint = temporary,
        failureReason = null,
    )

    fun failed(current: UnifiedPushRegistrationState, instance: String, reason: String): UnifiedPushRegistrationState =
        current.cleared(instance, UnifiedPushRegistrationStatus.REGISTRATION_FAILED)
            .copy(failureReason = reason.take(MAX_FAILURE_CHARS))

    /**
     * Deliberately keeps the endpoint: the distributor is expected back, and dropping the endpoint
     * here would make a transient outage look like a deregistration.
     */
    fun temporarilyUnavailable(current: UnifiedPushRegistrationState, instance: String): UnifiedPushRegistrationState =
        current.copy(
            instance = instance,
            status = UnifiedPushRegistrationStatus.TEMPORARILY_UNAVAILABLE,
            failureReason = null,
        )

    fun unregistered(current: UnifiedPushRegistrationState, instance: String): UnifiedPushRegistrationState =
        current.cleared(instance, UnifiedPushRegistrationStatus.UNREGISTERED)

    private fun UnifiedPushRegistrationState.cleared(
        instance: String,
        status: UnifiedPushRegistrationStatus,
    ): UnifiedPushRegistrationState = copy(
        instance = instance,
        status = status,
        endpoint = null,
        publicKey = null,
        auth = null,
        temporaryEndpoint = false,
        failureReason = null,
    )
}
