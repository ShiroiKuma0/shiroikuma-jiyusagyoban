package com.opentasker.core.storage

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val PREFS_NAME = "run_log_retention"
private const val KEY_MAX_ENTRIES = "max_entries"
private const val KEY_MAX_AGE_DAYS = "max_age_days"
private const val MIN_ENTRIES = 50
private const val MAX_ENTRIES = 10_000
private const val MIN_AGE_DAYS = 1
private const val MAX_AGE_DAYS = 365

data class RunLogRetentionPolicy(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 1_000
        const val DEFAULT_MAX_AGE_DAYS = 30
    }
}

data class RunLogRetentionOption(
    val label: String,
    val description: String,
    val policy: RunLogRetentionPolicy,
)

object RunLogRetentionOptions {
    val all = listOf(
        RunLogRetentionOption(
            label = "Short",
            description = "7 days or 250 entries",
            policy = RunLogRetentionPolicy(maxEntries = 250, maxAgeDays = 7),
        ),
        RunLogRetentionOption(
            label = "Standard",
            description = "30 days or 1,000 entries",
            policy = RunLogRetentionPolicy(),
        ),
        RunLogRetentionOption(
            label = "Extended",
            description = "90 days or 5,000 entries",
            policy = RunLogRetentionPolicy(maxEntries = 5_000, maxAgeDays = 90),
        ),
    )
}

class RunLogRetentionSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RunLogRetentionPolicy =
        RunLogRetentionPolicy(
            maxEntries = prefs.getInt(KEY_MAX_ENTRIES, RunLogRetentionPolicy.DEFAULT_MAX_ENTRIES),
            maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, RunLogRetentionPolicy.DEFAULT_MAX_AGE_DAYS),
        ).normalized()

    fun save(policy: RunLogRetentionPolicy) {
        val normalized = policy.normalized()
        prefs.edit {
            putInt(KEY_MAX_ENTRIES, normalized.maxEntries)
            putInt(KEY_MAX_AGE_DAYS, normalized.maxAgeDays)
        }
    }
}

fun RunLogRetentionPolicy.normalized(): RunLogRetentionPolicy =
    copy(
        maxEntries = maxEntries.coerceIn(MIN_ENTRIES, MAX_ENTRIES),
        maxAgeDays = maxAgeDays.coerceIn(MIN_AGE_DAYS, MAX_AGE_DAYS),
    )

fun RunLogRetentionPolicy.minimumTimestamp(nowMillis: Long): Long =
    nowMillis - TimeUnit.DAYS.toMillis(normalized().maxAgeDays.toLong())

fun RunLogRetentionPolicy.displayLabel(): String {
    val normalized = normalized()
    return "${normalized.maxAgeDays} ${dayLabel(normalized.maxAgeDays)} or " +
        "${String.format(Locale.US, "%,d", normalized.maxEntries)} ${entryLabel(normalized.maxEntries)}"
}

private fun dayLabel(days: Int): String = if (days == 1) "day" else "days"

private fun entryLabel(entries: Int): String = if (entries == 1) "entry" else "entries"
