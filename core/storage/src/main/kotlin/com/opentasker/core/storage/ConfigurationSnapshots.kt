package com.opentasker.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Opt-in off-device snapshot policy.
 *
 * [destinationTreeUri] is a user-selected Storage Access Framework tree with a persisted grant.
 * Snapshot archives never touch the live database and never stage a restore: restoring one still
 * goes through the existing inspect-then-stage review gate.
 */
data class ConfigurationSnapshotPolicy(
    val enabled: Boolean = false,
    val maxSnapshots: Int = DEFAULT_MAX_SNAPSHOTS,
    val maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS,
    val destinationTreeUri: String? = null,
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
    destinationTreeUri = destinationTreeUri?.takeIf(String::isNotBlank),
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
        destinationTreeUri = prefs.getString(KEY_DESTINATION_TREE_URI, null),
    ).normalized()

    fun save(policy: ConfigurationSnapshotPolicy) {
        val normalized = policy.normalized()
        prefs.edit(commit = true) {
            putBoolean(KEY_ENABLED, normalized.enabled)
            putInt(KEY_MAX_SNAPSHOTS, normalized.maxSnapshots)
            putInt(KEY_MAX_AGE_DAYS, normalized.maxAgeDays)
            putString(KEY_DESTINATION_TREE_URI, normalized.destinationTreeUri)
        }
    }

    /**
     * Caches the user-owned archive passphrase under the variable-secret Keystore key. The
     * ciphertext is useful only to this install; the user-held passphrase is what makes archives
     * recoverable after uninstall or on another device.
     */
    fun saveRecoveryPassphrase(passphrase: CharArray) {
        require(passphrase.isNotEmpty()) { "Snapshot recovery passphrase must not be empty" }
        val encrypted = VariableSecretCodecs.android.encrypt(
            SNAPSHOT_SECRET_PROJECT_ID,
            SNAPSHOT_SECRET_NAME,
            passphrase.concatToString(),
        )
        check(prefs.edit().putString(KEY_RECOVERY_PASSPHRASE, encrypted).commit()) {
            "Could not durably cache the snapshot recovery passphrase"
        }
    }

    fun loadRecoveryPassphrase(): CharArray {
        val encrypted = prefs.getString(KEY_RECOVERY_PASSPHRASE, null)
            ?: throw SnapshotRecoveryPassphraseUnavailableException(
                "Snapshot recovery passphrase is not configured",
            )
        return VariableSecretCodecs.android.decrypt(
            SNAPSHOT_SECRET_PROJECT_ID,
            SNAPSHOT_SECRET_NAME,
            encrypted,
        ).getOrElse { error ->
            throw SnapshotRecoveryPassphraseUnavailableException(
                "Snapshot recovery passphrase is unavailable",
                error,
            )
        }.toCharArray()
    }

    fun clearRecoveryPassphrase() {
        prefs.edit(commit = true) { remove(KEY_RECOVERY_PASSPHRASE) }
    }

    fun loadStatus(): ConfigurationSnapshotStatus = ConfigurationSnapshotStatus(
        lastSuccessAtMs = prefs.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
        lastFailureAtMs = prefs.getLong(KEY_LAST_FAILURE, 0L).takeIf { it > 0L },
        lastFailureMessage = prefs.getString(KEY_LAST_FAILURE_MESSAGE, null),
        snapshotCount = prefs.getInt(KEY_SNAPSHOT_COUNT, 0).coerceAtLeast(0),
        storageBytes = prefs.getLong(KEY_STORAGE_BYTES, 0L).coerceAtLeast(0L),
    )

    fun recordSuccess(atMs: Long, snapshotCount: Int, storageBytes: Long) {
        prefs.edit(commit = true) {
            putLong(KEY_LAST_SUCCESS, atMs)
            putInt(KEY_SNAPSHOT_COUNT, snapshotCount.coerceAtLeast(0))
            putLong(KEY_STORAGE_BYTES, storageBytes.coerceAtLeast(0L))
            remove(KEY_LAST_FAILURE)
            remove(KEY_LAST_FAILURE_MESSAGE)
        }
    }

    fun recordFailure(atMs: Long, message: String?) {
        prefs.edit(commit = true) {
            putLong(KEY_LAST_FAILURE, atMs)
            putString(KEY_LAST_FAILURE_MESSAGE, message?.take(MAX_FAILURE_MESSAGE_CHARS))
        }
    }

    fun clearStatus() {
        prefs.edit(commit = true) {
            remove(KEY_LAST_SUCCESS)
            remove(KEY_LAST_FAILURE)
            remove(KEY_LAST_FAILURE_MESSAGE)
            remove(KEY_SNAPSHOT_COUNT)
            remove(KEY_STORAGE_BYTES)
        }
    }

    /** Emits initially and whenever the policy, archive inventory, or worker status changes. */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    private companion object {
        const val PREFS_NAME = "configuration_snapshots"
        const val KEY_ENABLED = "enabled"
        const val KEY_MAX_SNAPSHOTS = "max_snapshots"
        const val KEY_MAX_AGE_DAYS = "max_age_days"
        const val KEY_DESTINATION_TREE_URI = "destination_tree_uri"
        const val KEY_RECOVERY_PASSPHRASE = "recovery_passphrase"
        const val KEY_LAST_SUCCESS = "last_success_at"
        const val KEY_LAST_FAILURE = "last_failure_at"
        const val KEY_LAST_FAILURE_MESSAGE = "last_failure_message"
        const val KEY_SNAPSHOT_COUNT = "snapshot_count"
        const val KEY_STORAGE_BYTES = "storage_bytes"
        const val SNAPSHOT_SECRET_PROJECT_ID = Long.MIN_VALUE
        const val SNAPSHOT_SECRET_NAME = "__configuration_snapshot_recovery_passphrase"
        const val MAX_FAILURE_MESSAGE_CHARS = 200
    }
}

/** Durably claims a user-selected SAF tree and caches the archive passphrase for scheduled work. */
// Public because the Setup screen configures the destination; core:storage is a module now.
fun configureConfigurationSnapshotDestination(
    context: Context,
    uri: Uri,
    passphrase: CharArray,
    enableSchedule: Boolean,
): ConfigurationSnapshotPolicy {
    val appContext = context.applicationContext
    val settings = ConfigurationSnapshotSettings(appContext)
    val previousTreeUri = settings.load().destinationTreeUri?.let(Uri::parse)
    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    appContext.contentResolver.takePersistableUriPermission(
        uri,
        grantFlags,
    )
    settings.saveRecoveryPassphrase(passphrase)
    val current = settings.load()
    val policy = current.copy(
        enabled = current.enabled || enableSchedule,
        destinationTreeUri = uri.toString(),
    )
    settings.save(policy)
    settings.clearStatus()
    ConfigurationSnapshotWorker.sync(appContext, policy)
    if (previousTreeUri != null && previousTreeUri != uri) {
        runCatching { appContext.contentResolver.releasePersistableUriPermission(previousTreeUri, grantFlags) }
    }
    return policy
}

internal class SnapshotRecoveryPassphraseUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

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
