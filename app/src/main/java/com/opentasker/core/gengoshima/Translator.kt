package com.opentasker.core.gengoshima

import com.opentasker.core.claude.ClaudeClient
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * English sentences → natural spoken Japanese, one island per call.
 *
 * An island is a MONOLOGUE: ~30 sentences in order, each following on from the last. So the call
 * carries the island's topic and register, and every sentence of it already translated, as context —
 * the new lines have to continue that speech, not be translated in isolation (白い熊, 2026-09-30).
 *
 * The answer is structured (a JSON schema): per sentence the Japanese and its word split, each word
 * with its dictionary form, its reading in katakana and a short gloss. The split is what the player
 * shows furigana from, what a tap looks up in 白い熊の辞書 (by the dictionary form), and what a
 * reading override attaches to. On the first call it also names the island in Japanese.
 */
object Translator {

    @Serializable
    data class Token(
        val surface: String,
        val base: String = "",
        val reading: String = "",
        val gloss: String = "",
        /** 白い熊's own reading for this word, in katakana; sent to VOICEVOX in place of [surface]. */
        val reading_override: String? = null,
    )

    @Serializable
    data class Line(val id: Long, val ja: String, val tokens: List<Token>)

    @Serializable
    data class Answer(val island_name_ja: String, val sentences: List<Line>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    internal val SYSTEM = """
        You translate for a learner of Japanese who studies with Mikel Hyperpolyglot's "language
        islands" method: they write short monologues in English about their own life, one topic per
        island, and memorise the Japanese by listening to it read aloud, shadowing it and recalling it.

        Translate each English sentence into the Japanese a native speaker would actually SAY in
        that situation — natural, spoken, idiomatic, never a word-for-word rendering. Keep the
        register the island names, the same all the way through. The sentences of an island form one
        continuous monologue in the order given: each line follows on from the ones before it, so use
        the earlier lines (given as context) for what is already established, drop what Japanese
        would leave unsaid, and keep the speaker's voice consistent. The speaker is the learner,
        talking about themselves.

        For every sentence, also split the Japanese into words, in order, covering the whole sentence
        exactly (punctuation as its own token with an empty reading). For each word give: surface
        (as written in your sentence), base (dictionary form), reading (katakana, for the surface as
        written), gloss (a few English words). Also give the island a short natural Japanese name.
    """.trimIndent()

    internal val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("island_name_ja") { put("type", "string") }
            putJsonObject("sentences") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "integer") }
                        putJsonObject("ja") { put("type", "string") }
                        putJsonObject("tokens") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    for (k in listOf("surface", "base", "reading", "gloss")) {
                                        putJsonObject(k) { put("type", "string") }
                                    }
                                }
                                putJsonArray("required") {
                                    for (k in listOf("surface", "base", "reading", "gloss")) add(kotlinx.serialization.json.JsonPrimitive(k))
                                }
                                put("additionalProperties", false)
                            }
                        }
                    }
                    putJsonArray("required") {
                        for (k in listOf("id", "ja", "tokens")) add(kotlinx.serialization.json.JsonPrimitive(k))
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") {
            add(kotlinx.serialization.json.JsonPrimitive("island_name_ja"))
            add(kotlinx.serialization.json.JsonPrimitive("sentences"))
        }
        put("additionalProperties", false)
    }

    /** The user turn: the island, what is already said, and what to translate now. */
    internal fun prompt(
        island: GengoshimaIslandEntity,
        all: List<GengoshimaSentenceEntity>,
        pending: List<GengoshimaSentenceEntity>,
    ): String = buildString {
        append("Island: ").append(island.nameEn).append('\n')
        if (island.nameJa.isNotBlank()) append("Its Japanese name: ").append(island.nameJa).append('\n')
        append("Register: ")
            .append(island.register.ifBlank { "natural everyday spoken Japanese, polite (です/ます) unless the content is clearly casual" })
            .append("\n\n")
        val pendingIds = pending.map { it.id }.toSet()
        val done = all.filter { it.id !in pendingIds && it.ja.isNotBlank() }
        if (done.isNotEmpty()) {
            append("The monologue so far (already translated — context only, do not return these):\n")
            done.forEach { append("[").append(it.position).append("] ").append(it.en).append(" → ").append(it.ja).append('\n') }
            append('\n')
        }
        append("Translate these, in this order (return each with its id):\n")
        pending.forEach { append("id ").append(it.id).append(" [").append(it.position).append("] ").append(it.en).append('\n') }
    }

    /**
     * How many sentences the model has FINISHED writing, read off the half-written answer.
     *
     * Each sentence's `"ja"` string is complete once its closing quote has arrived, and the answer
     * streams in order, so counting completed `"ja"` strings counts sentences done — which is what
     * turns a thirty-second silence into "7 / 20" (白い熊, 2026-09-30). The last one is returned too,
     * so the window can show the line that has just been written.
     */
    internal fun completed(partial: String): Pair<Int, String?> {
        val found = JA.findAll(partial).toList()
        val last = found.lastOrNull()?.groupValues?.get(1)?.let { raw ->
            runCatching { json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrNull()
        }
        return found.size to last
    }

    private val JA = Regex("\"ja\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Translate [pending] (at most a chunk's worth — see the runner), streamed: [onLive] hears the
     * thinking summary and each sentence as it is written.
     */
    suspend fun translate(
        island: GengoshimaIslandEntity,
        all: List<GengoshimaSentenceEntity>,
        pending: List<GengoshimaSentenceEntity>,
        settings: GengoshimaSettings,
        onLive: suspend (ClaudeClient.Live) -> Unit = {},
    ): Answer {
        val reply = ClaudeClient.stream(
            ClaudeClient.Request(
                apiKey = settings.apiKey,
                system = SYSTEM,
                prompt = prompt(island, all, pending),
                model = settings.model,
                effort = settings.effort,
                schema = SCHEMA,
                maxTokens = 32_000,
                thinkingSummary = true,
            ),
            onLive,
        )
        if (!reply.complete) {
            throw IllegalStateException(
                when (reply.stopReason) {
                    "refusal" -> "Claude declined (${reply.refusal.orEmpty()})"
                    "max_tokens" -> "the answer was cut off — too many sentences in one island at once"
                    else -> "Claude stopped: ${reply.stopReason}"
                },
            )
        }
        return json.decodeFromString(Answer.serializer(), reply.text)
    }

    internal val ANNOTATE_SYSTEM = """
        The learner has written or corrected these Japanese sentences by hand. Do NOT change them:
        return each one's Japanese exactly as given, character for character. Only split each into
        words, in order, covering the whole sentence exactly (punctuation as its own token with an
        empty reading), and for each word give: surface (as written), base (dictionary form),
        reading (katakana, for the surface as written), gloss (a few English words). Return
        island_name_ja as an empty string.
    """.trimIndent()

    /**
     * Re-split hand-edited Japanese into words — the text itself is 白い熊's and is not touched.
     * A reading override survives wherever its word still appears with the same surface.
     */
    suspend fun annotate(
        sentences: List<GengoshimaSentenceEntity>,
        settings: GengoshimaSettings,
        onLive: suspend (ClaudeClient.Live) -> Unit = {},
    ): Map<Long, List<Token>> {
        val prompt = buildString {
            append("Split these (return each with its id):\n")
            sentences.forEach { append("id ").append(it.id).append(": ").append(it.ja).append('\n') }
        }
        val reply = ClaudeClient.stream(
            ClaudeClient.Request(
                apiKey = settings.apiKey, system = ANNOTATE_SYSTEM, prompt = prompt,
                model = settings.model, effort = "medium", schema = SCHEMA, maxTokens = 32_000,
            ),
            onLive,
        )
        if (!reply.complete) throw IllegalStateException("Claude stopped: ${reply.stopReason}")
        val answer = json.decodeFromString(Answer.serializer(), reply.text)
        return sentences.associate { s ->
            val kept = tokens(s.tokensJson).filter { !it.reading_override.isNullOrBlank() }.associateBy { it.surface }
            val fresh = answer.sentences.firstOrNull { it.id == s.id }?.tokens.orEmpty()
            s.id to fresh.map { t -> kept[t.surface]?.let { t.copy(reading_override = it.reading_override) } ?: t }
        }
    }

    fun tokensJson(tokens: List<Token>): String = json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(Token.serializer()),
        tokens,
    )

    fun tokens(tokensJson: String): List<Token> =
        if (tokensJson.isBlank()) emptyList()
        else runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Token.serializer()), tokensJson)
        }.getOrDefault(emptyList())

    /**
     * What VOICEVOX is given to read: the Japanese with every reading-overridden word replaced by its
     * katakana, which VOICEVOX reads verbatim. Without overrides — or if the words no longer spell
     * the sentence, after a hand edit — it is the Japanese as it stands.
     */
    fun speechText(ja: String, tokensJson: String): String {
        val tokens = tokens(tokensJson)
        if (tokens.none { !it.reading_override.isNullOrBlank() }) return ja
        if (tokens.joinToString("") { it.surface } != ja) return ja
        return tokens.joinToString("") { t -> t.reading_override?.takeIf { it.isNotBlank() } ?: t.surface }
    }
}
