package com.opentasker.ui.charts

/**
 * Regime changes that silently corrupt a rolling baseline — travel and altitude.
 *
 * ## The failure this exists to stop
 *
 * Every marker in [Recovery] is judged against a rolling personal baseline, and a rolling baseline
 * has one characteristic way of lying: it absorbs a genuine step change and then reports the *return*
 * to normal as the anomaly.
 *
 * - **Travel.** Across 1.5 million nights and 64 847 trips of 1 000 km or more (Willoughby et al.
 *   2025, *Sleep* 48(7)), sleep *duration* returns to within ~12 min of baseline in about two days —
 *   so the simplest input reconverges and the algorithm declares recovery — while sleep *timing* had
 *   not returned to baseline within the entire 15-day follow-up for eastward travel. The misaligned
 *   state is quietly averaged into the baseline, and re-alignment then scores as a fresh deviation.
 *   **No manufacturer documents any correction for this**; Oura's readiness documentation does not
 *   mention time zones at all.
 * - **Altitude.** At 3 619 m, RMSSD *rises* and heart rate rises with it; by 5 140 m SpO₂ falls from
 *   97.8 % to 80.1 % (Boos et al. 2022, n = 89). That signature — resting HR up, oxygen down — is
 *   exactly what a readiness algorithm reads as overtraining, indefinitely, because the deviation is
 *   real and adaptive rather than a problem. Garmin alone corrects for it publicly.
 *
 * ## What is done here, and what deliberately is not
 *
 * The regime is **detected and said out loud**, not silently corrected. Freezing or re-basing a
 * baseline would be inventing an adjustment nobody has published and would hide the very thing worth
 * knowing. An annotation costs nothing and is honest: *"you changed time zone two days ago — the
 * comparison is still catching up"* tells 白い熊 exactly how much to discount the card.
 *
 * Pure Kotlin; the zone offsets and SpO₂ series arrive as arguments.
 */
object RecoveryRegime {

    /** Beyond this a rolling 28-night baseline has re-converged and the note stops. */
    const val TRAVEL_NOTE_DAYS = 14

    /** A sustained drop of this many SpO₂ points below baseline reads as altitude. */
    const val ALTITUDE_DROP_PCT = 2.0

    /** One low night is a bad sensor contact; two is a place. */
    const val ALTITUDE_MIN_NIGHTS = 2

    data class Regime(
        /** Days since the device's UTC offset last changed, when that was recent enough to matter. */
        val daysSinceZoneChange: Int?,
        /** Nights of sustained SpO₂ depression, when it looks like altitude. */
        val altitudeNights: Int?,
        val spo2Drop: Double?,
    ) {
        val any: Boolean get() = daysSinceZoneChange != null || altitudeNights != null
    }

    /**
     * [offsetsByDay] maps an epoch-day to the device's UTC offset in minutes on that day, newest
     * included. A change between consecutive recorded days is a flight.
     */
    fun detect(
        offsetsByDay: Map<Long, Int>,
        todayEpochDay: Long,
        spo2ByNight: List<Double>,
    ): Regime {
        val days = offsetsByDay.keys.sorted()
        var lastChange: Long? = null
        for (i in 1 until days.size) {
            if (offsetsByDay[days[i]] != offsetsByDay[days[i - 1]]) lastChange = days[i]
        }
        val sinceChange = lastChange
            ?.let { (todayEpochDay - it).toInt() }
            ?.takeIf { it in 0..TRAVEL_NOTE_DAYS }

        // Altitude: the recent nights sit below the baseline formed by the earlier ones. Compared
        // against the median of everything before them, so a slow move up a mountain still registers.
        var altitudeNights: Int? = null
        var drop: Double? = null
        if (spo2ByNight.size >= ALTITUDE_MIN_NIGHTS + 3) {
            for (n in spo2ByNight.size - ALTITUDE_MIN_NIGHTS downTo 3) {
                val recent = spo2ByNight.drop(n)
                val baseline = HealthIndexSource.median(spo2ByNight.take(n)) ?: continue
                val worst = recent.maxOrNull() ?: continue
                if (baseline - worst >= ALTITUDE_DROP_PCT) {
                    altitudeNights = recent.size
                    drop = baseline - (HealthIndexSource.median(recent) ?: worst)
                } else {
                    break
                }
            }
        }
        return Regime(sinceChange, altitudeNights, drop)
    }
}
