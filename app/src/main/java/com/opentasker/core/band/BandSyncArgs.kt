package com.opentasker.core.band

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `band.sync`'s arguments, parsed. Pure and Context-free so the whole of it is JVM-testable — the
 * Action itself then does nothing but resolve and call.
 */

/** Where to start reading from. */
sealed interface BandFrom {
    /** Last successful sync minus the overlap; three days back if there has never been one. */
    data object Auto : BandFrom

    /** A whole number of days back from today. */
    data class Days(val days: Int) : BandFrom

    /** An explicit local instant. */
    data class At(val at: BandLocalTime) : BandFrom
}

data class BandSyncArgs(
    val from: BandFrom,
    val streams: List<BandStream>,
    val address: String?,
    val prefix: String,
    val timeoutSec: Int,
    val backup: Boolean,
    val store: String?,
) {
    companion object {
        const val DEFAULT_PREFIX = "BAND_"
        const val DEFAULT_TIMEOUT_SEC = 180
        const val FALLBACK_DAYS = 3

        private val EXPLICIT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun parse(args: Map<String, String>): Result<BandSyncArgs> {
            val fromRaw = args["from"]?.trim().orEmpty().ifEmpty { "auto" }
            val from = parseFrom(fromRaw) ?: return Result.failure(
                IllegalArgumentException(
                    "from must be 'auto', a number of days, or 'yyyy-MM-dd HH:mm:ss' — got '$fromRaw'",
                ),
            )
            val timeout = args["timeout_sec"]?.trim()?.toIntOrNull() ?: DEFAULT_TIMEOUT_SEC
            return Result.success(
                BandSyncArgs(
                    from = from,
                    streams = BandSettings.parseStreams(args["streams"]?.trim().orEmpty()),
                    address = args["address"]?.trim()?.ifEmpty { null },
                    prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: DEFAULT_PREFIX,
                    // Coerced rather than rejected: a silly number in a Profile should still sync.
                    timeoutSec = timeout.coerceIn(15, 600),
                    backup = args["backup"]?.trim()?.lowercase() !in setOf("false", "0", "no", "off"),
                    store = args["store"]?.trim()?.ifEmpty { null },
                ),
            )
        }

        private fun parseFrom(raw: String): BandFrom? {
            if (raw.equals("auto", ignoreCase = true)) return BandFrom.Auto
            raw.toIntOrNull()?.let { days ->
                return if (days in 0..3650) BandFrom.Days(days) else null
            }
            runCatching { LocalDateTime.parse(raw, EXPLICIT) }.getOrNull()?.let { return BandFrom.At(it.toBandTime()) }
            runCatching { java.time.LocalDate.parse(raw, DATE_ONLY) }.getOrNull()?.let {
                return BandFrom.At(BandLocalTime(it.year, it.monthValue, it.dayOfMonth))
            }
            return null
        }

        /**
         * Resolve to the instant actually put on the wire.
         *
         * Auto deliberately re-requests an overlap of already-held data: overlap is FREE because the
         * dedupe key discards it, whereas asking for too little loses records permanently once the
         * band's ring buffer has rolled over them.
         */
        fun resolve(
            from: BandFrom,
            lastSuccessAtMillis: Long?,
            overlapMinutes: Int,
            now: LocalDateTime,
        ): BandLocalTime = when (from) {
            is BandFrom.At -> from.at
            is BandFrom.Days -> now.minusDays(from.days.toLong()).toLocalDate().atStartOfDay().toBandTime()
            BandFrom.Auto -> {
                if (lastSuccessAtMillis == null || lastSuccessAtMillis <= 0L) {
                    now.minusDays(FALLBACK_DAYS.toLong()).toLocalDate().atStartOfDay().toBandTime()
                } else {
                    val last = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(lastSuccessAtMillis),
                        java.time.ZoneId.systemDefault(),
                    )
                    last.minusMinutes(overlapMinutes.toLong()).toBandTime()
                }
            }
        }
    }
}

internal fun LocalDateTime.toBandTime() =
    BandLocalTime(year, monthValue, dayOfMonth, hour, minute, second)
