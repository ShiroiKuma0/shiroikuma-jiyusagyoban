package com.opentasker.core.huawei

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file transfer, driven against a scripted band.
 *
 * Everything asserted here was read off a decrypted capture of Huawei Health pulling `sequence_data`
 * from 白い熊's band, and the two transfers in that capture are what pin the frame layout: 642 bytes
 * arriving at offset 0, and 692 arriving at offset 6832 of a 7524-byte file. Both land on their
 * declared size exactly, which is what identifies the header as six bytes rather than five.
 */
class HuaweiFileClientTest {

    /** A band that answers the file handshake and then pushes slices. */
    private class FileBand(
        private val size: Int,
        private val chunk: Int,
        private val headResult: Int = HuaweiProtocol.RESULT_SUCCESS,
        private val type: Int = HuaweiFileClient.SEQUENCE_TYPE,
        private val dropFrom: Int = Int.MAX_VALUE,
        private val duplicateFirst: Boolean = false,
    ) : HuaweiTransport {
        val written = ArrayList<HuaweiProtocol.Frame>()

        /**
         * ONE byte longer than the size the band declares — which is what the real band does. Both
         * captured transfers carry `size + 1`: 643 bytes for a declared 642, and a final slice
         * landing at 7525 of a declared 7524.
         */
        val content = ByteArray(size + 1) { ((it + 7) % 251).toByte() }
        private val out = ArrayDeque<ByteArray>()

        override suspend fun write(data: ByteArray) {
            val f = HuaweiProtocol.unframe(data)
            written += f
            when (f.commandId) {
                HuaweiCommands.FILE_REQUEST -> out.add(
                    HuaweiProtocol.frame(
                        HuaweiCommands.SVC_FILE_TRANSFER, HuaweiCommands.FILE_REQUEST,
                        HuaweiProtocol.tlv(1, "sequence_data") +
                            HuaweiProtocol.tlv(2, byteArrayOf(type.toByte())) +
                            HuaweiProtocol.tlv(4, HuaweiProtocol.intBytes(size, 4)) +
                            HuaweiProtocol.tlv(
                                HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(headResult, 4),
                            ),
                    ),
                )
                HuaweiCommands.FILE_NEGOTIATE -> out.add(
                    HuaweiProtocol.frame(
                        HuaweiCommands.SVC_FILE_TRANSFER, HuaweiCommands.FILE_NEGOTIATE,
                        HuaweiProtocol.tlv(1, byteArrayOf(type.toByte())) +
                            HuaweiProtocol.tlv(3, HuaweiProtocol.intBytes(chunk, 4)) +
                            HuaweiProtocol.tlv(4, HuaweiProtocol.intBytes(0x1E80, 4)) +
                            HuaweiProtocol.tlv(5, byteArrayOf(2)),
                    ),
                )
                HuaweiCommands.FILE_START -> {
                    var off = 0
                    var first = true
                    while (off < size && off < dropFrom) {
                        // The overflow byte rides in the FINAL slice, as it does on the real band:
                        // one frame carried 643 bytes for a declared 642, and the last slice of the
                        // 7524-byte file ended at 7525. It never arrives as a chunk of its own.
                        var n = minOf(chunk, size - off)
                        if (off + n >= size) n = content.size - off
                        out.add(slice(off, n))
                        if (first && duplicateFirst) out.add(slice(off, n))
                        first = false
                        off += n
                    }
                }
                HuaweiCommands.FILE_DONE -> out.add(
                    HuaweiProtocol.frame(
                        HuaweiCommands.SVC_FILE_TRANSFER, HuaweiCommands.FILE_DONE,
                        HuaweiProtocol.tlv(
                            HuaweiProtocol.TAG_RESULT,
                            HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4),
                        ),
                    ),
                )
            }
        }

        /** type | offset | data — a five-byte header and then file bytes, nothing else. */
        private fun slice(offset: Int, n: Int) = HuaweiProtocol.frame(
            HuaweiCommands.SVC_FILE_TRANSFER, HuaweiCommands.FILE_DATA,
            byteArrayOf(type.toByte()) + HuaweiProtocol.intBytes(offset, 4) +
                content.copyOfRange(offset, offset + n),
        )

        override suspend fun read(timeoutMs: Long): ByteArray? = out.removeFirstOrNull()
        override suspend fun close() = Unit
    }

    @Test
    fun `a chunked file is reassembled byte for byte`() = runBlocking {
        val band = FileBand(size = 7524, chunk = 976)
        val r = HuaweiFileClient(HuaweiSession(band)).fetch(
            HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE, 0, 1, id = 700_013,
        )
        val data = r as HuaweiFileClient.Result.Data
        assertEquals(7525, data.bytes.size)
        assertArrayEquals(band.content, data.bytes)
    }

    @Test
    fun `the byte past the declared size is kept, not discarded`() = runBlocking {
        // The band announces one byte fewer than it sends. Discarding the difference cost the last
        // sleep segment its high byte — zero that time, so nothing looked wrong. The declared size
        // is therefore treated as a minimum, and what the band actually sent is what comes back.
        val band = FileBand(size = 2000, chunk = 500)
        val data = HuaweiFileClient(HuaweiSession(band)).fetch(
            HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE, 0, 1, id = 1,
        ) as HuaweiFileClient.Result.Data
        assertArrayEquals(band.content, data.bytes)
    }

    @Test
    fun `nothing recorded is an answer, not a failure`() = runBlocking {
        // 144001 is what the band returns for a window it holds nothing for. An empty night and a
        // broken request must never look alike.
        val band = FileBand(size = 0, chunk = 976, headResult = 144_001)
        val r = HuaweiFileClient(HuaweiSession(band)).fetch(
            HuaweiFileClient.RRI_DATA, HuaweiFileClient.RRI_TYPE, 0, 1,
        )
        assertTrue(r is HuaweiFileClient.Result.Empty)
        assertEquals(144_001, (r as HuaweiFileClient.Result.Empty).resultCode)
    }

    @Test
    fun `a repeated chunk does not fake completion`() = runBlocking {
        val band = FileBand(size = 3000, chunk = 500, duplicateFirst = true)
        val data = HuaweiFileClient(HuaweiSession(band)).fetch(
            HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE, 0, 1, id = 1,
        ) as HuaweiFileClient.Result.Data
        assertArrayEquals(band.content, data.bytes)
    }

    /**
     * The bytes that arrived are kept and handed back. Throwing them away was the older behaviour
     * and it cost a real 390 KB of `sequence_data` — assembled, then dropped because the transfer
     * was not whole.
     */
    @Test
    fun `a short transfer comes back as Partial, with the bytes that did arrive`() = runBlocking {
        val band = FileBand(size = 4000, chunk = 500, dropFrom = 1500)
        val r = HuaweiFileClient(HuaweiSession(band)).fetch(
            HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE, 0, 1,
            id = 1, timeoutMs = 300,
        )
        val partial = r as HuaweiFileClient.Result.Partial
        assertEquals(4000, partial.declared)
        assertEquals(1500, partial.received)
        assertEquals(2500, partial.missing)
        // The prefix that did arrive must match the band's own bytes exactly — a partial file that
        // is subtly wrong is worse than none, because it will be decoded.
        assertArrayEquals(band.content.copyOf(1500), partial.bytes.copyOf(1500))
        // And it must not be mistaken for a whole file by a caller that only checks for Data.
        assertTrue(r !is HuaweiFileClient.Result.Data)
    }

    @Test
    fun `the request carries the id only when one is given`() = runBlocking {
        val with = HuaweiProtocol.parseTlvs(
            HuaweiCommands.fileRequest("sequence_data", 0x16, 100, 200, 700_013),
        )
        assertEquals(700_013, HuaweiProtocol.bytesToInt(with.first { it.tag == 12 }.value))
        assertEquals("sequence_data", String(with.first { it.tag == 1 }.value, Charsets.US_ASCII))
        assertEquals(100, HuaweiProtocol.bytesToInt(with.first { it.tag == 5 }.value))
        assertEquals(200, HuaweiProtocol.bytesToInt(with.first { it.tag == 6 }.value))

        val without = HuaweiProtocol.parseTlvs(
            HuaweiCommands.fileRequest("rrisqi_data.bin", 0x10, 100, 200, null),
        )
        assertTrue(without.none { it.tag == 12 })
    }
}
