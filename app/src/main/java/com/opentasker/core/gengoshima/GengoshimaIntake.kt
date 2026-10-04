package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaDao
import com.opentasker.core.storage.GengoshimaInboxEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `gengoshima_intake`: reviewed walk-capture sentences from 白い熊 kxkb land in the 未分類 inbox
 * (docs/sister-app-contract-kxkb-gengoshima.md §2).
 *
 * The answer lists every uuid that is durably in the inbox — newly stored OR already there, filed or
 * not — so kxkb's outbox may retry freely and a retry is harmless. Nothing is translated here; the
 * 未分類 page files the sentences into islands and the normal run translates them.
 */
object GengoshimaIntake {
    const val METHOD = "gengoshima_intake"
    const val MAX_ITEMS = 50

    /** Parse the `items` JSON; null when it is not a usable array (→ `ERROR:items`). */
    internal fun parse(raw: String?, now: Long): List<GengoshimaInboxEntity>? {
        val arr = runCatching { Json.parseToJsonElement(raw ?: return null) as? JsonArray }.getOrNull() ?: return null
        if (arr.size > MAX_ITEMS) return null
        return arr.map { el ->
            val o = el as? JsonObject ?: return null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
            val uuid = str("uuid").orEmpty()
            val text = str("text").orEmpty()
            if (uuid.isEmpty() || text.isEmpty()) return null
            GengoshimaInboxEntity(
                uuid = uuid,
                en = text,
                recognized = str("recognized") ?: text,
                language = str("language").orEmpty().ifBlank { "en" },
                capturedAt = (o["capturedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull ?: now,
                receivedAt = now,
            )
        }
    }

    /** Store and answer. Returns the provider's `result` string. */
    suspend fun accept(dao: GengoshimaDao, raw: String?, now: Long = System.currentTimeMillis()): String {
        val rows = parse(raw, now) ?: return "ERROR:items"
        if (rows.isEmpty()) return "OK:"
        dao.insertInbox(rows)
        val present = dao.inboxPresent(rows.map { it.uuid }).toSet()
        return "OK:" + rows.map { it.uuid }.filter { it in present }.joinToString(",")
    }
}
