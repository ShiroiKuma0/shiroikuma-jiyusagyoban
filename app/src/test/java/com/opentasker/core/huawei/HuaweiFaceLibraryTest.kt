package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The on-disk face library.
 *
 * The archives are built by `scripts/huawei-face-session.py` on the PC, so these tests are the only
 * place the two halves are checked against each other: if the script's layout and this reader ever
 * drift, a directory full of faces silently shows as empty.
 */
class HuaweiFaceLibraryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun face(
        fileName: String,
        name: String,
        asset: String = "7185695173",
        version: String = "2.1.1",
        binSize: Int = 4096,
        withPreview: Boolean = true,
        withManifest: Boolean = true,
    ): File {
        val f = tmp.newFile(fileName)
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(entry: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(entry)); z.write(bytes); z.closeEntry()
            }
            put("${asset}_$version.bin", ByteArray(binSize) { it.toByte() })
            put("${asset}_$version.json", """{"result":{"content":{}}}""".toByteArray())
            if (withPreview) put("preview.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            if (withManifest) {
                put(
                    "face.json",
                    """{"name":"$name","assetId":"$asset","version":"$version"}""".toByteArray(),
                )
            }
        }
        return f
    }

    @Test
    fun `the display name comes from the manifest, not the filename`() {
        // The filename is sanitised for the filesystem and that is lossy — a face called
        // "Aurora / Night" cannot be a filename, so the archive carries the real name inside.
        face("Aurora _ Night.zip", "Aurora / Night")
        val entry = HuaweiFaceLibrary.list(tmp.root).single()
        assertEquals("Aurora / Night", entry.name)
        assertEquals("7185695173_2.1.1", entry.id)
    }

    @Test
    fun `faces are listed by name, and rubbish in the directory is ignored`() {
        face("b.zip", "Beta")
        face("a.zip", "Alpha")
        tmp.newFile("notes.txt").writeText("not a face")
        tmp.newFile("broken.zip").writeText("also not a face")
        assertEquals(listOf("Alpha", "Beta"), HuaweiFaceLibrary.list(tmp.root).map { it.name })
    }

    @Test
    fun `an archive without a manifest is not a face`() {
        // A bare pair of files could be anything; without face.json there is no name and no
        // assurance the archive was built by our own capture.
        val f = face("nameless.zip", "x", withManifest = false)
        assertNull(HuaweiFaceLibrary.read(f))
    }

    @Test
    fun `a manifest whose face is missing is refused`() {
        val f = tmp.newFile("empty.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("face.json"))
            z.write("""{"name":"Ghost","assetId":"1","version":"1.0"}""".toByteArray())
            z.closeEntry()
        }
        assertNull("a manifest alone is not a face", HuaweiFaceLibrary.read(f))
    }

    @Test
    fun `unpack produces exactly the two files an install needs, and clean removes them`() {
        val entry = checkNotNull(HuaweiFaceLibrary.read(face("f.zip", "Face")))
        val work = tmp.newFolder("work")
        val bin = checkNotNull(HuaweiFaceLibrary.unpack(entry, work))
        assertTrue(bin.isFile)
        // uploadWatchFace REQUIRES the sidecar beside the .bin — unpacking one without the other
        // fails at the band rather than here, which is much harder to read.
        assertTrue(File(work, "${entry.id}.json").isFile)
        assertEquals(4096, bin.length())

        HuaweiFaceLibrary.clean(entry, work)
        assertTrue(work.listFiles().orEmpty().isEmpty())
        assertTrue("the archive itself must survive", entry.zip.isFile)
    }

    @Test
    fun `a preview is returned when present and null when not`() {
        assertNotNull(HuaweiFaceLibrary.preview(face("with.zip", "With")))
        assertNull(HuaweiFaceLibrary.preview(face("without.zip", "Without", withPreview = false)))
    }
}
