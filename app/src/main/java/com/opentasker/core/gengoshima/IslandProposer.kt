package com.opentasker.core.gengoshima

import com.opentasker.core.claude.ClaudeClient
import com.opentasker.core.storage.GengoshimaInboxEntity
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Which island does each walk-captured sentence belong to? One Claude call per inbox batch.
 *
 * A walk produces mixed topics, so the sentences arrive in the 未分類 inbox, not in an island. Claude
 * sees every island (English topic, register, a few of its sentences) and answers, per sentence, an
 * existing island's id — or 0 with a new island's English topic and register. Sentences that belong
 * together get the SAME new topic, so a fresh subject becomes one island, not one per line. It is only
 * a proposal: the 未分類 page shows it beside each sentence and 白い熊 changes what is wrong.
 */
object IslandProposer {

    @Serializable
    data class Pick(val uuid: String, val island_id: Long, val new_name: String = "", val new_register: String = "")

    @Serializable
    data class Answer(val picks: List<Pick>)

    private val json = Json { ignoreUnknownKeys = true }

    internal val SYSTEM = """
        You help a learner of Japanese who studies with Mikel Hyperpolyglot's "language islands"
        method: short monologues in English about their own life, one topic per island, later
        translated into Japanese and memorised by listening.

        The learner spoke new sentences on a walk, whatever came to mind, so they are on mixed topics.
        For each new sentence, choose the existing island whose topic it continues best (its id). If
        none fits well, answer island_id 0 and propose a new island: new_name a short English topic in
        the style of the existing names, new_register the Japanese register it should be spoken in
        (in the style of the existing registers). New sentences on the same new subject must get
        exactly the same new_name and new_register, so they form one island together. Leave new_name
        and new_register empty when island_id is not 0. Answer every sentence, by its uuid.
    """.trimIndent()

    internal val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("picks") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("uuid") { put("type", "string") }
                        putJsonObject("island_id") { put("type", "integer") }
                        putJsonObject("new_name") { put("type", "string") }
                        putJsonObject("new_register") { put("type", "string") }
                    }
                    putJsonArray("required") {
                        for (k in listOf("uuid", "island_id", "new_name", "new_register")) add(JsonPrimitive(k))
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("picks")) }
        put("additionalProperties", false)
    }

    internal fun prompt(
        islands: List<GengoshimaIslandEntity>,
        sentences: Map<Long, List<GengoshimaSentenceEntity>>,
        inbox: List<GengoshimaInboxEntity>,
    ): String = buildString {
        if (islands.isEmpty()) {
            append("There are no islands yet — propose new ones.\n\n")
        } else {
            append("The existing islands:\n")
            islands.forEach { isl ->
                append("id ").append(isl.id).append(": ").append(isl.nameEn)
                if (isl.register.isNotBlank()) append(" — register: ").append(isl.register)
                append('\n')
                sentences[isl.id].orEmpty().take(SAMPLES).forEach { append("    · ").append(it.en).append('\n') }
            }
            append('\n')
        }
        append("New sentences, in the order spoken:\n")
        inbox.forEach { append(it.uuid).append(": ").append(it.en).append('\n') }
    }

    /** Ask Claude; returns uuid → pick. Picks naming an island that does not exist become "new". */
    suspend fun propose(
        islands: List<GengoshimaIslandEntity>,
        sentences: Map<Long, List<GengoshimaSentenceEntity>>,
        inbox: List<GengoshimaInboxEntity>,
        settings: GengoshimaSettings,
    ): Map<String, Pick> {
        if (inbox.isEmpty()) return emptyMap()
        val reply = ClaudeClient.send(
            ClaudeClient.Request(
                apiKey = settings.apiKey,
                system = SYSTEM,
                prompt = prompt(islands, sentences, inbox),
                model = settings.model,
                effort = "medium",
                schema = SCHEMA,
                maxTokens = 16_000,
            ),
        )
        if (!reply.complete) throw IllegalStateException("Claude stopped: ${reply.stopReason}")
        return parse(reply.text, islands.map { it.id }.toSet())
    }

    internal fun parse(text: String, islandIds: Set<Long>): Map<String, Pick> =
        json.decodeFromString(Answer.serializer(), text).picks.associate { p ->
            val fixed = if (p.island_id != 0L && p.island_id !in islandIds) {
                p.copy(island_id = 0, new_name = p.new_name.ifBlank { "New island" })
            } else p
            fixed.uuid to fixed
        }

    private const val SAMPLES = 4
}
