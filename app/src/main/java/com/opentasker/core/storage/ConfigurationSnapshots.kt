package com.opentasker.core.storage

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * Opt-in local snapshot policy.
 *
 * Snapshots are ordinary managed backups taken on a schedule. They never leave the device, never
 * touch the live database, and never stage a restore: restoring one still goes through the existing
 * inspect-then-stage review gate.
 */
data class ConfigurationSnapshotPolicy(
    val enabled: Boolean = false,
    val maxSnapshots: Int = DEFAULT_MAX_SNAPSHOTS,
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
) {
    companion object {
        const val DEFAULT_MAX_SNAPSHOTS = 5
        const val DEFAULT_MAX_AGE_DAYS = 14
        const val MIN_SNAPSHOTS = 2
        const val MAX_SNAPSHOTS = 30
        const val MIN_AGE_DAYS = 1
        const val MAX_AGE_DAYS = 180
    }
}

fun ConfigurationSnapshotPolicy.normalized(): ConfigurationSnapshotPolicy = copy(
    maxSnapshots = maxSnapshots.coerceIn(
        ConfigurationSnapshotPolicy.MIN_SNAPSHOTS,
        ConfigurationSnapshotPolicy.MAX_SNAPSHOTS,
    ),
    maxAgeDays = maxAgeDays.coerceIn(
        ConfigurationSnapshotPolicy.MIN_AGE_DAYS,
        ConfigurationSnapshotPolicy.MAX_AGE_DAYS,
    ),
)

/** Last observed snapshot outcome, so the UI can report more than "it is on". */
data class ConfigurationSnapshotStatus(
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val lastFailureMessage: String? = null,
    val snapshotCount: Int = 0,
    val storageBytes: Long = 0L,
)

class ConfigurationSnapshotSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ConfigurationSnapshotPolicy = ConfigurationSnapshotPolicy(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        maxSnapshots = prefs.getInt(KEY_MAX_SNAPSHOTS, ConfigurationSnapshotPolicy.DEFAULT_MAX_SNAPSHOTS),
        maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, ConfigurationSnapshotPolicy.DEFAULT_MAX_AGE_DAYS),
    ).normalized()

    fun save(policy: ConfigurationSnapshotPolicy) {
        val normalized = policy.normalized()
        prefs.edit {
            putBoolean(KEY_ENABLED, normalized.enabled)
            putInt(KEY_MAX_SNAPSHOTS, normalized.maxSnapshots)
            putInt(KEY_MAX_AGE_DAYS, normalized.maxAgeDays)
        }
    }

    fun loadStatus(): ConfigurationSnapshotStatus = ConfigurationSnapshotStatus(
        lastSuccessAtMs = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastFailureAtMs = prefs.getLong(KEY_LAST_FAILURE, 0L).takeIf { it > 0L },
        lastFailureMessage = prefs.getString(KEY_LAST_FAILURE_MESSAGE, null),
    )

    fun recordSuccess(atMs: Long) {
        prefs.edit {
            putLong(KEY_LAST_SUCCESS, atMs)
            remove(KEY_LAST_FAILURE)
            remove(KEY_LAST_FAILURE_MESSAGE)
        }
    }

    fun recordFailure(atMs: Long, message: String?) {
        prefs.edit {
            putLong(KEY_LAST_FAILURE, atMs)
            putString(KEY_LAST_FAILURE_MESSAGE, message?.take(MAX_FAILURE_MESSAGE_CHARS))
        }
    }

    private companion object {
        const val PREFS_NAME = "configuration_snapshots"
        const val KEY_ENABLED = "enabled"
        const val KEY_MAX_SNAPSHOTS = "max_snapshots"
        const val KEY_MAX_AGE_DAYS = "max_age_days"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_LAST_FAILURE = "last_failure_at"
        const val KEY_LAST_FAILURE_MESSAGE = "last_failure_message"
        const val MAX_FAILURE_MESSAGE_CHARS = 200
    }
}

/**
 * Chooses which snapshots to delete for [policy], newest first.
 *
 * Pure so the retention decision is testable without touching the file system: age and count are
 * applied together, and the newest snapshot is never deleted even if it is older than the window,
 * because having one stale recovery point beats having none.
 */
fun selectExpiredSnapshots(
    snapshots: List<SnapshotFile>,
    policy: ConfigurationSnapshotPolicy,
    nowMs: Long,
): List<SnapshotFile> {
    val normalized = policy.normalized()
    val newestFirst = snapshots.sortedByDescending(SnapshotFile::lastModifiedMs)
    if (newestFirst.isEmpty()) return emptyList()
    val oldestAllowedMs = nowMs - normalized.maxAgeDays * MILLIS_PER_DAY
    return newestFirst.drop(1).filterIndexed { indexAfterNewest, snapshot ->
        val position = indexAfterNewest + 1
        position >= normalized.maxSnapshots || snapshot.lastModifiedMs < oldestAllowedMs
    }
}

/** File identity a retention decision needs, so the pure selector never reads the disk. */
data class SnapshotFile(val name: String, val lastModifiedMs: Long, val sizeBytes: Long = 0L)

internal fun File.toSnapshotFile(): SnapshotFile = SnapshotFile(name, lastModified(), length())

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
