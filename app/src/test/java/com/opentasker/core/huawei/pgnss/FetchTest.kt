package com.opentasker.core.huawei.pgnss

import java.io.File
import java.io.IOException
import java.time.LocalDate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The download layer: the naming rules that decide WHICH file to ask for, and the sniff that decides
 * whether what came back is a file at all.
 *
 * Both halves have already cost real builds. `download.aiub.unibe.ch` answers a bad path with HTTP
 * 200 and a 162-byte HTML page and a good one with a cross-host 301, so "the request succeeded" says
 * nothing; and the CODE product names lag their own contents by a day, so asking for today's returns
 * a 404 that looks like an outage. Neither is caught by anything except the tests below.
 */
class FetchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var fetcher: PgnssFetcher
    private val reports = mutableListOf<FetchProgress>()

    // Companion members need a receiver for a callable reference, so wrap them once here.
    private val sniffSp3: (ByteArray) -> Boolean = { PgnssFetcher.looksLikeSp3(it) }
    private val sniffErp: (ByteArray) -> Boolean = { PgnssFetcher.looksLikeErp(it) }
    private val sniffYuma: (ByteArray) -> Boolean = { PgnssFetcher.looksLikeYuma(it) }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        fetcher = PgnssFetcher(temp.root, PgnssFetcher.defaultClient()) { reports.add(it) }
    }

    @After
    fun stop() {
        server.close()
    }

    // ── which file to ask for ──────────────────────────────────────────────────────────────────

    /**
     * The CODE names LAG their contents by a day: the file called `...<doy>0000` has its first epoch
     * on doy+1. Asking for today's day-of-year returns a 404 from a bucket that has no directory
     * listing, so there is nothing to fall back on and the whole BeiDou half of the build stops.
     */
    @Test
    fun theCodeErpIsNamedForYesterdaysDayOfYear() {
        assertEquals("2026241", PgnssFetcher.yearDoy(LocalDate.of(2026, 8, 30).minusDays(1)))
        assertEquals("2026001", PgnssFetcher.yearDoy(LocalDate.of(2026, 1, 1)))
        assertEquals("2025365", PgnssFetcher.yearDoy(LocalDate.of(2026, 1, 1).minusDays(1)))
    }

    /**
     * Consecutive hourly Wuhan issues overlap almost entirely — each carries ONE day of observed
     * orbit — so three of them together give barely more than 24 hours of arc. Taking "the newest
     * three" therefore produces a dynamical fit with a third of the data it thinks it has, and
     * nothing about the result looks short.
     */
    @Test
    fun wuhanIssuesArePickedADayApartNotAnHour() {
        val hourly = (0..47).map { "WUM0MGXNRT_2026%03d%02d00_02D_05M_ORB.SP3.gz".format(239 + it / 24, it % 24) }
        val picked = PgnssFetcher.spacedIssues(hourly, 3)
        assertEquals(3, picked.size)
        val hours = picked.mapNotNull { PgnssFetcher.issueHour(it) }
        assertTrue("issues must be a day apart, got $picked", hours[0] - hours[1] >= 23)
        assertTrue("issues must be a day apart, got $picked", hours[1] - hours[2] >= 23)
        assertEquals("WUM0MGXNRT_20262402300_02D_05M_ORB.SP3.gz", picked.first())
    }

    /** Fewer issues than asked for is a short list, never a silently repeated file. */
    @Test
    fun tooFewSpacedIssuesReturnsWhatThereIs() {
        val bunched = (0..5).map { "WUM0MGXNRT_2026241%02d00_02D_05M_ORB.SP3.gz".format(it) }
        assertEquals(1, PgnssFetcher.spacedIssues(bunched, 3).size)
        assertEquals(emptyList<String>(), PgnssFetcher.spacedIssues(listOf("README.txt"), 3))
    }

    /** The eleven-digit stamp is `<yyyy><doy><hh>`, not `<yyyy><mm><dd><hh>`. */
    @Test
    fun theIssueStampIsYearDayOfYearHour() {
        val a = PgnssFetcher.issueHour("WUM0MGXNRT_20262410400_02D_05M_ORB.SP3.gz")!!
        val b = PgnssFetcher.issueHour("WUM0MGXNRT_20262400400_02D_05M_ORB.SP3.gz")!!
        assertEquals(24L, a - b)
        assertEquals(null, PgnssFetcher.issueHour("WUM0MGXFIN_notastamp.SP3.gz"))
    }

    /**
     * A product is filed under the GPS week of its OWN timestamp, which is not this week once the
     * mirror is a day behind — as Wuhan always is. Computing the directory from "now" alone looks in
     * a week that exists but is empty, and an empty listing is indistinguishable from an outage.
     */
    @Test
    fun theWeekDirectoryIsTheGpsWeekOfTheFilesOwnDate() {
        assertEquals(2433, PgnssFetcher.gpsWeekOf(LocalDate.of(2026, 8, 29)))
        assertEquals(2434, PgnssFetcher.gpsWeekOf(LocalDate.of(2026, 8, 30)))
        assertEquals(2434, PgnssFetcher.gpsWeekOf(LocalDate.of(2026, 9, 5)))
        assertEquals(0, PgnssFetcher.gpsWeekOf(LocalDate.of(1980, 1, 6)))
    }

    // ── is it a file at all ────────────────────────────────────────────────────────────────────

    /** The exact page that twice landed on disk looking like an orbit product. */
    private val redirectPage =
        "<html>\r\n<head><title>301 Moved Permanently</title></head>\r\n<body>\r\n" +
            "<center><h1>301 Moved Permanently</h1></center>\r\n<hr><center>nginx</center>\r\n" +
            "</body>\r\n</html>\r\n"

    @Test
    fun aRedirectPageIsNotAnyOfTheFormatsWeAskFor() {
        val page = redirectPage.toByteArray()
        assertFalse(PgnssFetcher.looksLikeSp3(page))
        assertFalse(PgnssFetcher.looksLikeErp(page))
        assertFalse(PgnssFetcher.looksLikeGzip(page))
        assertFalse(PgnssFetcher.looksLikeGfc(page))
        assertFalse(PgnssFetcher.looksLikeYuma(page))
        assertFalse(PgnssFetcher.looksLikeGalileoAlmanac(page))
        assertFalse(PgnssFetcher.looksLikeAgl(page))
    }

    /** ...and the real heads are recognised, so the sniffs are not merely "reject everything". */
    @Test
    fun theRealFormatsAreRecognised() {
        assertTrue(
            PgnssFetcher.looksLikeSp3(
                ("#dP2026  8 30  0  0  0.00000000    1441 d+D   IGc20 EXT AIUB\n" +
                    "## 2434      0.0000\n").toByteArray(),
            ),
        )
        assertTrue(PgnssFetcher.looksLikeErp("VERSION 2\nCODE\n  MJD  X-P\n".toByteArray()))
        assertTrue(PgnssFetcher.looksLikeGzip(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
        assertTrue(PgnssFetcher.looksLikeGfc("modelname EGM96\nproduct_type gravity_field\n".toByteArray()))
        assertTrue(PgnssFetcher.looksLikeYuma("ID: 01\nTime of Applicability(s): 1\n".toByteArray()))
        assertTrue(PgnssFetcher.looksLikeGalileoAlmanac("<Almanacs><svAlmanac><SVID>02".toByteArray()))
        assertTrue(PgnssFetcher.looksLikeAgl("10 08 2026   20679\n 1   1  1\n".toByteArray()))
    }

    /**
     * A 200 carrying the wrong thing must FAIL and leave nothing behind. Writing it and reporting the
     * status afterwards is how a 162-byte HTML page ends up named `COD0OPSPRD_05D.SP3` — and the next
     * run then reads it, finds no epochs, and reports a successful build of an empty window.
     */
    @Test
    fun aTwoHundredWithTheWrongContentFailsAndLeavesNoFile() {
        server.enqueue(MockResponse.Builder().code(200).body(redirectPage).build())
        val thrown = runCatching {
            fetcher.fetch("COD0OPSPRD_05D.SP3", server.url("/CODE/COD0OPSPRD_05D.SP3").toString(), sniffSp3)
        }.exceptionOrNull()
        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        assertFalse(File(temp.root, "COD0OPSPRD_05D.SP3").exists())
    }

    /**
     * The CODE products answer a 301 to a DIFFERENT host. Refusing the cross-origin hop stores the
     * redirect page instead of the 9.6 MB orbit — with a 200 in the log either way.
     */
    @Test
    fun aRedirectIsFollowedRatherThanStored() {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/moved.SP3").build())
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("#dP2026  8 30  0  0  0.00000000    1441 d+D   IGc20 EXT AIUB\n## 2434      0.0000\n")
                .build(),
        )
        val file = fetcher.fetch("orbit.SP3", server.url("/CODE/COD0OPSPRD_05D.SP3").toString(), sniffSp3)
        assertTrue(file.readText().startsWith("#dP2026"))
    }

    /** A 404 is a failure, not an empty file. */
    @Test
    fun aNotFoundFailsWithoutTouchingTheDestination() {
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        val thrown = runCatching {
            fetcher.fetch("erp.ERP", server.url("/CODE/missing.ERP").toString(), sniffErp)
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertFalse(File(temp.root, "erp.ERP").exists())
    }

    /**
     * Progress is reported per file and the last report is the complete one, so a UI can show
     * "4 of 8, 9.6 MB" for a 25 MB run over mobile data instead of a spinner that looks hung.
     */
    @Test
    fun everyFileEndsWithACompletedProgressReport() {
        server.enqueue(MockResponse.Builder().code(200).body("ID: 01\nTime of Applicability(s): 1\n").build())
        fetcher.fetch("current_yuma.alm", server.url("/alm").toString(), sniffYuma)
        val last = reports.last()
        assertTrue(last.complete)
        assertEquals("current_yuma.alm", last.name)
        assertEquals(File(temp.root, "current_yuma.alm").length(), last.bytesRead)
    }

    /**
     * NOTHING IS CACHED, by 白い熊's explicit decision: a run always asks the server. A conditional
     * GET would let a stale orbit satisfy a fresh build, which is the failure the whole pipeline
     * exists to prevent.
     */
    @Test
    fun everyRequestAsksTheServerAgain() {
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(200).body("ID: 01\nTime of Applicability(s): 1\n").build())
        }
        repeat(2) { fetcher.fetch("a.alm", server.url("/alm").toString(), sniffYuma) }
        assertEquals(2, server.requestCount)
        val recorded = server.takeRequest()
        assertTrue(recorded.headers["Cache-Control"]!!.contains("no-store"))
    }

    // ── FTP ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The passive-mode port is `p1 * 256 + p2`, and the reply's own text contains other numbers.
     * Reading it wrong opens a connection to a port that is not listening, which times out after a
     * minute and reads exactly like "the mirror is down".
     */
    @Test
    fun thePassiveReplyYieldsTheDataPort() {
        assertEquals(59559, Ftp.parsePasv("227 Entering Passive Mode (194,85,163,17,232,167)"))
        assertEquals(20480, Ftp.parsePasv("227 Entering Passive Mode (10,0,0,1,80,0)."))
        assertEquals(null, Ftp.parsePasv("227 Entering Passive Mode"))
    }
}
