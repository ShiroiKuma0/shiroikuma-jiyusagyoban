package com.opentasker.core.huawei

import com.opentasker.core.storage.HuaweiWorkoutBlobEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The store's contract, over the band's own bytes.
 *
 * The workouts moved out of `/sdcard/〇/[666] 私資料/[666][147] tracks` and into the database on
 * 2026-09-04, and two of the guarantees that used to be enforced by how a directory was written now
 * have to be enforced here instead:
 *
 *  * **A re-fetch must not touch what the band cannot re-supply.** On disk this was three writers
 *    each remembering to carry the note and the stop count forward, and each of them was a place to
 *    forget. Here [HuaweiWorkoutStore.put] reads the existing row itself, so a caller holding a
 *    stale copy cannot roll them back.
 *  * **A workout is not named after the wrong sport.** That used to be a directory called
 *    `walk-20-…` for a strength session, which is what 白い熊 caught on the session's own screen.
 *    There are no names now — a row is keyed by when the workout started — so the whole class of
 *    mistake is gone rather than guarded against.
 */
class HuaweiWorkoutStoreTest {

    private fun bytes(name: String) =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("huawei/$name")) {
            "fixture huawei/$name missing"
        }.readBytes()

    private fun summary(name: String) = requireNotNull(
        HuaweiWorkout.parseSummary(listOf(HuaweiProtocol.Tlv(0x81, bytes(name)))),
    )

    /** The strength session of 2026-09-03, exactly as the band handed it over. */
    private suspend fun storeLift(dao: FakeWorkoutDao): HuaweiWorkoutStore.Workout {
        val s = summary("workout-20-summary.bin")
        HuaweiWorkoutStore.put(
            dao = dao,
            summary = s,
            startSeconds = s.startSeconds!!,
            sampleBlocks = listOf(bytes("workout-20-samples-0.bin")),
            splits = emptyList(),
            summaryRaw = bytes("workout-20-summary.bin"),
            trackRaw = null,
            trackPoints = 0,
            sampleCount = 516,
            intervalSeconds = 5,
        )
        return requireNotNull(HuaweiWorkoutStore.byId(dao, s.startSeconds.toString()))
    }

    @Test
    fun `a stored workout reads back as what it was`() = runBlocking {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        assertEquals(20, lift.number)
        assertEquals("strength", lift.kind)
        assertTrue(lift.isStrength)
        assertTrue("a lift has no route", !lift.hasTrack)
        assertEquals(217, lift.calories)
        assertEquals(2584L, lift.durationSeconds)
        // The identity is WHEN it happened, not the band's index into its own ring buffer.
        assertEquals(lift.startSeconds.toString(), lift.id)
    }

    @Test
    fun `the heart rate decodes from the band's own blocks`() = runBlocking {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        val effort = requireNotNull(HuaweiWorkoutStore.effortOf(dao, lift))
        assertEquals(5, effort.intervalSeconds)
        assertEquals(516, effort.heart.size)
        assertEquals(86, effort.minHeart)
        assertEquals(134, effort.maxHeart)
        assertEquals(20, effort.recoveryDrop)
        assertTrue("a lift carries no speed", effort.speedDmS.isEmpty())
    }

    /**
     * The one thing the band cannot give back.
     *
     * Written, then re-fetched with everything measured replaced, and the two authored fields have
     * to still be there. This is the guarantee the old on-disk writers each had to remember.
     */
    @Test
    fun `a re-fetch replaces every measurement and keeps the annotation`() = runBlocking {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        HuaweiWorkoutStore.annotate(dao, lift, "legs — the last set went badly", 3)

        // The same workout again, as if the band had been asked a second time.
        val again = summary("workout-20-summary.bin")
        HuaweiWorkoutStore.put(
            dao = dao, summary = again, startSeconds = again.startSeconds!!,
            sampleBlocks = listOf(bytes("workout-20-samples-0.bin")), splits = emptyList(),
            summaryRaw = bytes("workout-20-summary.bin"), trackRaw = null,
            trackPoints = 0, sampleCount = 516, intervalSeconds = 5,
        )
        val after = requireNotNull(HuaweiWorkoutStore.byId(dao, again.startSeconds.toString()))
        assertEquals("legs — the last set went badly", after.note)
        assertEquals(3, after.stops)
        assertEquals("and one row, not two", 1, dao.rows.size)
    }

    /** Blank deletes — the same contract the note pill has carried everywhere since the start. */
    @Test
    fun `a blank note deletes rather than storing whitespace`() = runBlocking {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        HuaweiWorkoutStore.annotate(dao, lift, "something", null)
        HuaweiWorkoutStore.annotate(dao, lift, "   ", null)
        assertNull(requireNotNull(HuaweiWorkoutStore.byId(dao, lift.id)).note)
    }

    /**
     * A workout imported from the old archive, whose raw blocks were never kept.
     *
     * The fallback exists so a one-way migration does not depend on the band still holding the
     * workout — and it must LOSE to the band's own bytes the moment those arrive, so a re-fetch
     * cleans up after the import without anything having to remember to.
     */
    @Test
    fun `legacy decoded effort is read only when there are no real blocks`() = runBlocking {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        dao.deleteBlobs(lift.startSeconds)
        dao.putBlob(
            HuaweiWorkoutBlobEntity(
                lift.startSeconds, HuaweiWorkoutImport.BLOB_LEGACY_EFFORT,
                """{"intervalSeconds":5,"heart":[0,70,80,90]}""".toByteArray(),
            ),
        )
        val legacy = requireNotNull(HuaweiWorkoutStore.effortOf(dao, lift))
        assertEquals(4, legacy.heart.size)
        assertEquals(90, legacy.maxHeart)

        // The band's own block arrives; the fallback stops being read at once.
        dao.putBlob(
            HuaweiWorkoutBlobEntity(
                lift.startSeconds, HuaweiWorkoutStore.blobSamples(0), bytes("workout-20-samples-0.bin"),
            ),
        )
        assertEquals(516, requireNotNull(HuaweiWorkoutStore.effortOf(dao, lift)).heart.size)
    }

    /**
     * The exported heart rate stands on its own, away from this app.
     *
     * A bare array of numbers would not: nothing downstream could place it in a day. And the
     * recovery curve must NOT claim a spacing — the band never states one, and a file that invented
     * it would undo the care taken everywhere else not to.
     */
    @Test
    fun `the exported heart rate carries its own clock and no invented spacing`() = runBlocking<Unit> {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        val effort = requireNotNull(HuaweiWorkoutStore.effortOf(dao, lift))
        val dir = File.createTempFile("hr-export", "").let { it.delete(); it.mkdirs(); it }

        val out = requireNotNull(
            HuaweiWorkoutStore.exportHeart(lift, effort, dir, nowMillis = 1_788_456_000_000L),
        )
        assertTrue("the name says what it is", out.name.startsWith("hr-strength-20_"))
        val text = out.readText()
        assertTrue(text.contains("\"startSeconds\":1788451380"))
        assertTrue(text.contains("\"intervalSeconds\":5"))
        assertTrue("zeros are readings that were not taken", text.contains("\"zeroMeansNoReading\":true"))
        assertTrue("the recovery spacing is unknown and says so", text.contains("\"spacingSeconds\":null"))
        dir.deleteRecursively()
    }

    /** Nothing to export is not an empty file — it is no file. */
    @Test
    fun `a workout with no heart rate exports nothing`() {
        val dir = File.createTempFile("hr-export", "").let { it.delete(); it.mkdirs(); it }
        val bare = HuaweiWorkoutStore.Workout(startSeconds = 1L, number = 1)
        assertNull(HuaweiWorkoutStore.exportHeart(bare, HuaweiWorkoutStore.Effort(), dir))
        assertEquals(0, dir.listFiles()?.size)
        dir.deleteRecursively()
    }

    /**
     * The GPX is regenerated, never stored — and it says what turned metres back into degrees.
     *
     * Two earth radii are still candidates and the datum is unsettled, so a file that leaves this
     * app has to carry the reading that produced it or it stops being interpretable the day we
     * learn which is right.
     */
    @Test
    fun `an exported GPX states the constant it was decoded with`() = runBlocking<Unit> {
        val dao = FakeWorkoutDao()
        val s = summary("workout-8-summary.bin")
        HuaweiWorkoutStore.put(
            dao = dao, summary = s, startSeconds = s.startSeconds!!,
            sampleBlocks = emptyList(), splits = emptyList(), summaryRaw = null,
            trackRaw = bytes("workout-8-track.bin"), trackPoints = 1_763,
            sampleCount = 0, intervalSeconds = 0,
        )
        val walk = requireNotNull(HuaweiWorkoutStore.byId(dao, s.startSeconds.toString()))
        val dir = File.createTempFile("gpx-export", "").let { it.delete(); it.mkdirs(); it }
        val out = requireNotNull(HuaweiWorkoutStore.exportGpx(dao, walk, dir))
        val text = out.readText()
        assertTrue("the radius is named", text.contains("earthRadiusM=${HuaweiGpsTrack.EARTH_RADIUS_M}"))
        assertTrue("and the datum is admitted to be unconfirmed", text.contains("datum unconfirmed"))
        assertTrue("and it is still a GPX", text.contains("<trkpt"))
        dir.deleteRecursively()
    }

    /** Deleting a workout takes its bytes with it. Nothing else can reach an orphaned blob. */
    @Test
    fun `forgetting a workout removes its blobs too`() = runBlocking {
        val dao = FakeWorkoutDao()
        val lift = storeLift(dao)
        assertTrue(dao.blobs.isNotEmpty())
        HuaweiWorkoutStore.forget(dao, lift)
        assertEquals(0, dao.rows.size)
        assertEquals(0, dao.blobs.size)
    }
}

/**
 * A base map is cached only when there is a map in it.
 *
 * 地図's renderer falls back to painting a block plain black on every internal failure — its render
 * timeout, an interrupted draw, no map data for the area — and still replies `OK:`. That is right
 * for a walk picture, where a route on black still shows the route, and wrong for a bare cutout,
 * where it is a megabyte of nothing that looks exactly like success. `map_detail` is the only field
 * that tells them apart (地図 chat, 2026-09-04).
 *
 * Source-scanned rather than exercised, because the alternative is standing up an intent round trip
 * to a sister app inside a JVM test. What matters is that the gate EXISTS and that no accepting
 * path bypasses it — which is a question about the code, not about its behaviour at run time.
 */
class HuaweiChizuBasemapGateTest {

    private val production = com.opentasker.ProductionSources
        .read("app/src/main/java/com/opentasker/ui/charts/huawei/HuaweiChizu.kt".removePrefix("app/src/main/java/"))

    @Test
    fun `only map_detail = map is cached`() {
        assertTrue("the gate must read map_detail", production.contains("map_detail"))
        assertTrue(
            "anything that is not `map` must be refused",
            production.contains("if (detail != \"map\")"),
        )
        // The refusal has to come BEFORE the write, or the row is already in the database when the
        // message is composed.
        val gate = production.indexOf("if (detail != \"map\")")
        val write = production.indexOf("HuaweiWorkoutStore.putCutout")
        assertTrue("the gate must precede the write", gate in 1 until write)
    }

    /**
     * The two stacked ceilings on 地図's side, and the one number that has to clear both.
     *
     * Its initialization wait is 180 s and its rasterizer's own timeout is another 120 s; a cold
     * start over an area never drawn before hits both in series. 300 s left exactly zero margin.
     */
    @Test
    fun `the round trip outlasts both of 地図's ceilings`() {
        val timeout = Regex("TIMEOUT_MS = ([0-9_]+)L").find(production)!!
            .groupValues[1].replace("_", "").toLong()
        assertTrue("300 s is exactly 180 + 120 and leaves nothing", timeout > 300_000L)
    }
}

/**
 * The interim state, while 白い熊 地図 has not been rebuilt for the URI hand-over.
 *
 * The installed 地図 refuses both requests before writing anything — clean failures, nothing
 * created and nothing half-written. But its own words are `no gpx: pass gpx_data or gpx_path`,
 * which put in front of 白い熊 reads as a fault in THIS app rather than as the other one being a
 * build behind. The strings were supplied by the 地図 chat on 2026-09-04 and disappear the moment
 * its build lands, which is what makes matching on them safe rather than brittle.
 */
class HuaweiChizuInterimTest {

    private val production = com.opentasker.ProductionSources
        .read("com/opentasker/ui/charts/huawei/HuaweiChizu.kt")

    @Test
    fun `both of the old build's refusals are recognised`() {
        for (refusal in listOf("ERROR:no gpx: pass gpx_data or gpx_path", "ERROR:no out_path")) {
            assertTrue(
                "the interim refusal $refusal must be recognised, not shown raw",
                production.contains("\"$refusal\""),
            )
        }
    }

    /** Recognised in BOTH directions — a base map failing raw would read exactly as badly. */
    @Test
    fun `the refusal is mapped wherever a reply is read`() {
        assertEquals(
            "every non-OK reply goes through refusal()",
            2,
            Regex("Outcome\\(false, refusal\\(").findAll(production).count(),
        )
    }
}

/**
 * A walk with no base map asks for one. It does not report its absence.
 *
 * 白い熊, 2026-09-04: *"If no map — it shouldn't display no map, it should request from chizu. This
 * must be automatic in the app."* The screen knew what was missing and who to ask, and waited to be
 * told; that read as the feature being broken, and it was.
 *
 * Source-scanned, because the behaviour is a Compose effect driving an intent round trip to a
 * sister app — but the three properties that make it safe rather than a request storm are all
 * visible in the code, and each has a specific failure it prevents.
 */
class HuaweiCutoutAutoFetchTest {

    private val activity = com.opentasker.ProductionSources
        .read("com/opentasker/ui/charts/huawei/HuaweiWalksActivity.kt")
    private val text = com.opentasker.ProductionSources
        .read("com/opentasker/ui/charts/huawei/HuaweiText.kt")

    /**
     * The file with its comments removed.
     *
     * Needed because this codebase documents what a wording USED to be and why it changed, so a
     * naive search for the old string finds the explanation of its own removal. The question the
     * test is asking is "can anything still show this?", and only code can show anything.
     */
    private val code = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)^\\s*//.*$"), "")

    @Test
    fun `a missing area is fetched without being asked`() {
        assertTrue(
            "the window must call basemap() itself",
            activity.contains("HuaweiChizu.basemap("),
        )
        assertTrue(
            "and it must be driven by what is missing, not by a button",
            activity.contains("it.id !in state.bases && it.id !in askedFor"),
        )
        // The id asked for must be the cutout that WOULD cover the walk. Reading it from
        // `plot.cutout` — the one that already does — yields null in precisely the case this
        // exists to handle, so nothing is ever requested while every cell claims to be asking.
        assertTrue(
            "the wanted area comes from needed(), not from the covering cutout",
            activity.contains("MapCutouts.needed("),
        )
        assertTrue(
            "and only for walks that have no cover yet",
            activity.contains("if (plot.cutout != null) return@mapNotNull null"),
        )
    }

    /**
     * One request per AREA. Ten walks from the same door share one cutout, and keying the fetch by
     * walk instead of by cutout id would send ten near-identical requests to a renderer that
     * serialises them anyway.
     */
    @Test
    fun `areas are de-duplicated before anything is requested`() {
        assertTrue(
            "the fetch list must be distinct cutout ids",
            activity.contains(".distinctBy { it.id }"),
        )
    }

    /**
     * One attempt per area per window.
     *
     * Without it, an area 地図 has no data for is re-requested on every reload for ever — and each
     * of those is a round trip that can run for minutes.
     */
    @Test
    fun `an area is asked about once, and reopening the window is the retry`() {
        assertTrue("attempts must be remembered", activity.contains("askedFor = askedFor + next.id"))
        assertTrue("and consulted before asking", activity.contains("it.id !in askedFor"))
    }

    /** Serial: 地図 renders under one lock, so a second request in flight only queues behind. */
    @Test
    fun `nothing is requested while a round trip is already running`() {
        assertTrue(
            "the effect must stand down while busy",
            activity.contains("if (state.busy || state.loading) return@LaunchedEffect"),
        )
    }

    /** And the cell says what is happening, rather than stating a fact it will not act on. */
    @Test
    fun `the cell says it is asking rather than that a map is absent`() {
        assertTrue("the asking wording exists", text.contains("val walksAskingMap"))
        assertTrue("in both languages", text.contains("白い熊 地図 にこの辺りの地図を頼んでいます"))
        assertTrue(
            "the old wording must no longer be a value anything can show",
            !code.contains("No map cached for this area yet"),
        )
    }
}

/**
 * The 地図 reply is read by NAME, and positionally only when the shape is exactly what we expect.
 *
 * In URI mode with no pictures asked for, three of the six fields come back empty — the first
 * request in this contract's life is also the first to produce that shape. A parser that skipped or
 * trimmed empties would not fail; it would put the duration where the distance goes and show two
 * plausible wrong numbers on the one card whose entire purpose is catching a disagreement between
 * 地図's arithmetic and the band's.
 */
class HuaweiChizuReplyShapeTest {

    private val production = com.opentasker.ProductionSources
        .read("com/opentasker/ui/charts/huawei/HuaweiChizu.kt")

    @Test
    fun `the positional fallback is refused when the field count disagrees`() {
        assertTrue(
            "the count must be checked before any field is read by index",
            production.contains("packed.takeIf { it.size == slots.size }"),
        )
        val guard = production.indexOf("packed.takeIf { it.size == slots.size }")
        val use = production.indexOf("positional?.getOrNull(slots.indexOf(name))")
        assertTrue("and checked before it is used", guard in 1 until use)
    }

    @Test
    fun `named extras are read first, always`() {
        assertTrue(
            "the extra wins over the position",
            production.contains("reply.extras[name]?.takeIf { it.isNotBlank() }"),
        )
    }

    /**
     * The reply shape itself, as 地図 specifies it: six fields, three of them empty.
     *
     * Asserted on a literal rather than trusted, because this is the one number that decides whether
     * distance and duration land in the right place, and the two apps agreeing about it in a chat is
     * not the same as the code agreeing about it.
     */
    @Test
    fun `a no-picture success splits into exactly six fields`() {
        val reply = "OK:abc123||||2270|1765"
        val packed = reply.removePrefix("OK:").split('|')
        assertEquals("six fields, three empty", 6, packed.size)
        assertEquals("abc123", packed[0])
        assertEquals("", packed[1])
        assertEquals("", packed[2])
        assertEquals("", packed[3])
        assertEquals("2270", packed[4])
        assertEquals("1765", packed[5])
    }
}
