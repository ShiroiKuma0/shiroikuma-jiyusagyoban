package com.opentasker.core.huawei

/**
 * Where a sync starts, and — the part that matters — how it is cut into windows.
 *
 * Pure Kotlin, because this file encodes a firmware quirk that is expensive to rediscover and this
 * is the only place that knowledge lives.
 *
 * ## Why a list of bounded windows, and not one open range
 *
 * The band's history protocol is stateful in a way nothing documents. A count query sets the working
 * window and record indices are relative to *that* window. Ask for everything since the epoch and
 * then request record 1 and the band answers with error 106489; ask for a 24-hour window first and
 * the identical request succeeds. So a run is a sequence of bounded windows, each counted
 * immediately before its records are fetched, and the window is never changed mid-fetch.
 *
 * The Hume band has no equivalent: its `from` is a single instant. Mirroring that here would produce
 * a sync that fails on any gap longer than a day, intermittently, with an opaque error code.
 */
sealed interface HuaweiFrom {
    /** Continue from the last successful sync, with the configured overlap. The normal case. */
    data object Auto : HuaweiFrom

    /** Walk back as far as [HuaweiSyncArgs.resolve]'s cap allows — the ring-depth probe. */
    data object All : HuaweiFrom

    /** An explicit start. No overlap is applied: the caller named the instant they meant. */
    data class Since(val epochSeconds: Long) : HuaweiFrom
}

object HuaweiSyncArgs {

    /** One window's span. 24 h is the largest count window observed to work on firmware 6.0.0.125. */
    const val DEFAULT_WINDOW_HOURS = 24

    /**
     * How many windows one run may walk. A backstop, not a preference: without it a wrong clock or a
     * corrupt last-success could ask the band for years and hold the radio for hours.
     */
    const val DEFAULT_MAX_WINDOWS = 14

    /**
     * Cut `[start, now]` into windows, newest first.
     *
     * Newest-first is deliberate: if a run is cut short — a timeout, a walk out of range, a flat
     * battery — what survives is the most recent data, which is the data most likely to be lost
     * from the band's ring buffer first.
     *
     * @return contiguous inclusive ranges of epoch **seconds**, newest first. Consecutive
     *   windows share one boundary second; see the comment in the body. Never empty.
     */
    fun resolve(
        from: HuaweiFrom,
        lastSuccessAtSeconds: Long?,
        overlapMinutes: Int,
        nowSeconds: Long,
        maxWindowHours: Int = DEFAULT_WINDOW_HOURS,
        maxWindows: Int = DEFAULT_MAX_WINDOWS,
    ): List<LongRange> {
        val windowSec = maxWindowHours.coerceAtLeast(1) * 3_600L
        val windows = maxWindows.coerceAtLeast(1)

        val start = when (from) {
            is HuaweiFrom.Since -> from.epochSeconds
            HuaweiFrom.All -> nowSeconds - windowSec * windows
            HuaweiFrom.Auto ->
                if (lastSuccessAtSeconds == null) {
                    nowSeconds - windowSec
                } else {
                    // Overlap is applied ONCE, at the oldest edge. It is free — the (metric, minute)
                    // dedupe key discards it — whereas asking for too little loses records for good.
                    lastSuccessAtSeconds - overlapMinutes.coerceAtLeast(0) * 60L
                }
        }

        // A clock that has stepped backwards, or a Since in the future, must not produce a negative
        // range or an empty list. One ordinary window is the safe answer.
        if (start >= nowSeconds) return listOf((nowSeconds - windowSec + 1)..nowSeconds)

        // Consecutive windows SHARE their boundary second rather than abutting it. That is not
        // sloppiness: tiling an inclusive range exactly leaves a one-second sliver whenever the gap
        // lands on a window boundary, and asking the band for a one-second window is a wasted round
        // trip. A shared second costs nothing — the (metric, minute) dedupe key discards it.
        val out = ArrayList<LongRange>()
        var to = nowSeconds
        while (out.size < windows) {
            val edge = maxOf(start, to - windowSec)
            out += edge..to
            if (edge <= start) break
            to = edge
        }
        return out
    }

    /** Parse the Action's `from` argument. Anything unrecognised is [HuaweiFrom.Auto]. */
    fun parseFrom(raw: String?): HuaweiFrom {
        val text = raw?.trim().orEmpty()
        return when {
            text.isEmpty() || text.equals("auto", ignoreCase = true) -> HuaweiFrom.Auto
            text.equals("all", ignoreCase = true) || text == "0" -> HuaweiFrom.All
            else -> text.toLongOrNull()?.let { HuaweiFrom.Since(it) } ?: HuaweiFrom.Auto
        }
    }
}
