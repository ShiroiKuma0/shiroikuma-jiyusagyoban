package com.opentasker.core.huawei

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * The watch faces kept on disk, one ZIP each.
 *
 * Most entries were captured from Huawei's store, and a few are built here — 相撲字時計 is one, made
 * out of MZ DIGICOLOR by `scripts/sumoji-build.py`. Either way an entry is a small archive of
 * everything the face needs to be installed again:
 *
 * ```
 * <display name>.zip
 * ├── <assetId>_<version>.bin     the face
 * ├── <assetId>_<version>.json    the store record, signed by Huawei
 * ├── preview.png                 cropped from Huawei Health's page
 * └── face.json                   {name, assetId, version, capturedAt}
 * ```
 *
 * The ZIP is named for the face so the directory reads sensibly, but the name is **also** inside
 * `face.json`, because a filename has to be sanitised and that is lossy. Anything shown to 白い熊
 * comes from `face.json`; the filename is only an address.
 *
 * **The store record contains personal identifiers** — a hash of 白い熊's Huawei account and the
 * band's own device id. These archives are private, and nothing here should ever copy one somewhere
 * it would be published.
 */
object HuaweiFaceLibrary {

    /**
     * What `face.json` carries.
     *
     * Deliberately kotlinx and NOT `org.json`: Android's `org.json` is a stub on the JVM test
     * classpath that quietly returns nothing, so a reader built on it passes its own tests by
     * finding no faces at all.
     */
    @Serializable
    private data class Manifest(
        val name: String = "",
        val assetId: String = "",
        val version: String = "",
        val capturedAt: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** One face on disk, as far as the picker needs to know. */
    data class Entry(
        val zip: File,
        val name: String,
        val assetId: String,
        val version: String,
    ) {
        val id: String get() = "${assetId}_$version"
    }

    /** What a face weighs before it is worth showing a thumbnail for. */
    private const val MIN_FACE_BYTES = 1024

    /**
     * Every readable face in [dir], by name.
     *
     * A ZIP that does not parse is skipped rather than shown broken: the directory is 白い熊's, and
     * something unrelated landing in it should cost nothing.
     */
    fun list(dir: File): List<Entry> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) } ?: emptyArray())
            .mapNotNull { read(it) }
            .sortedBy { it.name.lowercase() }

    fun read(zip: File): Entry? = runCatching {
        ZipFile(zip).use { z ->
            val manifest = z.getEntry("face.json") ?: return null
            val j = json.decodeFromString(
                Manifest.serializer(),
                z.getInputStream(manifest).bufferedReader().readText(),
            )
            val asset = j.assetId.ifEmpty { return null }
            val version = j.version.ifEmpty { return null }
            // The face itself must be present and plausible; a manifest alone is not a face.
            val bin = z.getEntry("${asset}_$version.bin") ?: return null
            if (bin.size in 0 until MIN_FACE_BYTES) return null
            Entry(zip, j.name.ifEmpty { "${asset}_$version" }, asset, version)
        }
    }.getOrNull()

    /** The preview image bytes, or null when the archive carries none. */
    fun preview(zip: File): ByteArray? = runCatching {
        ZipFile(zip).use { z ->
            z.getEntry("preview.png")?.let { z.getInputStream(it).readBytes() }
        }
    }.getOrNull()

    /**
     * Unpack the two files an install needs into [workDir], and return the `.bin`.
     *
     * `HuaweiSyncRunner.uploadWatchFace` takes real files and requires the `.json` sidecar beside the
     * `.bin`, so the archive has to be opened before an install rather than streamed into one. The
     * caller is expected to [clean] afterwards — these are ~1 MB each and there is no reason to keep
     * them once the band has taken the face.
     */
    fun unpack(entry: Entry, workDir: File): File? = runCatching {
        workDir.mkdirs()
        ZipFile(entry.zip).use { z ->
            listOf("${entry.id}.bin", "${entry.id}.json").forEach { name ->
                val e = z.getEntry(name) ?: return null
                File(workDir, name).outputStream().use { out ->
                    z.getInputStream(e).use { it.copyTo(out) }
                }
            }
        }
        File(workDir, "${entry.id}.bin")
    }.getOrNull()

    /** Remove an unpacked pair. Never touches the archive itself. */
    fun clean(entry: Entry, workDir: File) {
        runCatching {
            File(workDir, "${entry.id}.bin").delete()
            File(workDir, "${entry.id}.json").delete()
        }
    }
}
