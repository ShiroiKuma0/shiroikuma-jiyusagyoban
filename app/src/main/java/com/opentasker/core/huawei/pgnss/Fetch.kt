package com.opentasker.core.huawei.pgnss

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads every input an on-device `HW_PGNSS_*` build needs — about 25 MB from six hosts.
 *
 * ALWAYS DOWNLOADS, EVERY RUN. There is no cache and no check for a metered connection: 白い熊
 * decided that explicitly. A cached orbit product is a WRONG orbit product a day later, and the
 * failure it causes — a build that succeeds and produces a plausible file whose window has already
 * closed — is exactly what this whole pipeline exists to stop shipping. So the only correct answer
 * to "is the copy on disk still good?" is not to keep one.
 *
 * EVERY DOWNLOAD IS SNIFFED. `download.aiub.unibe.ch` answers a bad path with HTTP 200 and a
 * 162-byte HTML page, and it answers a good one with a 301 to a different host; twice in one day
 * that page landed on disk looking like data. So redirects are followed (across hosts — the CODE
 * products genuinely live on S3), and the first 4 KB of every response has to look like the format
 * that was asked for or the file is deleted and the fetch fails.
 *
 * FTP IS NOT OPTIONAL. Two of the sources — the Wuhan multi-GNSS orbits and the Russian IAC's
 * GLONASS almanac — are published on anonymous FTP and nowhere else reachable without a login
 * (CDDIS wants an Earthdata account; ESA's GSSC, SOPAC and BKG carry no `WUM0MGXNRT` at all).
 * OkHttp speaks no FTP, so [Ftp] below is a ~120-line RETR/LIST client on a plain socket.
 * Everything else goes over OkHttp.
 *
 * AN EARLIER VERSION OF THIS NOTE SAID IGN "does not answer at all from here". THAT WAS WRONG, and
 * it cost about five minutes on every build for as long as it stood. IGN's FTP rejects curl's
 * DEFAULT anonymous password with a 530, which reads exactly like a dead host — but `anonymous` /
 * `anonymous@`, which [Ftp] already sends, logs in fine. Measured interleaved against Wuhan over
 * four rounds on the same file: **2295 KB/s against 23.8, a factor of 96**, identical md5, and IGN
 * publishes each new issue about twenty minutes EARLIER. The lesson is worth more than the number:
 * "we could not find a mirror" was a statement about the search, not about the world.
 */
class PgnssFetcher(
    private val workDir: File,
    private val client: OkHttpClient = defaultClient(),
    private val progress: (FetchProgress) -> Unit = {},
) {

    /**
     * Fetch the lot.
     *
     * @param today the UTC date to build for; a parameter so a test can pin it.
     * @param wuhanIssues how many Wuhan orbit files to take. Three covers the arc the BeiDou
     *   integration needs.
     */
    fun fetchAll(today: LocalDate = LocalDate.now(ZoneOffset.UTC), wuhanIssues: Int = 3): PgnssSources {
        workDir.mkdirs()
        return PgnssSources(
            codeSp3 = fetchCodeSp3(),
            codeErp = fetchCodeErp(today),
            wuhanOrbits = fetchWuhanOrbits(today, wuhanIssues),
            egm96 = fetchEgm96(),
            yuma = fetchYuma(),
            galileoXml = fetchGalileoAlmanac(today),
            glonassAgl = fetchGlonassAlmanac(today),
            // Today's navigation file is required; the PREVIOUS day's only lengthens the arc, and a
            // day that has not been published yet must not stop the build. Today first, because the
            // merge de-duplicates against what is already there.
            brdcNav = buildList {
                add(fetchBrdcNav(today))
                runCatching { add(fetchBrdcNav(today.minusDays(1))) }
            },
        )
    }

    // ── the individual sources ──────────────────────────────────────────────────────────────────

    /** CODE's free five-day predicted orbit, ~9.6 MB. It spans the whole 72-hour window outright. */
    fun fetchCodeSp3(): File = fetch(
        name = "COD0OPSPRD_05D.SP3",
        url = "$AIUB/COD0OPSPRD_05D.SP3",
        sniff = ::looksLikeSp3,
    )

    /**
     * CODE's free 21-day PREDICTED Earth-rotation parameters.
     *
     * THE DATED NAME LAGS ITS CONTENTS BY A DAY: the file called `...<doy>0000` has its first epoch
     * on doy+1, so "today's" ERP is named for YESTERDAY'S day-of-year and asking for today's returns
     * a 404. The bucket keeps about a week and has no directory listing, so the name is computed,
     * not looked up. `COD0OPSULT.ERP` is not a substitute: it carries one day, and the integration
     * needs the pole across the whole arc and window.
     */
    fun fetchCodeErp(today: LocalDate): File {
        val yd = yearDoy(today.minusDays(1))
        val name = "COD0OPSPRD_${yd}0000_21D_06H_ERP.ERP"
        return fetch(name = name, url = "$AIUB/$name", sniff = ::looksLikeErp)
    }

    /**
     * The `WUM0MGXNRT` 48-hour multi-GNSS product — the only free source of BeiDou orbits.
     *
     * Taken from the first mirror in [WUM_MIRRORS] that has it, which is IGN rather than Wuhan and
     * is worth roughly five minutes of every build. Wuhan stays as the fallback: it is the origin,
     * so if the product exists anywhere it exists there.
     *
     * ISSUES ARE PICKED A DAY APART, not an hour. Each file carries ONE day of observed orbit
     * followed by one predicted; consecutive hourly issues overlap almost entirely, so three of them
     * together still give barely more than 24 hours of arc, and the integration silently fits a
     * dynamical model to a third of the data it thinks it has.
     */
    fun fetchWuhanOrbits(today: LocalDate, issues: Int = 3): List<File> {
        val week = gpsWeekOf(today)
        val failures = mutableListOf<String>()
        for ((host, path) in WUM_MIRRORS) {
            val available = LinkedHashMap<String, Int>()      // file name -> week directory
            for (w in intArrayOf(week, week - 1, week - 2)) {
                val names = runCatching { Ftp.list(host, "$path/$w/") }.getOrDefault(emptyList())
                for (n in names) if (WUM_ORB.matches(n)) available.putIfAbsent(n, w)
                if (spacedIssues(available.keys, issues).size >= issues) break
            }
            val chosen = spacedIssues(available.keys, issues)
            if (chosen.isEmpty()) {
                failures += "$host: no WUM0MGXNRT files in weeks ${week - 2}..$week"
                continue
            }
            // The listing and the download must come from the SAME mirror. They carry the same
            // product, but not necessarily the same issues at the same moment — IGN publishes
            // earlier — and a name taken from one host is not a promise about the other.
            val got = runCatching {
                chosen.map { name ->
                    val at = "$path/${available.getValue(name)}/$name"
                    gunzip(ftpDownload(name, host, at, ::looksLikeGzip))
                }
            }
            got.getOrNull()?.let { return it }
            failures += "$host: ${got.exceptionOrNull()?.message ?: "download failed"}"
        }
        throw IOException("no WUM0MGXNRT orbit files on any mirror — ${failures.joinToString("; ")}")
    }

    /**
     * EGM96, ~5.6 MB. It never changes, and it is still downloaded every run for the same reason as
     * everything else: the one file kept on disk is the one nobody notices has been truncated.
     *
     * The direct link carries a content hash that ICGEM has changed before, so a failed sniff falls
     * back to reading the model table and resolving the link there.
     */
    fun fetchEgm96(): File {
        val direct = runCatching {
            fetch("EGM96.gfc", "$ICGEM/getmodel/gfc/$EGM96_HASH/EGM96.gfc", ::looksLikeGfc)
        }
        direct.getOrNull()?.let { return it }
        val table = httpText("$ICGEM/tom_longtime")
        val href = EGM96_LINK.find(table)?.value
            ?: throw IOException("ICGEM model table no longer links an EGM96.gfc", direct.exceptionOrNull())
        return fetch("EGM96.gfc", "$ICGEM$href", ::looksLikeGfc)
    }

    /** The Navcen YUMA GPS almanac. */
    fun fetchYuma(): File = fetch(
        name = "current_yuma.alm",
        url = "https://www.navcen.uscg.gov/sites/default/files/gps/almanac/current_yuma.alm",
        sniff = ::looksLikeYuma,
    )

    /** The ESA GSSC Galileo almanac XML, named for the day it was issued; walk back until one exists. */
    fun fetchGalileoAlmanac(today: LocalDate, lookBackDays: Int = 10): File {
        var last: Throwable? = null
        for (back in 0 until lookBackDays) {
            val date = today.minusDays(back.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val attempt = runCatching {
                fetch("galileo_$date.xml", "$GSSC/$date.xml", ::looksLikeGalileoAlmanac)
            }
            attempt.getOrNull()?.let { return it }
            last = attempt.exceptionOrNull()
        }
        throw IOException("no Galileo almanac XML in the last $lookBackDays days", last)
    }

    /**
     * The Russian IAC GLONASS almanac.
     *
     * The IAC posts `.agl` files irregularly — weeks can pass — so the directory is listed and the
     * newest file at or before [today] taken, rather than probing dates one by one and concluding
     * from a 404 that GLONASS is unavailable.
     */
    fun fetchGlonassAlmanac(today: LocalDate): File {
        val stamp = today.format(DateTimeFormatter.ofPattern("yyMMdd", Locale.ROOT))
        for (year in intArrayOf(today.year, today.year - 1)) {
            val dir = "$IAC_PATH/$year/"
            val names = runCatching { Ftp.list(IAC_HOST, dir) }.getOrDefault(emptyList())
                .filter { AGL_NAME.matches(it) && it.substring(5, 11) <= stamp }
                .sorted()
            val newest = names.lastOrNull() ?: continue
            return ftpDownload(newest, IAC_HOST, "$dir$newest", ::looksLikeAgl)
        }
        throw IOException("no IAC GLONASS almanac available for ${today.year} or ${today.year - 1}")
    }

    /** The BKG mixed broadcast navigation file — Klobuchar, the UTC set and the BeiDou ephemeris. */
    fun fetchBrdcNav(date: LocalDate): File {
        val year = date.year
        val doy = String.format(Locale.ROOT, "%03d", date.dayOfYear)
        val name = "BRDC00WRD_R_$year${doy}0000_01D_MN.rnx.gz"
        val gz = fetch(name, "$BKG/$year/$doy/$name", ::looksLikeGzip)
        return gunzip(gz)
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────

    /**
     * Download [url] to `workDir/[name]`, reporting progress and refusing anything that does not
     * look like the format asked for. The generic primitive behind every fetcher above.
     */
    fun fetch(name: String, url: String, sniff: (ByteArray) -> Boolean): File {
        val target = File(workDir, name)
        // Remove any earlier copy FIRST, so a failed request can never leave yesterday's file
        // sitting under today's name for the build to pick up.
        target.delete()
        val started = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            // No conditional GET, no stored response: see the class KDoc.
            .header("Cache-Control", "no-store, no-cache, max-age=0")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val declared = response.body.contentLength()
            response.body.byteStream().use { source ->
                stream(name, url, source, target, declared, started, sniff)
            }
        }
        return target
    }

    private fun httpText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-store, no-cache, max-age=0")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body.string()
        }
    }

    private fun ftpDownload(name: String, host: String, path: String, sniff: (ByteArray) -> Boolean): File {
        val target = File(workDir, name)
        target.delete()
        val started = System.currentTimeMillis()
        Ftp.retrieve(host, path) { source, declared ->
            stream(name, "ftp://$host$path", source, target, declared, started, sniff)
        }
        return target
    }

    /**
     * Copy [source] to [target], reporting progress and sniffing the head.
     *
     * A failed sniff DELETES the file. Leaving a 162-byte HTML page named `COD0OPSPRD_05D.SP3` on
     * disk is how the next run picks it up and reports a successful build.
     */
    private fun stream(
        name: String,
        url: String,
        source: InputStream,
        target: File,
        declaredLength: Long,
        startedAtMillis: Long,
        sniff: (ByteArray) -> Boolean,
    ) {
        val head = ByteArray(SNIFF_BYTES)
        var headLength = 0
        var total = 0L
        var lastReport = 0L
        try {
            target.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (headLength < SNIFF_BYTES) {
                        val take = minOf(read, SNIFF_BYTES - headLength)
                        System.arraycopy(buffer, 0, head, headLength, take)
                        headLength += take
                    }
                    sink.write(buffer, 0, read)
                    total += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        progress(FetchProgress(name, url, total, declaredLength, false, now - startedAtMillis))
                    }
                }
            }
            if (total == 0L) throw IOException("$url returned an empty body")
            if (!sniff(head.copyOf(headLength))) {
                throw IOException("$url answered $total bytes that are not a $name (content sniff failed)")
            }
        } catch (error: IOException) {
            target.delete()
            throw error
        }
        progress(
            FetchProgress(name, url, total, total, true, System.currentTimeMillis() - startedAtMillis),
        )
    }

    /** Decompress `x.gz` next to itself as `x`, and report it like any other step. */
    private fun gunzip(gz: File): File {
        val target = File(gz.parentFile, gz.name.removeSuffix(".gz"))
        val started = System.currentTimeMillis()
        var total = 0L
        GZIPInputStream(gz.inputStream().buffered()).use { source ->
            target.outputStream().use { sink -> total = source.copyTo(sink) }
        }
        progress(
            FetchProgress(
                target.name, gz.absolutePath, total, total, true,
                System.currentTimeMillis() - started,
            ),
        )
        return target
    }

    companion object {
        private const val AIUB = "https://download.aiub.unibe.ch/CODE"
        private const val ICGEM = "https://icgem.gfz-potsdam.de"
        private const val GSSC = "https://www.gsc-europa.eu/sites/default/files/sites/all/files"
        private const val BKG = "https://igs.bkg.bund.de/root_ftp/IGS/BRDC"
        /**
         * Where `WUM0MGXNRT` can be had, fastest first. The product is Wuhan's either way — `WUM`
         * is Wuhan Multi-GNSS — and both mirrors serve byte-identical files; only the wire speed
         * differs, and it differs by two orders of magnitude.
         *
         * IGN's copy is NOT under `mgex/`. That legacy directory stops around week 2044, which is
         * why an earlier search concluded the product was not there.
         */
        private val WUM_MIRRORS = listOf(
            "igs.ign.fr" to "/pub/igs/products",
            "igs.gnsswhu.cn" to "/pub/gps/products/mgex",
        )
        private const val IAC_HOST = "ftp.glonass-iac.ru"
        private const val IAC_PATH = "/MCC/ALMANAC"

        /** The ICGEM content hash for EGM96 as of 2026-08-30; [fetchEgm96] recovers if it moves. */
        private const val EGM96_HASH =
            "971b0a3b49a497910aad23cd85e066d4cd9af0aeafe7ce6301a696bed8570be3"

        private const val USER_AGENT = "Mozilla/5.0 (Android) shiroikuma-jiyusagyoban/pgnss"
        private const val SNIFF_BYTES = 4096
        private const val PROGRESS_INTERVAL_MS = 250L

        /** Roughly what a whole run moves, for a progress bar that wants a denominator. */
        const val EXPECTED_TOTAL_BYTES: Long = 25L * 1024 * 1024

        private val EGM96_LINK = Regex("/getmodel/gfc/[0-9a-f]+/EGM96\\.gfc")
        private val WUM_ORB = Regex("WUM0MGXNRT_\\d{11}_02D_05M_ORB\\.SP3\\.gz")
        private val AGL_NAME = Regex("MCCT_\\d{6}\\.agl")

        /** OkHttp configured for large, slow, redirect-crossing downloads. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // The CODE products 301 to an S3 bucket on ANOTHER host. Refusing the cross-origin hop
            // stores the 162-byte redirect page instead of the 9.6 MB orbit.
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()

        /**
         * Pick [count] issues spaced at least [minGapHours] apart, newest first.
         *
         * The names are `WUM0MGXNRT_<yyyy><doy><hh>00_...`, so the 11 digits sort chronologically
         * and the spacing can be computed on them directly. The default gap is a day less an hour
         * of slack, because the mirror does drop the occasional hourly issue and a strict 24 would
         * then silently return two files instead of three. Fewer than [count] comes back as a SHORT
         * list, never as a repeated file.
         */
        fun spacedIssues(names: Collection<String>, count: Int, minGapHours: Int = 23): List<String> {
            val sorted = names.filter { WUM_ORB.matches(it) }.sortedDescending()
            val out = ArrayList<String>(count)
            var lastHour = Long.MAX_VALUE
            for (name in sorted) {
                val hour = issueHour(name) ?: continue
                if (lastHour - hour < minGapHours) continue
                out.add(name)
                lastHour = hour
                if (out.size == count) break
            }
            return out
        }

        /** Absolute hours for a `WUM0MGXNRT_<yyyy><doy><hh>00` name, or null if it is not one. */
        fun issueHour(name: String): Long? {
            val stamp = name.substringAfter("WUM0MGXNRT_", "").take(11)
            if (stamp.length != 11 || !stamp.all { it.isDigit() }) return null
            val year = stamp.substring(0, 4).toInt()
            val doy = stamp.substring(4, 7).toInt()
            val hour = stamp.substring(7, 9).toInt()
            return LocalDate.ofYearDay(year, doy).toEpochDay() * 24 + hour
        }

        /** GPS week of a date — the week directory a product file is filed under. */
        fun gpsWeekOf(date: LocalDate): Int =
            ((date.toEpochDay() * 86400L - UNIX_GPS) / 604800L).toInt()

        /** `yyyydoy`, the stamp CODE and Wuhan name their files with. */
        fun yearDoy(date: LocalDate): String =
            String.format(Locale.ROOT, "%04d%03d", date.year, date.dayOfYear)

        // ── content sniffs ──────────────────────────────────────────────────────────────────────
        // Each one asks "is this the format I asked for", never "is this non-empty".

        fun looksLikeSp3(head: ByteArray): Boolean {
            val text = head.decodeToString()
            return text.startsWith("#") && text.contains("\n## ")
        }

        fun looksLikeErp(head: ByteArray): Boolean {
            val text = head.decodeToString()
            return text.startsWith("VERSION") && text.contains("MJD")
        }

        fun looksLikeGzip(head: ByteArray): Boolean =
            head.size >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()

        fun looksLikeGfc(head: ByteArray): Boolean =
            head.decodeToString().contains("gravity_field")

        fun looksLikeYuma(head: ByteArray): Boolean =
            head.decodeToString().contains("Time of Applicability")

        fun looksLikeGalileoAlmanac(head: ByteArray): Boolean =
            head.decodeToString().contains("<svAlmanac>")

        fun looksLikeAgl(head: ByteArray): Boolean =
            AGL_FIRST_LINE.containsMatchIn(head.decodeToString())

        private val AGL_FIRST_LINE = Regex("^\\s*\\d\\d \\d\\d \\d{4}")
    }
}

/** One progress report. [totalBytes] is -1 while the server has not declared a length. */
data class FetchProgress(
    val name: String,
    val url: String,
    val bytesRead: Long,
    val totalBytes: Long,
    val complete: Boolean,
    val elapsedMillis: Long,
)

/** Everything a build needs, on disk. */
data class PgnssSources(
    val codeSp3: File,
    val codeErp: File,
    val wuhanOrbits: List<File>,
    val egm96: File,
    val yuma: File,
    val galileoXml: File,
    val glonassAgl: File,
    val brdcNav: List<File>,
)

/**
 * A minimal anonymous FTP client: `LIST` and `RETR`, passive mode, binary.
 *
 * Written because OkHttp speaks no FTP and the two sources below are published nowhere else that
 * answers without a login. It implements exactly what those two servers need and nothing more —
 * no active mode, no resume, no TLS (neither host offers it).
 */
object Ftp {

    private const val PORT = 21
    private const val TIMEOUT_MS = 60_000

    /** Bare entry names in [path], from a `LIST` (the servers here both return `ls -l` lines). */
    fun list(host: String, path: String): List<String> {
        val out = ArrayList<String>()
        session(host) { control, reader ->
            command(control, reader, "TYPE A", 200)
            transfer(host, control, reader, "LIST $path") { data, _ ->
                BufferedReader(InputStreamReader(data, StandardCharsets.US_ASCII)).forEachLine { line ->
                    // "-rw-r--r--  1 ftp ftp  4848 Aug 11 08:32 MCCT_260810.agl" — the name is
                    // everything after the 8th field, so a name with spaces survives.
                    val name = line.trim().split(Regex("\\s+"), limit = 9).getOrNull(8)
                    if (!name.isNullOrBlank()) out.add(name.trim())
                }
            }
        }
        return out
    }

    /** Stream [path] to [sink], which is handed the data stream and the declared length (or -1). */
    fun retrieve(host: String, path: String, sink: (InputStream, Long) -> Unit) {
        session(host) { control, reader ->
            command(control, reader, "TYPE I", 200)
            val size = runCatching {
                command(control, reader, "SIZE $path", 213).substringAfter(' ').trim().toLong()
            }.getOrDefault(-1L)
            transfer(host, control, reader, "RETR $path") { data, _ -> sink(data, size) }
        }
    }

    private fun session(host: String, body: (OutputStream, BufferedReader) -> Unit) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val control = socket.getOutputStream()
            expect(reader, 220)
            command(control, reader, "USER anonymous", 230, 331)
            command(control, reader, "PASS anonymous@", 230, 202)
            body(control, reader)
            runCatching { send(control, "QUIT") }
        }
    }

    private fun transfer(
        host: String,
        control: OutputStream,
        reader: BufferedReader,
        verb: String,
        body: (InputStream, Long) -> Unit,
    ) {
        val pasv = command(control, reader, "PASV", 227)
        val port = parsePasv(pasv) ?: throw IOException("unparsable PASV reply: $pasv")
        Socket().use { data ->
            data.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            data.soTimeout = TIMEOUT_MS
            send(control, verb)
            // 125 and 150 both mean "the data connection is open"; anything else is a refusal, and a
            // refusal read as success gives an empty file that looks like an empty directory.
            expect(reader, 125, 150)
            body(data.getInputStream(), -1L)
        }
        expect(reader, 226, 250)
    }

    private fun command(
        control: OutputStream,
        reader: BufferedReader,
        verb: String,
        vararg accept: Int,
    ): String {
        send(control, verb)
        return expect(reader, *accept)
    }

    private fun send(control: OutputStream, verb: String) {
        control.write((verb + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
        control.flush()
    }

    /** Read one reply, folding a multi-line `nnn-` continuation, and check its code. */
    private fun expect(reader: BufferedReader, vararg accept: Int): String {
        var line = reader.readLine() ?: throw IOException("FTP connection closed")
        if (line.length >= 4 && line[3] == '-') {
            val tag = line.take(3)
            while (true) {
                val next = reader.readLine() ?: throw IOException("FTP connection closed mid-reply")
                if (next.startsWith("$tag ")) {
                    line = next
                    break
                }
            }
        }
        val code = line.take(3).toIntOrNull() ?: throw IOException("unparsable FTP reply: $line")
        if (accept.isNotEmpty() && code !in accept.toList()) throw IOException("FTP said: $line")
        return line
    }

    /** `227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)` -> the data port. */
    fun parsePasv(reply: String): Int? {
        val digits = Regex("\\((\\d+(?:,\\d+){5})\\)").find(reply)?.groupValues?.get(1)
            ?: Regex("(\\d+(?:,\\d+){5})").find(reply)?.groupValues?.get(1)
            ?: return null
        val parts = digits.split(",").map { it.trim().toInt() }
        return parts[4] * 256 + parts[5]
    }
}
