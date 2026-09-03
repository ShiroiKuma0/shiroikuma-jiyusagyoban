package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 白い熊's own annotation on a walk survives everything that rewrites `walk.json`.
 *
 * The note and the stop count are the only values in that file the band cannot re-supply. Both
 * writers rebuild the object whole — deliberately, so a half-written file cannot exist — which means
 * every field has to be carried forward BY NAME, and a field nobody remembered to list is silently
 * dropped on the next re-download. That is the failure this pins: it costs a note that 白い熊
 * believes is on file, and there is nowhere to recover it from.
 */
class HuaweiWalkAnnotationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun freshWalk(root: java.io.File) = HuaweiWalkLibrary.write(
        root = root,
        number = 1,
        startSeconds = 1_787_000_000L,
        endSeconds = 1_787_001_740L,
        distanceMetres = 2270,
        steps = 3011,
        calories = 118,
        elevationGainDm = 240,
        kind = "walk",
        points = 1763,
        raw = ByteArray(8),
        gpx = "<gpx/>",
    )

    @Test
    fun `a note and a stop count round-trip through the file`() {
        val root = temp.newFolder("tracks")
        val walk = freshWalk(root)
        assertNull("a fresh walk carries no annotation", walk.note)
        assertNull(walk.stops)

        val noted = HuaweiWalkLibrary.annotate(walk, "stopped at the bakery\nand again at the bridge", 2)
        assertEquals(2, noted.stops)
        assertEquals("stopped at the bakery\nand again at the bridge", noted.note)

        // Read back from disk rather than from the returned object: what matters is what a later
        // launch will see, not what this call happened to return.
        assertEquals(2, HuaweiWalkLibrary.read(walk.dir)!!.stops)
        assertEquals(noted.note, HuaweiWalkLibrary.read(walk.dir)!!.note)
    }

    @Test
    fun `re-downloading the same walk keeps what was written on it`() {
        val root = temp.newFolder("tracks")
        HuaweiWalkLibrary.annotate(freshWalk(root), "long way round", 3)

        // Exactly what a second fetch of the same walk does: same number, same start, so the same
        // directory, rewritten from the band's figures.
        val again = freshWalk(root)
        assertEquals("the note is not the band's to overwrite", "long way round", again.note)
        assertEquals(3, again.stops)
    }

    @Test
    fun `a fresh map from 地図 keeps what was written on the walk`() {
        val root = temp.newFolder("tracks")
        val noted = HuaweiWalkLibrary.annotate(freshWalk(root), "windy", 1)
        val mapped = HuaweiWalkLibrary.recordMap(noted, trackId = "t-1", thumbPath = null, mapPath = null)
        assertEquals("windy", mapped.note)
        assertEquals(1, mapped.stops)
        assertEquals("t-1", mapped.trackId)
    }

    /**
     * Blank deletes, and null withdraws — the two ways an annotation is taken back.
     *
     * Both are written as an ABSENT field rather than as an empty string or a zero, so "has a note"
     * and "has a stop count" are one question with one answer everywhere they are asked. A stored
     * `""` would read as a note that renders as nothing, and a stored `0` would be indistinguishable
     * from the real answer "I did not stop".
     */
    @Test
    fun `an emptied note and a withdrawn count leave no trace`() {
        val root = temp.newFolder("tracks")
        val noted = HuaweiWalkLibrary.annotate(freshWalk(root), "  ", null)
        assertNull("whitespace is not a note", noted.note)
        assertNull(noted.stops)

        val counted = HuaweiWalkLibrary.annotate(noted, "something", 0)
        assertEquals("zero stops is a real answer, not an absence", 0, counted.stops)
        assertEquals("and it survives a reload", 0, HuaweiWalkLibrary.read(counted.dir)!!.stops)

        val withdrawn = HuaweiWalkLibrary.annotate(counted, null, null)
        assertNull(withdrawn.note)
        assertNull(withdrawn.stops)
    }
}
