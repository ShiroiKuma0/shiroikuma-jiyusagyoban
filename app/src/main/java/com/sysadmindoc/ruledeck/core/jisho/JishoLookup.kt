package com.sysadmindoc.ruledeck.core.jisho

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One term as 白い熊の辞書 knows it — an item of the lookup door's answer
 * (`~/git/shiroikuma-jisho/docs/sister-app-contract-jisho-lookup.md`, contract version 1). Stored
 * verbatim per term in `gengoshima_vocab.jishoJson`; unknown keys are ignored, as the contract asks.
 */
@Serializable
data class JishoEntry(
    val term: String = "",
    val reading: String = "",
    val found: Boolean = false,
    val headword: String = "",
    @SerialName("headword_reading") val headwordReading: String = "",
    /** Downstep numbers, 0 = 平板; at most two. */
    val pitch: List<Int> = emptyList(),
    val forms: List<String> = emptyList(),
    val pos: List<String> = emptyList(),
    val glosses: List<Glosses> = emptyList(),
    val frequency: Int? = null,
    @SerialName("frequency_dictionary") val frequencyDictionary: String? = null,
    val tags: List<Tag> = emptyList(),
    val kanji: List<Kanji> = emptyList(),
    val examples: List<Example> = emptyList(),
    @SerialName("conjugation_class") val conjugationClass: String = "",
    val conjugation: List<Conjugation> = emptyList(),
) {
    @Serializable data class Glosses(val dictionary: String = "", val senses: List<String> = emptyList())
    @Serializable data class Tag(val name: String = "", val notes: String = "")
    @Serializable
    data class Kanji(
        val char: String = "",
        val meanings: List<String> = emptyList(),
        val on: List<String> = emptyList(),
        val kun: List<String> = emptyList(),
        val strokes: Int? = null,
        val grade: Int? = null,
        val jlpt: Int? = null,
        val frequency: Int? = null,
    )
    @Serializable data class Example(val ja: String = "", val en: String = "")
    @Serializable
    data class Conjugation(
        val form: String = "",
        val plain: String = "",
        @SerialName("plain_negative") val plainNegative: String = "",
        val polite: String = "",
        @SerialName("polite_negative") val politeNegative: String = "",
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** The cached answer of one term, or null when there is none (or it does not parse). */
        fun parse(stored: String): JishoEntry? =
            stored.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The caller's side of 辞書's lookup door: `ContentResolver.call` on
 * `content://shiroikuma.jisho.lookup`, the answer written by 辞書 into a regular file of ours (never
 * a pipe — the call is synchronous, so nothing would drain it).
 *
 * The call cannot be cancelled and has no timeout of its own, so each one runs on an IO thread and
 * is given up on at the contract's deadline; 辞書 abandons a batch itself after 90 s.
 */
object JishoLookup {

    private const val PACKAGE = "shiroikuma.jisho"
    private val URI: Uri = Uri.parse("content://shiroikuma.jisho.lookup")
    private const val META_CONTRACT = "shiroikuma.jisho.lookup.contract"
    private const val DESCRIBE_MS = 45_000L
    private const val LOOKUP_MS = 130_000L
    const val MAX_ITEMS = 100

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the installed 辞書 has the door — read from its manifest, without waking it. */
    fun supported(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(PACKAGE, PackageManager.GET_META_DATA)
        (info.metaData?.get(META_CONTRACT)?.toString()?.toIntOrNull() ?: 0) >= 1
    }.getOrDefault(false)

    sealed interface Answer {
        /** One raw JSON object per requested item, in request order. */
        data class Ok(val items: List<JsonObject>) : Answer
        data class Failed(val reason: String, val retryable: Boolean) : Answer
    }

    data class Item(val term: String, val reading: String)

    /** `describe`, as the JSON 辞書 answers with — the first real test of the door. */
    suspend fun describe(context: Context): Result<JsonObject> = runCatching {
        val line = call(context, "describe", null, DESCRIBE_MS)
        require(line.startsWith("OK:")) { line }
        json.parseToJsonElement(line.removePrefix("OK:")) as JsonObject
    }

    /** One batch of at most [MAX_ITEMS]. */
    suspend fun lookup(context: Context, items: List<Item>): Answer {
        require(items.size in 1..MAX_ITEMS) { "1–$MAX_ITEMS items per batch" }
        val request = buildJsonObject {
            put("language", "ja")
            put("items", buildJsonArray { items.forEach { add(buildJsonObject { put("term", it.term); put("reading", it.reading) }) } })
        }.toString()
        val file = File(context.cacheDir, "jisho-lookup.json")
        return try {
            val line = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { pfd ->
                call(context, "lookup", Bundle().apply { putString("request", request); putParcelable("fd", pfd) }, LOOKUP_MS)
            }
            if (!line.startsWith("OK:")) {
                val reason = line.removePrefix("ERROR:")
                return Answer.Failed(reason, retryable = reason.startsWith("timeout") || reason.startsWith("startup failed"))
            }
            val root = json.parseToJsonElement(withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }) as JsonObject
            val answered = (root["items"] as? JsonArray)?.map { it as JsonObject }.orEmpty()
            if (answered.size != items.size) Answer.Failed("answered ${answered.size} of ${items.size} items", retryable = false)
            else Answer.Ok(answered)
        } catch (e: Exception) {
            Answer.Failed(e.message ?: e.toString(), retryable = e is android.os.DeadObjectException)
        } finally {
            file.delete()
        }
    }

    private suspend fun call(context: Context, method: String, extras: Bundle?, timeoutMs: Long): String = coroutineScope {
        val resolver = context.contentResolver
        // Not a child of this scope: the binder call cannot be interrupted, so past the deadline
        // it is left to finish on its own thread instead of holding this coroutine with it.
        val pending = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
            runCatching { resolver.call(URI, method, null, extras)?.getString("result") ?: "ERROR:no answer" }
                .getOrElse { "ERROR:${it.javaClass.simpleName}: ${it.message}" }
        }
        withTimeoutOrNull(timeoutMs) { pending.await() } ?: "ERROR:timeout after $timeoutMs ms (our side)"
    }
}
