package com.opentasker.core.contexts

data class PlatformLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double? = null,
    val observedAtEpochMs: Long,
    val provider: String? = null,
)

object LocationContextEvents {
    const val TYPE = "location"

    fun location(sample: PlatformLocationSample): ContextEvent =
        ContextEvent(TYPE, true, metadata(sample))

    fun setupBlocked(reason: String, detail: String): ContextEvent =
        ContextEvent(
            TYPE,
            false,
            mapOf(
                "setup" to reason,
                "reason" to detail,
            ),
        )

    fun metadata(sample: PlatformLocationSample): Map<String, String> = buildMap {
        put("latitude", sample.latitude.toString())
        put("longitude", sample.longitude.toString())
        put("observedAtEpochMs", sample.observedAtEpochMs.toString())
        sample.accuracyMeters?.let { put("accuracyMeters", it.toString()) }
        sample.provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
    }
}
