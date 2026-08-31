package com.opentasker.core.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.opentasker.core.storage.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full app-state export/import — the Kōjiki-style category ZIP (白い熊 2026-07-25).
 *
 * The export is a ZIP of plain JSON files — one per category — plus imported font files under
 * `fonts/` and per-task icons under `icons/`. No binary blobs, no `.db` files. A `manifest.json`
 * lists format, version, and the categories present. Every category is an independent entry:
 * import iterates the categories present in the archive, skips absent ones, and merges
 * SharedPreferences (never clears), so an old export stays importable forever.
 *
 * `workspace.json` is the standard "export everything" [OpenTaskerBundle] — byte-identical in
 * format to the manual Export All JSON, so the archive doubles as a normal workspace backup:
 * the "+" Import JSON flow accepts the whole ZIP and imports that entry.
 */
object SettingsBackup {

    const val FORMAT = "jiyusagyoban-export"
    const val VERSION = 1

    /**
     * Export file name prefix — the page's latest-export query matches `PREFIX*.zip`.
     * The family convention (白い熊, 2026-07-25) is the app's English dash-separated name plus a
     * datetime and NO version, so every sister app's backups sort and read alike:
     * `shiroikuma-jiyusagyoban_2026-07-25_18-58-23.zip`.
     */
    const val EXPORT_PREFIX = "shiroikuma-jiyusagyoban_"

    /** Pre-2026-07-25 name (`白い熊 自由作業盤-<version>-export_<stamp>.zip`), still recognised. */
    const val LEGACY_EXPORT_PREFIX = "白い熊 自由作業盤-"

    /** The workspace bundle entry inside the ZIP (category [Cat.WORKSPACE]). */
    const val WORKSPACE_ENTRY = "workspace.json"

    private const val FONTS_DIR = "fonts"
    private const val ICONS_DIR = "icons"
    private const val MAX_ENTRY_BYTES = 48 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256 * 1024 * 1024

    /** A selectable export/import category. [id] is the JSON entry name (`<id>.json`) in the ZIP. */
    /**
     * [defaultSelected] is the fourth field of the `LIST_CATEGORIES` reply — whether the item starts
     * ticked in 保存復元's picker. Mark `false` only what is large, derived AND re-creatable (a
     * regenerable cache); everything this app exports is authored, so all of it stays on.
     */
    enum class Cat(val id: String, val label: String, val defaultSelected: Boolean = true) {
        WORKSPACE("workspace", "Workspace programming — projects · tasks · profiles · scenes · variables"),
        APPEARANCE("appearance", "UI theme (colours · fonts)"),
        WIDGETS("widgets", "Widgets (templates · bindings)"),
        BUBBLES("bubbles", "Bubbles (freeze · flash)"),
        APP_SETTINGS("app_settings", "App settings (sort · projects · logs · picker)"),
        SHARE_TILES("share_tiles", "Share tiles (共有アプリ工房)"),
        TASK_ICONS("task_icons", "Task icons");

        companion object {
            fun byId(id: String): Cat? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * SharedPreferences files per category. Runtime state (cooldowns, heartbeat, dwell, permission
     * history) and security grants (Locale tokens, Termux allowlist) are deliberately NOT exported.
     */
    private val PREF_FILES: Map<Cat, List<String>> = mapOf(
        Cat.APPEARANCE to listOf("shiroikuma_ui_theme"),
        Cat.WIDGETS to listOf("shiroikuma_widget_templates", "shiroikuma_styled_widgets", "opentasker_widget_prefs"),
        Cat.BUBBLES to listOf("shiroikuma_freeze_bubbles", "shiroikuma_flash_bubbles"),
        Cat.APP_SETTINGS to listOf(
            "shiroikuma_list_sort", "project_selection", "run_log_retention",
            "auto_start_settings", "app_picker_prefs", "shiroikuma_runlog_seen", "ui_state",
        ),
        Cat.SHARE_TILES to listOf("shiroikuma_share_relays"),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun exportFileName(): String =
        EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

    // ---- directory + latest-export query ----------------------------------------------------

    private const val EXIMPORT_PREFS = "jiyusagyoban_eximport" // device-local; never exported
    private const val KEY_DIR_URI = "dir_uri"

    fun dirUri(context: Context): Uri? =
        context.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DIR_URI, null)?.let(Uri::parse)

    fun setDirUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        context.getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    fun exportDir(context: Context): DocumentFile? =
        dirUri(context)?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
            ?.takeIf { it.isDirectory }

    /** Display name of the chosen directory, or null when unset/unreachable. */
    fun dirLabel(context: Context): String? =
        exportDir(context)?.name ?: dirUri(context)?.lastPathSegment

    data class LatestExport(val file: DocumentFile, val timestampText: String)

    /** Newest `PREFIX*.zip` in the chosen directory (by mtime), or null. */
    fun latestExport(context: Context): LatestExport? {
        val dir = exportDir(context) ?: return null
        val newest = runCatching {
            dir.listFiles().filter { file ->
                val name = file.name.orEmpty()
                file.isFile && name.endsWith(".zip") &&
                    (name.startsWith(EXPORT_PREFIX) || name.startsWith(LEGACY_EXPORT_PREFIX))
            }.maxByOrNull { it.lastModified() }
        }.getOrNull() ?: return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(newest.lastModified()))
        return LatestExport(newest, stamp)
    }

    // ---- export ------------------------------------------------------------------------------

    /**
     * Writes the selected categories to [output]. Returns a one-line summary. [onProgress]
     * (done, total, category label) fires after each written category — the automation bridge
     * (StateExportReceiver) forwards it as contract progress broadcasts; UI callers omit it.
     *
     * [isCancelled] is polled at each **category boundary** and, when it answers true, throws
     * [ExportCancelledException]. Checking between entries rather than mid-`write()` is the contract's
     * requirement and the safe reading: the stream unwinds normally and the caller deletes the partial,
     * instead of a thread being interrupted with a half-flushed ZIP behind it.
     */
    suspend fun export(
        context: Context,
        db: AppDatabase,
        appVersion: String,
        cats: Set<Cat>,
        output: OutputStream,
        onProgress: ((done: Int, total: Int, catLabel: String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): String {
        var count = 0
        val total = Cat.entries.count { it in cats }
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = buildJsonObject {
                put("format", JsonPrimitive(FORMAT))
                put("version", JsonPrimitive(VERSION))
                put("app", JsonPrimitive(context.packageName))
                put("appVersion", JsonPrimitive(appVersion))
                put("createdTs", JsonPrimitive(System.currentTimeMillis()))
                put("categories", buildJsonArray { cats.forEach { add(JsonPrimitive(it.id)) } })
            }
            writeEntry(zip, "manifest.json", json.encodeToString(JsonObject.serializer(), manifest).toByteArray())

            for (cat in Cat.entries.filter { it in cats }) {
                if (isCancelled?.invoke() == true) throw ExportCancelledException()
                when (cat) {
                    Cat.WORKSPACE -> {
                        val bundle = OpenTaskerBundleRepository(db).exportBundle(
                            appVersion = appVersion,
                            name = "白い熊 自由作業盤 Workspace Export",
                            description = "Profiles, tasks, variables, and scenes exported from 白い熊 自由作業盤.",
                        )
                        writeEntry(zip, WORKSPACE_ENTRY, OpenTaskerBundleCodec.encode(bundle).toByteArray())
                    }
                    Cat.TASK_ICONS -> exportDirFiles(zip, File(context.filesDir, "task_icons"), ICONS_DIR)
                    else -> {
                        writeEntry(zip, "${cat.id}.json", exportPrefs(context, PREF_FILES.getValue(cat)))
                        if (cat == Cat.APPEARANCE) exportDirFiles(zip, File(context.filesDir, "fonts"), FONTS_DIR)
                    }
                }
                count++
                onProgress?.invoke(count, total, cat.label)
            }
        }
        return "$count categor${if (count == 1) "y" else "ies"}"
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun exportDirFiles(zip: ZipOutputStream, dir: File, entryDir: String) {
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            writeEntry(zip, "$entryDir/${file.name}", file.readBytes())
        }
    }

    /** Type-tagged dump of the named SharedPreferences files: `{file: {key: {"t":…, "v":…}}}`. */
    private fun exportPrefs(context: Context, files: List<String>): ByteArray {
        val root = buildJsonObject {
            files.forEach { name ->
                val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                put(name, buildJsonObject {
                    sp.all.forEach { (key, value) ->
                        val tagged: JsonObject? = when (value) {
                            is Boolean -> typed("b", JsonPrimitive(value))
                            is Int -> typed("i", JsonPrimitive(value))
                            is Long -> typed("l", JsonPrimitive(value))
                            is Float -> typed("f", JsonPrimitive(value))
                            is String -> typed("s", JsonPrimitive(value))
                            is Set<*> -> typed("ss", buildJsonArray {
                                value.filterIsInstance<String>().forEach { add(JsonPrimitive(it)) }
                            })
                            else -> null
                        }
                        if (tagged != null) put(key, tagged)
                    }
                })
            }
        }
        return json.encodeToString(JsonObject.serializer(), root).toByteArray()
    }

    private fun typed(t: String, v: JsonElement): JsonObject =
        buildJsonObject { put("t", JsonPrimitive(t)); put("v", v) }

    // ---- import ------------------------------------------------------------------------------

    data class ImportResult(val summaryLines: List<String>, val restartNeeded: Boolean)

    /** Which categories the archive at [bytes] carries (manifest first, entry names as fallback). */
    fun categoriesIn(bytes: ByteArray): Set<Cat> {
        val entries = readZip(ByteArrayInputStream(bytes))
        val fromManifest = entries["manifest.json"]?.let { m ->
            runCatching {
                json.parseToJsonElement(m.toString(Charsets.UTF_8)).jsonObject["categories"]
                    ?.jsonArray?.mapNotNull { Cat.byId(it.jsonPrimitive.contentOrNull ?: "") }?.toSet()
            }.getOrNull()
        }
        if (!fromManifest.isNullOrEmpty()) return fromManifest
        return Cat.entries.filter { cat ->
            when (cat) {
                Cat.WORKSPACE -> WORKSPACE_ENTRY in entries
                Cat.TASK_ICONS -> entries.keys.any { it.startsWith("$ICONS_DIR/") }
                else -> "${cat.id}.json" in entries
            }
        }.toSet()
    }

    /** Applies the selected categories from the archive. Returns per-category summary lines. */
    suspend fun import(
        context: Context,
        db: AppDatabase,
        bytes: ByteArray,
        cats: Set<Cat>,
    ): ImportResult {
        val entries = readZip(ByteArrayInputStream(bytes))
        val lines = mutableListOf<String>()
        var restartNeeded = false

        for (cat in Cat.entries.filter { it in cats }) {
            when (cat) {
                Cat.WORKSPACE -> {
                    val raw = entries[WORKSPACE_ENTRY] ?: continue
                    val bundle = OpenTaskerBundleCodec.decode(raw.toString(Charsets.UTF_8))
                    val report = OpenTaskerBundleRepository(db).importBundle(bundle)
                    lines += "${cat.label}: ${report.insertedTasks} tasks · ${report.insertedProfiles} profiles · " +
                        "${report.insertedScenes} scenes · ${report.insertedVariables} variables"
                }
                Cat.TASK_ICONS -> {
                    val n = importDirFiles(entries, ICONS_DIR, File(context.filesDir, "task_icons"))
                    if (n >= 0) { lines += "${cat.label}: $n"; }
                }
                else -> {
                    val raw = entries["${cat.id}.json"] ?: continue
                    val n = importPrefs(context, raw)
                    if (cat == Cat.APPEARANCE) importDirFiles(entries, FONTS_DIR, File(context.filesDir, "fonts"))
                    lines += "${cat.label}: $n keys"
                    restartNeeded = true
                }
            }
        }
        if (lines.isEmpty()) error("No selected categories found in this archive.")
        return ImportResult(lines, restartNeeded)
    }

    /** Restores files under `entryDir/` in the archive into [dest] (basename only — no traversal). */
    private fun importDirFiles(entries: Map<String, ByteArray>, entryDir: String, dest: File): Int {
        val files = entries.filterKeys { it.startsWith("$entryDir/") }
        if (files.isEmpty()) return -1
        dest.mkdirs()
        var n = 0
        files.forEach { (name, bytes) ->
            val base = File(name).name
            if (base.isNotEmpty()) {
                File(dest, base).writeBytes(bytes)
                n++
            }
        }
        return n
    }

    /** Merges a type-tagged prefs dump back — never clears, so missing keys keep current values. */
    private fun importPrefs(context: Context, raw: ByteArray): Int {
        val root = json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        var n = 0
        root.forEach { (file, values) ->
            val ed = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit() // merge — never clear
            values.jsonObject.forEach inner@{ (key, tagged) ->
                val obj = tagged as? JsonObject ?: return@inner
                val t = obj["t"]?.jsonPrimitive?.contentOrNull ?: return@inner
                val v = obj["v"] ?: return@inner
                runCatching {
                    when (t) {
                        "b" -> ed.putBoolean(key, v.jsonPrimitive.boolean)
                        "i" -> ed.putInt(key, v.jsonPrimitive.long.toInt())
                        "l" -> ed.putLong(key, v.jsonPrimitive.long)
                        "f" -> ed.putFloat(key, v.jsonPrimitive.double.toFloat())
                        "s" -> ed.putString(key, v.jsonPrimitive.contentOrNull)
                        "ss" -> ed.putStringSet(
                            key,
                            (v as JsonArray).mapNotNull { it.jsonPrimitive.contentOrNull }.toSet(),
                        )
                        else -> return@inner
                    }
                    n++
                }
            }
            ed.apply()
        }
        return n
    }

    // ---- zip helpers -------------------------------------------------------------------------

    /** True when [bytes] starts with the ZIP local-file magic (`PK\x03\x04`). */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * The workspace-bundle JSON inside a settings ZIP — [WORKSPACE_ENTRY], with any root `*.json`
     * that is not a manifest or a category prefs dump as fallback (a hand-repacked archive).
     */
    fun workspaceJsonFrom(bytes: ByteArray): String {
        val entries = readZip(ByteArrayInputStream(bytes))
        val exact = entries[WORKSPACE_ENTRY]
        if (exact != null) return exact.toString(Charsets.UTF_8)
        val known = setOf("manifest.json") + Cat.entries.map { "${it.id}.json" }
        val fallback = entries.entries.firstOrNull { (name, _) ->
            name.endsWith(".json") && !name.contains('/') && name !in known
        } ?: error("This ZIP carries no workspace export ($WORKSPACE_ENTRY).")
        return fallback.value.toString(Charsets.UTF_8)
    }

    /** All entries into memory, bounded per-entry and in total. */
    fun readZip(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(chunk)
                    if (read == -1) break
                    entryBytes += read
                    total += read
                    require(entryBytes <= MAX_ENTRY_BYTES) { "archive entry too large: ${entry.name}" }
                    require(total <= MAX_TOTAL_BYTES) { "archive too large" }
                    buffer.write(chunk, 0, read)
                }
                out[entry.name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }
        return out
    }
}
