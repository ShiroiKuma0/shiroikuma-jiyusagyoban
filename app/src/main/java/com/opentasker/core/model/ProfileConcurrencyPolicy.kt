package com.opentasker.core.model

/** Bounded per-profile admission overrides. Null values inherit engine defaults. */
object ProfileConcurrencyPolicy {
    const val MIN_MAX_ACTIVE = 1
    const val MAX_MAX_ACTIVE = 8
    const val MIN_BURST_LIMIT = 1
    const val MAX_BURST_LIMIT = 32

    fun normalizeMaxActive(value: Int?): Int? = value?.coerceIn(MIN_MAX_ACTIVE, MAX_MAX_ACTIVE)

    fun normalizeBurstLimit(value: Int?): Int? = value?.coerceIn(MIN_BURST_LIMIT, MAX_BURST_LIMIT)
}
