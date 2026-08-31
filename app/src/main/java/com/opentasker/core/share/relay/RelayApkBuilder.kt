package com.opentasker.core.share.relay

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Assembles + signs one per-target relay APK entirely on-device, reusing the fixed fixtures in
 * `assets/relay/` (see `app/src/relay/REGENERATE.md`). Per relay only the binary manifest (package /
 * label / target) and the icon PNG differ; `classes.dex` and `resources.arsc` are byte-shared.
 *
 * The zip is written by hand (all entries STORED, every entry 4-byte aligned) rather than via
 * `ZipOutputStream`, because `resources.arsc` MUST be uncompressed AND 4-byte aligned for a
 * `targetSdk >= 30` app to install, and `ZipOutputStream.setExtra` alignment padding is unreliable
 * across runtimes. apksig then inserts its signing block between the last entry and the central
 * directory, preserving those data offsets (and thus the alignment).
 */
object RelayApkBuilder {

    private const val ICON_ENTRY = "res/mipmap/ic.png"

    /** Icon resource path referenced by the fixed resources.arsc (id 0x7f010000). */

    data class Spec(
        val relayPackage: String,
        val label: String,
        val targetPackage: String,
        /** The chosen icon as PNG bytes; null falls back to the shipped placeholder. */
        val iconPng: ByteArray?,
    )

    /** Build + sign the relay into [outSigned]. Pure JVM + apksig — no Shizuku needed here. */
    fun buildSigned(context: Context, spec: Spec, workDir: File, outSigned: File) {
        val assets = context.assets
        val template = assets.open("relay/AndroidManifest.tmpl").use { it.readBytes() }
        val dex = assets.open("relay/classes.dex").use { it.readBytes() }
        val arsc = assets.open("relay/resources.arsc").use { it.readBytes() }
        val icon = spec.iconPng ?: assets.open("relay/ic_placeholder.png").use { it.readBytes() }

        val manifest = RelayManifestTemplate.build(template, spec.relayPackage, spec.label, spec.targetPackage)

        workDir.mkdirs()
        val unsigned = File(workDir, "unsigned.apk")
        // Order is arbitrary; every entry is STORED and 4-byte aligned. resources.arsc alignment is
        // the load-bearing one for R+ installs.
        writeAlignedZip(
            unsigned,
            listOf(
                "AndroidManifest.xml" to manifest,
                "classes.dex" to dex,
                "resources.arsc" to arsc,
                ICON_ENTRY to icon,
            ),
        )
        RelaySigner.sign(context, unsigned, outSigned)
        unsigned.delete()
    }

    /** Minimal STORED-only zip writer with every entry's data 4-byte aligned (à la `zipalign -p 4`). */
    private fun writeAlignedZip(out: File, entries: List<Pair<String, ByteArray>>) {
        val body = ByteArrayOutputStream()
        data class Central(val nameBytes: ByteArray, val crc: Long, val size: Int, val offset: Int)
        val centrals = ArrayList<Central>()

        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            val headerOffset = body.size()
            // Data begins after: 30-byte local header + name + extra. Pad the extra so it is 4-aligned.
            val beforeExtra = headerOffset + 30 + nameBytes.size
            val pad = ((4 - beforeExtra % 4) % 4)
            // Local file header
            writeInt(body, 0x04034b50)          // signature
            writeShort(body, 20)                // version needed
            writeShort(body, 0)                 // flags
            writeShort(body, 0)                 // method: STORED
            writeShort(body, 0)                 // mod time
            writeShort(body, 0x21)              // mod date (1980-01-01)
            writeInt(body, crc.toInt())
            writeInt(body, data.size)           // compressed size
            writeInt(body, data.size)           // uncompressed size
            writeShort(body, nameBytes.size)
            writeShort(body, pad)               // extra length = alignment padding
            body.write(nameBytes)
            repeat(pad) { body.write(0) }
            body.write(data)
            centrals.add(Central(nameBytes, crc, data.size, headerOffset))
        }

        val cdStart = body.size()
        for (c in centrals) {
            writeInt(body, 0x02014b50)          // central header signature
            writeShort(body, 20)                // version made by
            writeShort(body, 20)                // version needed
            writeShort(body, 0)                 // flags
            writeShort(body, 0)                 // method STORED
            writeShort(body, 0)                 // mod time
            writeShort(body, 0x21)              // mod date
            writeInt(body, c.crc.toInt())
            writeInt(body, c.size)
            writeInt(body, c.size)
            writeShort(body, c.nameBytes.size)
            writeShort(body, 0)                 // extra length
            writeShort(body, 0)                 // comment length
            writeShort(body, 0)                 // disk number
            writeShort(body, 0)                 // internal attrs
            writeInt(body, 0)                   // external attrs
            writeInt(body, c.offset)            // local header offset
            body.write(c.nameBytes)
        }
        val cdSize = body.size() - cdStart
        // End of central directory
        writeInt(body, 0x06054b50)
        writeShort(body, 0)                     // disk
        writeShort(body, 0)                     // cd start disk
        writeShort(body, centrals.size)         // entries on this disk
        writeShort(body, centrals.size)         // total entries
        writeInt(body, cdSize)
        writeInt(body, cdStart)
        writeShort(body, 0)                     // comment length

        out.outputStream().use { it.write(body.toByteArray()) }
    }

    private fun writeShort(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v ushr 8) and 0xFF)
    }

    private fun writeInt(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v ushr 8) and 0xFF); o.write((v ushr 16) and 0xFF); o.write((v ushr 24) and 0xFF)
    }
}
