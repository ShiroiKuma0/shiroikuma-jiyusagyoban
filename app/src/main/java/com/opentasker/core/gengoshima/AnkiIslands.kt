package com.opentasker.core.gengoshima

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.core.storage.GengoshimaTombstoneEntity
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 言語島 ↔ 白い熊 暗記: the `Language Islands` notes kept in step with the islands.
 *
 * The door is 暗記's 保存復元 v2 data door with two methods of its own, `islands.list` and
 * `islands.sync` — `~/git/shiroikuma-anki/docs/sister-app-contract-anki-islands.md` is the contract
 * and wins over anything said here. In short: we open a file, hand 暗記 the descriptor, `call()`
 * answers `OK:<job_id>` at once, and exactly one broadcast carrying that id answers later.
 *
 * **Notes are keyed by the sentence's uuid** (the tag `li::uuid::<uuid>`) and updated in place, so
 * editing a sentence here never costs its review history there. What a sync sends is decided by
 * [GengoshimaSentenceEntity.ankiHash]: a sentence goes whenever what its note would carry — its
 * island, its two texts, its two recordings — differs from what 暗記 last took.
 */
object AnkiIslands {

    const val PACKAGE = "shiroikuma.anki"
    private val DOOR: Uri by lazy { Uri.parse("content://shiroikuma.anki.automation") }
    private const val REPLY_ACTION = "shiroikuma.jiyusagyoban.action.GENGOSHIMA_ANKI_REPLY"
    private const val PROGRESS_ACTION = "shiroikuma.jiyusagyoban.action.GENGOSHIMA_ANKI_PROGRESS"

    private val json = Json { ignoreUnknownKeys = true }

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    // ── what a note carries ─────────────────────────────────────────────────────────────────────

    /**
     * The deck leaf an island is filed under: its Japanese name, or the English until it has one.
     * `::` is Anki's deck separator, so a name holding it would make a subdeck — it is swapped for
     * the full-width colon, the same way the file names keep a forbidden character readable.
     */
    fun deckName(island: GengoshimaIslandEntity): String =
        island.nameJa.ifBlank { island.nameEn }.trim().replace("::", "：：").ifEmpty { island.uuid.take(8) }

    /** Ready for 暗記: translated, both texts present, and the Japanese voiced. */
    fun eligible(s: GengoshimaSentenceEntity): Boolean =
        s.state == GengoshimaSentenceEntity.STATE_READY && s.en.isNotBlank() && s.ja.isNotBlank() && s.audioPath.isNotEmpty()

    /** Everything the note carries; the position is not part of it — 暗記 never repositions a note. */
    fun ankiHash(s: GengoshimaSentenceEntity, island: GengoshimaIslandEntity): String =
        MessageDigest.getInstance("SHA-1")
            .digest("${island.uuid}|${s.en}|${s.ja}|${s.audioHash}|${s.enAudioHash}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ── the sync manifest ───────────────────────────────────────────────────────────────────────

    /** One sync, decided: the manifest and the audio files that go into the ZIP with it. */
    class Plan(
        val manifest: JsonObject,
        /** ZIP path → file on disk. */
        val files: Map<String, File>,
        /** Sentence id → the hash it is being sent at; written back for the ones 暗記 took. */
        val sent: Map<Long, String>,
        val tombstones: List<String>,
        /** Island uuid → deck leaf, as sent; remembered once 暗記 has taken them. */
        val islandNames: Map<String, String>,
        /** An island's name moved since 暗記 last took the names — worth a sync on its own. */
        val renamed: Boolean,
    ) {
        val sentences: Int get() = sent.size
        val empty: Boolean
            get() = sent.isEmpty() && tombstones.isEmpty() && !renamed
    }

    /**
     * Decide a sync.
     *
     * `delta` sends the sentences whose [ankiHash] moved; `full` sends every eligible sentence and
     * lets 暗記 delete any 言語島 note it holds that is not among them. Every island goes along every
     * time — there are a handful — so a rename always reaches its decks; [lastNames] (the names 暗記
     * last took) only decides whether a rename alone is worth a sync. A sentence still carrying the
     * note id it was adopted from, and never synced, goes in `adopt`.
     */
    fun plan(
        full: Boolean,
        s: GengoshimaSettings,
        islands: List<GengoshimaIslandEntity>,
        sentences: List<GengoshimaSentenceEntity>,
        tombstones: List<GengoshimaTombstoneEntity>,
        lastNames: Map<String, String>,
    ): Plan {
        val byId = islands.associateBy { it.id }
        val send = sentences.filter { eligible(it) && byId.containsKey(it.islandId) }
            .filter { full || it.ankiHash != ankiHash(it, byId.getValue(it.islandId)) }
        val files = LinkedHashMap<String, File>()
        val sent = LinkedHashMap<Long, String>()
        val names = islands.associate { it.uuid to deckName(it) }
        val renamed = names.any { (uuid, name) -> lastNames[uuid] != null && lastNames[uuid] != name }
        val manifest = buildJsonObject {
            put("format", "shiroikuma-anki-islands-sync")
            put("version", 1)
            put("mode", if (full) "full" else "delta")
            put("root", s.ankiRoot)
            put("recognition", s.ankiRecognition)
            put("production", s.ankiProduction)
            putJsonArray("islands") {
                islands.forEach { add(buildJsonObject { put("uuid", it.uuid); put("name", names.getValue(it.uuid)) }) }
            }
            putJsonArray("sentences") {
                for (row in send) {
                    val island = byId.getValue(row.islandId)
                    sent[row.id] = ankiHash(row, island)
                    add(
                        buildJsonObject {
                            put("uuid", row.uuid)
                            put("island", island.uuid)
                            put("position", row.position)
                            put("english", row.en.trim())
                            put("japanese", row.ja.trim())
                            File(row.audioPath).takeIf { it.isFile }?.let {
                                val p = "audio/${row.uuid}-ja.ogg"
                                files[p] = it
                                put("ja_audio", p)
                            }
                            File(row.enAudioPath).takeIf { row.enAudioPath.isNotEmpty() && it.isFile }?.let {
                                val p = "audio/${row.uuid}-en.ogg"
                                files[p] = it
                                put("en_audio", p)
                            }
                        },
                    )
                }
            }
            putJsonArray("deleted") { tombstones.filter { it.kind == "sentence" }.forEach { add(JsonPrimitive(it.uuid)) } }
            putJsonArray("deleted_islands") { tombstones.filter { it.kind == "island" }.forEach { add(JsonPrimitive(it.uuid)) } }
            putJsonObject("adopt") {
                send.filter { it.ankiNid != null && it.ankiHash.isEmpty() }.forEach { put(it.uuid, it.ankiNid) }
            }
        }
        return Plan(manifest, files, sent, tombstones.map { it.uuid }, names, renamed)
    }

    fun writeZip(plan: Plan, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(plan.manifest.toString().toByteArray())
            zip.closeEntry()
            for ((path, file) in plan.files) {
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ── the list ────────────────────────────────────────────────────────────────────────────────

    data class Note(val nid: Long, val english: String, val japanese: String, val decks: List<String>)

    /** Anki field HTML → the plain text 言語島 holds: line breaks become spaces, tags go, entities resolve. */
    fun plain(html: String): String =
        html.replace(Regex("(?i)<br\\s*/?>"), " ")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()

    fun parseList(text: String): List<Note> {
        val root = json.parseToJsonElement(text).jsonObject
        return root["notes"]?.jsonArray.orEmpty().map { el ->
            val o = el.jsonObject
            val f = o["fields"]?.jsonObject
            fun field(k: String) = f?.get(k)?.jsonPrimitive?.contentOrNull.orEmpty()
            Note(
                nid = o["nid"]!!.jsonPrimitive.longOrNull!!,
                english = plain(field("english")),
                japanese = plain(field("japanese")),
                decks = (o["cards"] as? JsonArray).orEmpty()
                    .sortedBy { it.jsonObject["ord"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
                    .mapNotNull { it.jsonObject["deck"]?.jsonPrimitive?.contentOrNull },
            )
        }.sortedBy { it.nid }
    }

    /**
     * The island a listed note belongs to: the leaf under `<root>::<recognition>::` of its first
     * card, else under `<root>::<production>::`, else simply the last part of the first card's deck.
     */
    fun islandOf(note: Note, s: GengoshimaSettings): String {
        val prefixes = listOf("${s.ankiRoot}::${s.ankiRecognition}::", "${s.ankiRoot}::${s.ankiProduction}::")
        for (p in prefixes) note.decks.firstOrNull { it.startsWith(p) }?.let { return it.removePrefix(p) }
        return note.decks.firstOrNull()?.substringAfterLast("::").orEmpty().ifEmpty { s.ankiRoot }
    }

    // ── the door ────────────────────────────────────────────────────────────────────────────────

    data class Reply(val result: String, val errors: List<Pair<String, String>> = emptyList()) {
        val ok: Boolean get() = result.startsWith("OK:")
    }

    /** `islands.list` into [into]; the JSON is read back from there once 暗記 says it is written. */
    suspend fun list(context: Context, into: File, timeoutMs: Long = 120_000): Reply =
        call(context, "islands.list", into, write = true, timeoutMs = timeoutMs) { _, _ -> }

    /** `islands.sync` from the ZIP [zip]; [onProgress] gets (done, total) as 暗記 reports it. */
    suspend fun sync(context: Context, zip: File, timeoutMs: Long = 600_000, onProgress: (Int, Int) -> Unit): Reply =
        call(context, "islands.sync", zip, write = false, timeoutMs = timeoutMs, onProgress = onProgress)

    private suspend fun call(
        context: Context,
        method: String,
        file: File,
        write: Boolean,
        timeoutMs: Long,
        onProgress: (Int, Int) -> Unit,
    ): Reply {
        val app = context.applicationContext
        val settled = CompletableDeferred<Reply>()
        val parked = HashMap<String, Reply>()
        var expected: String? = null
        val lock = Any()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                if (intent.action == PROGRESS_ACTION) {
                    // The v2 AutomationProgress triple; read whatever number type it arrives as.
                    @Suppress("DEPRECATION")
                    fun n(k: String) = intent.extras?.get(k)?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() } ?: -1
                    val done = n("current")
                    val total = n("total")
                    if (done >= 0) onProgress(done, total)
                    return
                }
                val result = intent.getStringExtra("result") ?: return
                val job = intent.getStringExtra("job_id").orEmpty()
                val errors = intent.getStringExtra("errors").orEmpty().lines().filter { it.isNotBlank() }
                    .map { it.substringBefore('\t') to it.substringAfter('\t', "") }
                val reply = Reply(result, errors)
                synchronized(lock) {
                    val want = expected
                    if (want != null && (job == want || job.isEmpty())) settled.complete(reply) else parked[job] = reply
                }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter().apply { addAction(REPLY_ACTION); addAction(PROGRESS_ACTION) },
            ContextCompat.RECEIVER_EXPORTED,
        )
        var fd: ParcelFileDescriptor? = null
        try {
            fd = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(
                    file,
                    if (write) ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
                    else ParcelFileDescriptor.MODE_READ_ONLY,
                )
            }
            val extras = Bundle().apply {
                putParcelable("fd", fd)
                putString("reply_action", REPLY_ACTION)
                putString("reply_package", app.packageName)
                putString("progress_action", PROGRESS_ACTION)
            }
            val answer = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.call(DOOR, method, null, extras)?.getString("result") }
                    .fold({ it ?: "ERROR:暗記 answered nothing" }, { "ERROR:暗記 unreachable — ${it.message}" })
            }
            if (!answer.startsWith("OK:")) return Reply(answer)
            val job = answer.removePrefix("OK:").trim()
            synchronized(lock) {
                expected = job
                (parked[job] ?: parked[""])?.let { settled.complete(it) }
            }
            return withTimeoutOrNull(timeoutMs) { settled.await() } ?: run {
                runCatching { app.contentResolver.call(DOOR, "cancel", null, Bundle().apply { putString("job_id", job) }) }
                Reply("ERROR:暗記 did not answer in ${timeoutMs / 1000}s")
            }
        } finally {
            runCatching { fd?.close() }
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
