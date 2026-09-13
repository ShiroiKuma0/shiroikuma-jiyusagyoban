package com.opentasker.core.huawei

/**
 * Storage keys for the RR-interval windows.
 *
 * Only the two fields established against Huawei Health's own displayed values get names. The rest
 * keep their field number, exactly as unrecognised feature bits do elsewhere in this package: a
 * numbered key is honest about not knowing, and a guessed name would be believed by every chart and
 * every reader downstream. Renaming later is a migration; un-believing a wrong name is not.
 */
object HuaweiRriKeys {

    /** Field 1 — intervals the band accepted in the window. */
    const val COUNT = "rri_count"

    /** Field 6 — mean RR interval in milliseconds, in 20 ms steps. */
    const val MEAN_MS = "rri_mean_ms"

    /**
     * Statistics computed HERE from the band's per-beat series, not read off its panel.
     *
     * A separate prefix from `rri_` on purpose: those are Huawei's numbers and these are ours. When
     * `beat_rmssd` and `rri_f5` disagree, the two names are what makes the disagreement legible
     * instead of turning into one averaged figure belonging to neither.
     */
    const val BEAT_SDNN = "beat_sdnn"
    const val BEAT_RMSSD = "beat_rmssd"
    const val BEAT_PNN50 = "beat_pnn50"

    /** Beats the quality index let through — the denominator behind every other `beat_` value. */
    const val BEAT_COUNT = "beat_count"

    /**
     * Breaths per minute, from respiratory sinus arrhythmia.
     *
     * The one metric on this band that no Huawei surface publishes and no sensor supplies: it is
     * recovered from the intervals. Absent for a record whose own clock disagrees with its beats,
     * and absent whenever no HF peak stands out — see `HuaweiBeatMetrics`, which says why an absent
     * reading is the honest answer there rather than an argmax.
     */
    const val BEAT_RESP_BPM = "beat_resp_bpm"

    /**
     * How far the intervals swing at the respiratory frequency, in ms.
     *
     * Stored beside the rate because the pair is what makes an elevated HF power readable at all.
     * `rri_f8` rises both when breathing slows and deepens and when vagal tone climbs; the rate
     * falls in the first case and this rises in the second.
     */
    const val BEAT_RSA_MS = "beat_rsa_ms"

    /**
     * Every metric this app derives from the per-beat series.
     *
     * Named as a set because a recompute has to be able to RETRACT as well as overwrite. An upsert
     * alone cannot: when a corrected window falls below the minimum interval count the estimator
     * returns null, writes nothing, and the previous value survives — and on 2026-09-12 the four
     * values that survived that way were the four most wrong ones in the whole series, because the
     * windows too damaged to compute are exactly the windows that produced the absurd figures.
     */
    val BEAT_METRICS = listOf(BEAT_SDNN, BEAT_RMSSD, BEAT_PNN50, BEAT_COUNT, BEAT_RESP_BPM, BEAT_RSA_MS)

    fun metricFor(field: Int): String = when (field) {
        1 -> COUNT
        6 -> MEAN_MS
        else -> "rri_f$field"
    }
}
