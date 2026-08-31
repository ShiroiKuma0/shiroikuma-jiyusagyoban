package com.opentasker.core.share.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Produces a per-relay binary `AndroidManifest.xml` from the fixed template shipped in
 * `assets/relay/AndroidManifest.tmpl` (an aapt2-compiled binary AXML). Only three string-pool
 * entries vary per relay — the app **package**, the share-tile **label**, and the **target package**
 * (the `share.target` meta-data value) — so we rebuild ONLY the string-pool chunk with those three
 * substitutions and copy the resource map + node tree verbatim. Because string indices are preserved
 * (same count, same order), every node-tree reference stays valid; the result is aapt-correct by
 * construction. Validated against `aapt2 dump xmltree` (see `app/src/relay/REGENERATE.md`).
 *
 * AXML layout: `RES_XML_TYPE`(8-byte header) → `RES_STRING_POOL_TYPE` → `RES_XML_RESOURCE_MAP_TYPE`
 * → node chunks. The pool here is UTF-16LE, styleCount 0 (verified for the shipped template).
 */
object RelayManifestTemplate {

    /** The exact placeholder strings in the template that get substituted (must match the source manifest). */
    private const val PLACEHOLDER_PACKAGE = "com.opentasker.relay"
    private const val PLACEHOLDER_LABEL = "Relay Placeholder"
    private const val PLACEHOLDER_TARGET = "PLACEHOLDER_TARGET_PKG"

    private const val TYPE_STRING_POOL = 0x0001
    private const val STRING_POOL_HEADER_SIZE = 28

    /**
     * @param template the raw bytes of `assets/relay/AndroidManifest.tmpl`
     * @param relayPackage the generated relay's application id
     * @param label the share-sheet tile label
     * @param targetPackage the app package the relay forwards to
     */
    fun build(template: ByteArray, relayPackage: String, label: String, targetPackage: String): ByteArray {
        val buf = ByteBuffer.wrap(template).order(ByteOrder.LITTLE_ENDIAN)

        // Outer RES_XML chunk header: type(2) headerSize(2) size(4). String pool starts at offset 8.
        val poolOff = 8
        val poolType = buf.getShort(poolOff).toInt() and 0xFFFF
        require(poolType == TYPE_STRING_POOL) { "template: expected string pool at 8, got 0x%04x".format(poolType) }
        val poolSize = buf.getInt(poolOff + 4)
        val stringCount = buf.getInt(poolOff + 8)
        val styleCount = buf.getInt(poolOff + 12)
        val flags = buf.getInt(poolOff + 16)
        val stringsStart = buf.getInt(poolOff + 20)
        require(styleCount == 0) { "template: unexpected styleCount=$styleCount" }
        val isUtf8 = (flags and 0x0100) != 0
        require(!isUtf8) { "template: expected UTF-16 pool" }

        // Read the existing strings, then substitute the three placeholders (exact-match).
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val off = buf.getInt(poolOff + STRING_POOL_HEADER_SIZE + i * 4)
            var p = poolOff + stringsStart + off
            var len = buf.getShort(p).toInt() and 0xFFFF
            p += 2
            if (len and 0x8000 != 0) { // high-bit => 2-word length; never hit for our short strings
                len = ((len and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
                p += 2
            }
            val bytes = ByteArray(len * 2)
            for (k in 0 until len * 2) bytes[k] = template[p + k]
            strings.add(String(bytes, Charsets.UTF_16LE))
        }
        val substituted = strings.map {
            when (it) {
                PLACEHOLDER_PACKAGE -> relayPackage
                PLACEHOLDER_LABEL -> label
                PLACEHOLDER_TARGET -> targetPackage
                else -> it
            }
        }

        // Rebuild string data (UTF-16LE: u16 char-count + code units + u16 NUL terminator) + offsets.
        val data = java.io.ByteArrayOutputStream()
        val offsets = IntArray(stringCount)
        for ((i, s) in substituted.withIndex()) {
            offsets[i] = data.size()
            val u = s.toByteArray(Charsets.UTF_16LE)
            val cl = u.size / 2
            require(cl < 0x8000) { "string too long: $s" }
            data.write(cl and 0xFF); data.write((cl ushr 8) and 0xFF)
            data.write(u)
            data.write(0); data.write(0)
        }
        val dataBytes = data.toByteArray()
        val padded = dataBytes.size + ((4 - dataBytes.size % 4) % 4)

        val headerAndOffsets = STRING_POOL_HEADER_SIZE + 4 * stringCount
        val newPoolSize = headerAndOffsets + padded

        val pool = ByteBuffer.allocate(newPoolSize).order(ByteOrder.LITTLE_ENDIAN)
        pool.putShort(TYPE_STRING_POOL.toShort())
        pool.putShort(STRING_POOL_HEADER_SIZE.toShort())
        pool.putInt(newPoolSize)
        pool.putInt(stringCount)
        pool.putInt(0)                      // styleCount
        pool.putInt(flags)
        pool.putInt(headerAndOffsets)       // stringsStart
        pool.putInt(0)                      // stylesStart
        for (o in offsets) pool.putInt(o)
        pool.put(dataBytes)
        // remaining bytes already zero (allocate() zero-fills)

        // Reassemble: outer header (8) + new pool + everything after the OLD pool (resmap + nodes).
        val restStart = poolOff + poolSize
        val out = ByteBuffer.allocate(8 + newPoolSize + (template.size - restStart)).order(ByteOrder.LITTLE_ENDIAN)
        out.put(template, 0, 8)
        out.put(pool.array())
        out.put(template, restStart, template.size - restStart)
        val bytes = out.array()
        // Fix the outer RES_XML chunk size (u32 at offset 4).
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size)
        return bytes
    }
}
