package com.opentasker.core.storage

import kotlinx.serialization.json.Json

/**
 * The JSON codec for the DB's embedded lists — task `actions`, profile `contexts`, scene `elements`.
 *
 * Deliberately TOLERANT on decode. The default `Json` throws on any unknown key or malformed token, and
 * the DAOs turn a decode failure into `emptyList()` — so a single schema/encoding quirk introduced by an
 * app update would silently collapse a task's whole action list (a data-loss landmine 白い熊 hit). With
 * these flags a task decodes as long as its structure is broadly intact:
 *   - ignoreUnknownKeys: a field written by a newer build (or removed in this one) never throws.
 *   - isLenient:         tolerant of quoting/format drift.
 *   - coerceInputValues: an invalid/null value for a field with a default coerces to that default
 *                        instead of failing the whole list.
 * Encoding is symmetric — anything the old strict `Json` wrote still decodes here, and what this writes
 * stays plain JSON older builds can read.
 */
val StorageJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
