package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDateTime

/**
 * Retention for the band's own tables — a sibling of RunLogRetention.
 *
 * Pruning here is safe in a way it would not otherwise be, because the JSONL archive is the
 * unbounded record: the database is a working set that can be trimmed, and nothing is lost.
 *
 * `band_syncs` is deliberately NOT pruned by any of this. It is a few rows a day and its whole value
 * is the multi-day series that measures the band's ring buffer.
 */
data class BandRetentionPolicy(
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
    val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    companion object {
        const val DEFAULT_MAX_AGE_DAYS = 400
        const val DEFAULT_MAX_SAMPLES = 3_000_000
    }
}

data class BandRetentionOption(val label: String, val description: String, val policy: BandRetentionPolicy)

object BandRetentionOptions {
    val all = listOf(
        BandRetentionOption("Short", "90 days", BandRetentionPolicy(maxAgeDays = 90)),
        BandRetentionOption("Standard", "400 days", BandRetentionPolicy()),
        BandRetentionOption("Everything", "5 years", BandRetentionPolicy(maxAgeDays = 1825)),
    )
}

class BandRetentionSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): BandRetentionPolicy = BandRetentionPolicy(
        maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, BandRetentionPolicy.DEFAULT_MAX_AGE_DAYS),
        maxSamples = prefs.getInt(KEY_MAX_SAMPLES, BandRetentionPolicy.DEFAULT_MAX_SAMPLES),
    )

    fun save(policy: BandRetentionPolicy) = prefs.edit {
        putInt(KEY_MAX_AGE_DAYS, policy.maxAgeDays.coerceIn(1, 3650))
        putInt(KEY_MAX_SAMPLES, policy.maxSamples.coerceIn(10_000, 20_000_000))
    }

    private companion object {
        const val PREFS = "band_retention"
        const val KEY_MAX_AGE_DAYS = "max_age_days"
        const val KEY_MAX_SAMPLES = "max_samples"
    }
}

/**
 * The cutoff as a **localTs** (yyyyMMddHHmmss), not epoch millis — because that is what the rows are
 * keyed on. Deriving it from the calendar keeps the comparison in the same space as the key, so a
 * DST shift cannot move the boundary by an hour's worth of rows.
 */
fun BandRetentionPolicy.cutoffLocalTs(now: LocalDateTime = LocalDateTime.now()): Long {
    val at = now.minusDays(maxAgeDays.toLong())
    return at.year.toLong() * 10_000_000_000L +
        at.monthValue.toLong() * 100_000_000L +
        at.dayOfMonth.toLong() * 1_000_000L +
        at.hour.toLong() * 10_000L +
        at.minute.toLong() * 100L +
        at.second.toLong()
}

/** The same boundary as a yyyyMMdd date, for band_daily. */
fun BandRetentionPolicy.cutoffLocalDate(now: LocalDateTime = LocalDateTime.now()): Long =
    cutoffLocalTs(now) / 1_000_000
