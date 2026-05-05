package com.opentasker.core.location

import android.content.Context
import com.opentasker.core.contexts.ContextEvent
import com.opentasker.core.contexts.LocationContextEvents
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import java.security.MessageDigest
import java.util.Locale

data class LocationDwellUpdate(
    val metadata: Map<String, String>,
    val persistence: LocationDwellPersistence,
)

sealed interface LocationDwellPersistence {
    data object Keep : LocationDwellPersistence
    data object Clear : LocationDwellPersistence
    data class Persist(val insideSinceEpochMs: Long) : LocationDwellPersistence
}

object LocationDwellStateTracker {
    fun apply(
        config: Map<String, String>,
        metadata: Map<String, String>,
        existingInsideSinceEpochMs: Long?,
        nowEpochMs: Long,
    ): LocationDwellUpdate {
        val observedAt = first(metadata, "observedAtEpochMs", "timestampEpochMs", "nowEpochMs")
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?: nowEpochMs
        val presenceConfig = config.filterKeys { it !in DWELL_KEYS }
        val evaluation = FossGeofenceEvaluator.evaluate(presenceConfig, metadata)
            ?: return LocationDwellUpdate(metadata.withDwellState("unknown"), LocationDwellPersistence.Keep)

        if (!evaluation.withinRadius) {
            return LocationDwellUpdate(
                metadata.withoutDwellMetadata().withDwellState("outside"),
                LocationDwellPersistence.Clear,
            )
        }

        val existing = existingInsideSinceEpochMs?.takeIf { it in 1L..observedAt }
        if (!evaluation.accuracyAccepted) {
            return LocationDwellUpdate(
                metadata.withOptionalInsideSince(existing).withDwellState("accuracy_blocked"),
                LocationDwellPersistence.Keep,
            )
        }

        val insideSince = existing ?: observedAt
        return LocationDwellUpdate(
            metadata
                .withOptionalInsideSince(insideSince)
                .withDwellState("inside"),
            LocationDwellPersistence.Persist(insideSince),
        )
    }

    private fun first(values: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { values[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun Map<String, String>.withOptionalInsideSince(insideSinceEpochMs: Long?): Map<String, String> {
        if (insideSinceEpochMs == null) return withoutDwellMetadata()
        return withoutDwellMetadata() + ("insideSinceEpochMs" to insideSinceEpochMs.toString())
    }

    private fun Map<String, String>.withoutDwellMetadata(): Map<String, String> =
        filterKeys { it !in INSIDE_SINCE_KEYS && it != "dwellState" }

    private fun Map<String, String>.withDwellState(state: String): Map<String, String> =
        this + ("dwellState" to state)

    private val DWELL_KEYS = setOf("dwellMillis", "dwellMs", "dwellSeconds", "dwellSec")
    private val INSIDE_SINCE_KEYS = setOf("insideSinceEpochMs", "enteredAtEpochMs")
}

class LocationDwellStateStore(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enrich(
        profileId: Long,
        contextIndex: Int,
        spec: ContextSpec,
        event: ContextEvent,
    ): ContextEvent {
        if (spec.type != ContextType.LOCATION || event.type != LocationContextEvents.TYPE || !event.matched) {
            return event
        }

        val key = LocationDwellStateKey.from(profileId, contextIndex, spec.config)
        val existing = prefs.getLong(key.storageKey, MISSING_INSIDE_SINCE)
            .takeIf { it != MISSING_INSIDE_SINCE }
        val update = LocationDwellStateTracker.apply(
            config = spec.config,
            metadata = event.metadata,
            existingInsideSinceEpochMs = existing,
            nowEpochMs = clock(),
        )

        when (val persistence = update.persistence) {
            LocationDwellPersistence.Keep -> Unit
            LocationDwellPersistence.Clear -> prefs.edit().remove(key.storageKey).apply()
            is LocationDwellPersistence.Persist -> prefs.edit()
                .putLong(key.storageKey, persistence.insideSinceEpochMs)
                .apply()
        }

        return event.copy(metadata = update.metadata)
    }

    fun clearProfile(profileId: Long): Int =
        clearKeysWithPrefix(LocationDwellStateKey.profilePrefix(profileId))

    private fun clearKeysWithPrefix(prefix: String): Int {
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return 0
        prefs.edit().also { editor ->
            keys.forEach { editor.remove(it) }
        }.apply()
        return keys.size
    }

    companion object {
        private const val PREFS_NAME = "opentasker_location_dwell_state"
        private const val MISSING_INSIDE_SINCE = -1L
    }
}

data class LocationDwellStateKey(val storageKey: String) {
    companion object {
        fun profilePrefix(profileId: Long): String =
            "profile:$profileId:"

        fun from(profileId: Long, contextIndex: Int, config: Map<String, String>): LocationDwellStateKey {
            val configHash = sha256(
                config.entries
                    .sortedBy { it.key.lowercase(Locale.US) }
                    .joinToString(separator = "\n") { "${it.key.trim()}=${it.value.trim()}" },
            )
            return LocationDwellStateKey("${profilePrefix(profileId)}context:$contextIndex:$configHash")
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }.take(16)
        }
    }
}
