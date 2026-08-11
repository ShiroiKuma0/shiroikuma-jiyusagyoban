package com.opentasker.ui.charts

/**
 * The 1–5 scale for the values that HAVE a published reference range.
 *
 * ## Why this exists beside the within-person banding
 *
 * [Recovery] answers "is tonight unusual **for 白い熊**" — a within-person comparison, which is the
 * strongest form available to a single-person record and the right question for spotting a change.
 * It is the wrong question for "was that a good night". A person who habitually sleeps six hours has
 * a median of six hours, so six hours is *usual* for them and a within-person scale can only ever
 * call it ordinary. It will never say the habit itself is the problem. (白い熊, 2026-08-11: "should
 * be measured in bands against some scientific general — not mine — numbers".)
 *
 * So the two live side by side and answer different questions. The ring on a calendar tile still
 * means "outside YOUR usual range"; the colour of a cell in the table means "where this value sits
 * against the population reference". Neither is derived from the other.
 *
 * ## What is referenced, and what deliberately is not
 *
 * **Sleep duration.** The National Sleep Foundation's consensus (Hirshkowitz et al. 2015, *Sleep
 * Health* 1(1):40–43) for adults 26–64: **7–9 h recommended**, 6–7 h and 9–10 h "may be appropriate",
 * outside that "not recommended". The AASM/SRS joint statement (Watson et al. 2015, *Sleep*
 * 38(6):843–844) is the stronger line and the one that breaks the tie inside "may be appropriate":
 * *"Adults should sleep 7 or more hours per night on a regular basis to promote optimal health."*
 * Short and long are therefore NOT symmetric here — 9–10 h scores above 6–7 h, because falling short
 * of the recommendation is the side with the evidence behind it.
 *
 * **Nocturnal heart rate.** The decadal resting-heart-rate categories of Jensen et al. (2013, *Heart*
 * 99(12):882–887, 16-year follow-up), whose risk is monotone across them, corroborated by Aune et al.
 * (2017, *Nutr Metab Cardiovasc Dis* 27(6):504–517): each 10 bpm of resting heart rate carries about
 * 9 % higher all-cause mortality. **Caveat, stated on screen too:** those are DAYTIME resting rates,
 * and a sleeping heart rate runs below its owner's daytime resting. Applying them to a nocturnal
 * value is therefore generous rather than harsh — a nocturnal 65 is not as good as a daytime 65.
 *
 * **Skin temperature has no reference band here and will not get one.** The wrist sensor correlates
 * with the room at r = 0.961 (Sato 2024) and the ambient term is several times the physiological one,
 * so an absolute threshold would be grading the bedroom. It stays within-person, where a deviation
 * from 白い熊's own nights is at least measuring the same bedroom twice.
 *
 * **体感 is not referenced either**, because it is already a 1–5: the number 白い熊 tapped is the
 * value, and there is nothing to convert.
 *
 * Pure Kotlin, no Android, so every boundary below is a test.
 */
object RecoveryReference {

    /** NSF's recommended window, in minutes — the band everything else is measured out from. */
    const val SLEEP_RECOMMENDED_MIN = 7 * 60.0
    const val SLEEP_RECOMMENDED_MAX = 9 * 60.0

    /**
     * Sleep duration → 1–5.
     *
     * 5 is the recommended 7–9 h. 4 is 9–10 h, long but "may be appropriate". 3 is 6–7 h: inside the
     * same NSF category as 9–10 h but below the AASM line, and so ranked under it. 2 is one further
     * hour out on either side, 1 beyond that.
     */
    fun sleepStep(minutes: Double): Int = when {
        minutes >= SLEEP_RECOMMENDED_MIN && minutes <= SLEEP_RECOMMENDED_MAX -> 5
        minutes > SLEEP_RECOMMENDED_MAX && minutes <= 10 * 60 -> 4
        minutes >= 6 * 60 && minutes < SLEEP_RECOMMENDED_MIN -> 3
        minutes >= 5 * 60 && minutes < 6 * 60 -> 2
        minutes > 10 * 60 && minutes <= 11 * 60 -> 2
        else -> 1
    }

    /**
     * Nocturnal heart rate → 1–5, on Jensen's resting-rate decades.
     *
     * Below 50 through 80-and-over, one step per decade. Monotone by construction, because the risk
     * it is standing in for is monotone across exactly these cut points.
     */
    fun nocturnalHrStep(bpm: Double): Int = when {
        bpm < 50 -> 5
        bpm < 60 -> 4
        bpm < 70 -> 3
        bpm < 80 -> 2
        else -> 1
    }
}
