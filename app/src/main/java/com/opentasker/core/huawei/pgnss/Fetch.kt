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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
    /**
     * Where the last good ALMANAC of each kind is kept. Null disables the fallback entirely, which
     * is what a test wants and what the old behaviour was.
     *
     * Declared BEFORE [progress] so the trailing-lambda call stays the natural one — every caller
     * writes the progress callback as a block, and a parameter added after it would have turned
     * each of them into a named argument.
     */
    private val cacheDir: File? = null,
    private val progress: (FetchProgress) -> Unit = {},
) {

    /** What the run should say about where its inputs came from. Reset by each [fetchAll]. */
    private val notes = ArrayList<String>()

    /**
     * Fetch the lot.
     *
     * @param today the UTC date to build for; a parameter so a test can pin it.
     * @param wuhanIssues how many Wuhan orbit files to take. Three covers the arc the BeiDou
     *   integration needs.
     */
    fun fetchAll(today: LocalDate = LocalDate.now(ZoneOffset.UTC), wuhanIssues: Int = 3): PgnssSources {
        workDir.mkdirs()
        notes.clear()
        return PgnssSources(
            codeSp3 = fetchCodeSp3(),
            codeErp = fetchCodeErp(today),
            wuhanOrbits = fetchWuhanOrbits(today, wuhanIssues),
            egm96 = fetchEgm96(),
            // ── the three ALMANACS, each with the last good one behind it ───────────────────
            //
            // NOT the orbit products, and the distinction is the whole point. A cached ORBIT is a
            // WRONG orbit a day later, which is why nothing in that path may fall back to anything
            // (see the note at the top of this class). An almanac is the opposite kind of thing:
            // coarse elements whose job is to tell a receiver roughly where to look, published
            // every few days and useful for weeks. Serving a fortnight-old one costs a little
            // acquisition time; refusing to build at all costs the whole set.
            //
            // ESA's GSSC went down on the evening of 2026-09-14 — three connection failures and a
            // 404 across four dates, having served fine two hours earlier — and two builds in a row
            // died with "no Galileo almanac XML in the last 10 days". Nothing else was wrong. 白い熊:
            // *"How will we know when it's back?"* — with this, nobody has to.
            yuma = almanac("gps-yuma") { fetchYuma() },
            galileoXml = almanac("galileo") { fetchGalileoAlmanac(today) },
            glonassAgl = almanac("glonass") { fetchGlonassAlmanac(today) },
            // AT LEAST ONE navigation file, not specifically today's — and today's from the
            // stations themselves when no merged file covers it.
            //
            // It used to require today's and treat yesterday's as a bonus, which reads as caution
            // and is the opposite: today's IGS file is a 404 by design until the day closes, so the
            // requirement rested entirely on one same-day product on one host. When that host's
            // gateway went sour on 2026-09-14 the build died, although yesterday's file carries
            // every single thing it reads — the Klobuchar block, the UTC set and the BeiDou
            // ephemeris. The build was refusing over freshness it does not use.
            //
            // Ordered newest first because the merge de-duplicates against what is already there,
            // so the freshest copy of a record wins. Three days, so a weekend outage is survivable.
            brdcNav = buildList {
                var haveToday = false
                for (back in 0L..2L) {
                    runCatching { fetchBrdcNav(today.minusDays(back)) }.onSuccess {
                        add(it)
                        if (back == 0L) haveToday = true
                    }
                }
                // Only when the merged same-day file could not be had: three station files are a
                // hundred kilobytes and four more FTP round trips, which is worth paying to close a
                // gap and not worth paying otherwise.
                if (!haveToday) {
                    val hourly = runCatching {
                        fetchHourlyNav(LocalDateTime.now(ZoneOffset.UTC))
                    }.getOrDefault(emptyList())
                    if (hourly.isNotEmpty()) {
                        addAll(hourly)
                        notes.add(
                            "today's ephemeris from ${hourly.size} IGN station file(s) — " +
                                "no merged same-day file was available",
                        )
                    }
                }
            }.also {
                if (it.isEmpty()) {
                    throw IOException(
                        "no broadcast navigation file for any of the last three days, from " +
                            BRDC_SOURCES.joinToString(", ") { s -> s.name } +
                            " or IGN's hourly stations — this file carries the ionosphere and the " +
                            "BeiDou ephemeris and nothing can stand in for it",
                    )
                }
            },
            notes = notes.toList(),
        )
    }

    /**
     * One almanac: live if it can be had, and the last good one from the store if it cannot.
     *
     * The live copy is cached on every success, so the fallback is always one build behind at
     * worst. The cached name carries the date it was CACHED, which is what makes its age legible
     * in the run log and in the store without a sidecar to go stale beside it.
     *
     * [MAX_ALMANAC_AGE_DAYS] is the refusal. Past it the cache is no better than a guess and the
     * build should fail loudly, exactly as it did before this existed — the point of the fallback
     * is to survive an outage, not to go on serving a memory for a month.
     */
    internal fun almanac(kind: String, live: () -> File): File {
        val attempt = runCatching { live() }
        val dir = cacheDir
        attempt.getOrNull()?.let { file ->
            if (dir != null) {
                runCatching {
                    dir.mkdirs()
                    // `<kind>.<the day it was cached>.<the name it was published under>`. The cache
                    // date is what the age limit is measured on; the published name is what says
                    // the real vintage, because the live fetch may itself have walked back several
                    // days to find one. Both matter and neither needs a sidecar to go stale beside.
                    val today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val keep = File(dir, "$kind.$today.${file.name}")
                    file.copyTo(keep, overwrite = true)
                    // A cache, not an archive: one per kind, so the store cannot fill with almanacs.
                    dir.listFiles()
                        ?.filter { it.name.startsWith("$kind.") && it.name != keep.name }
                        ?.forEach { it.delete() }
                }
            }
            return file
        }
        val why = attempt.exceptionOrNull()
        val cached = dir?.listFiles()?.filter { it.name.startsWith("$kind.") }?.maxByOrNull { it.name }
            ?: throw IOException("the $kind almanac is unavailable and nothing is cached", why)
        val rest = cached.name.removePrefix("$kind.")
        val stamped = rest.take(10)
        val published = rest.drop(11).ifEmpty { cached.name }
        val age = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(stamped), LocalDate.now(ZoneOffset.UTC))
        }.getOrDefault(Long.MAX_VALUE)
        if (age > MAX_ALMANAC_AGE_DAYS) {
            throw IOException(
                "the $kind almanac is unavailable and the cached one was taken $age days ago " +
                    "($published; the limit is $MAX_ALMANAC_AGE_DAYS)",
                why,
            )
        }
        val target = File(workDir, published)
        cached.copyTo(target, overwrite = true)
        notes.add(
            "$kind almanac from the cache: $published, taken $age " +
                "${if (age == 1L) "day" else "days"} ago — ${why?.message ?: "the source did not answer"}",
        )
        return target
    }

    // ── the individual sources ──────────────────────────────────────────────────────────────────

    /**
     * CODE's free five-day predicted orbit, ~9.6 MB. It spans the whole 72-hour window outright.
     *
     * **The one orbit product with a fallback, and only because of what it is.** The rule at the
     * head of this class stands — a cached orbit is a *wrong* orbit a day later — and it is about
     * products that describe a fixed day. This one is a **five-day prediction**: yesterday's issue
     * still covers today's 72-hour window, one prediction-day older and correspondingly less exact,
     * which is a different thing from wrong. Two days is the cap, and it is arithmetic rather than
     * taste: a product issued on day D reaches D+5, a window opened on D+k closes at D+k+3, so
     * k ≤ 2 or it does not reach at all.
     *
     * And it is not trusted on that arithmetic alone. `validate()` compares the product's own last
     * epoch against the window it is about to build and refuses — *"the orbit product ends before
     * the 72 h window does"* — so a cached copy that falls short fails the build loudly instead of
     * shortening the forecast behind 白い熊's back. The build note says the copy was cached and how
     * old it is, every time it is used.
     *
     * Why it earned one: AIUB is the only host that serves this file. IGN and BKG carry IGS
     * combinations, not CODE's own prediction; `ftp.aiub.unibe.ch` does not answer over HTTP;
     * CDDIS wants an Earthdata login. On 2026-09-19 a single DNS failure — `Unable to resolve host
     * "download.aiub.unibe.ch"` — killed the build, the band went on wearing a set that expired two
     * days later, and 白い熊 walked with no fix.
     */
    fun fetchCodeSp3(): File = cachedOrbit("code-sp3", MAX_CODE_SP3_AGE_DAYS) {
        fetch(
            name = "COD0OPSPRD_05D.SP3",
            url = "$AIUB/COD0OPSPRD_05D.SP3",
            sniff = ::looksLikeSp3,
        )
    }

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
        // Twenty-one days of predicted pole, so an old copy reaches even further than the orbit's
        // does — and it comes from the same single host, so it fails in the same breath. Same cap
        // and the same loud note; see [fetchCodeSp3].
        return cachedOrbit("code-erp", MAX_CODE_SP3_AGE_DAYS) {
            fetch(name = name, url = "$AIUB/$name", sniff = ::looksLikeErp)
        }
    }

    /**
     * A product that may fall back to the last good copy — see [fetchCodeSp3] for why only these.
     *
     * Deliberately the same shape as [almanac], including the name that carries the cache date, so
     * the two read as one mechanism rather than as two conventions.
     */
    internal fun cachedOrbit(kind: String, maxAgeDays: Long, live: () -> File): File {
        val attempt = runCatching { live() }
        val dir = cacheDir
        attempt.getOrNull()?.let { file ->
            if (dir != null) {
                runCatching {
                    dir.mkdirs()
                    val today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val keep = File(dir, "$kind.$today.${file.name}")
                    file.copyTo(keep, overwrite = true)
                    dir.listFiles()
                        ?.filter { it.name.startsWith("$kind.") && it.name != keep.name }
                        ?.forEach { it.delete() }
                }
            }
            return file
        }
        val why = attempt.exceptionOrNull()
        val cached = dir?.listFiles()?.filter { it.name.startsWith("$kind.") }?.maxByOrNull { it.name }
            ?: throw IOException("$kind is unavailable and nothing is cached", why)
        val rest = cached.name.removePrefix("$kind.")
        val stamped = rest.take(10)
        val published = rest.drop(11).ifEmpty { cached.name }
        val age = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(stamped), LocalDate.now(ZoneOffset.UTC))
        }.getOrDefault(Long.MAX_VALUE)
        if (age > maxAgeDays) {
            throw IOException(
                "$kind is unavailable and the cached copy was taken $age days ago " +
                    "($published; the limit is $maxAgeDays, past which a five-day prediction " +
                    "cannot reach the end of a three-day window)",
                why,
            )
        }
        val target = File(workDir, published)
        cached.copyTo(target, overwrite = true)
        notes.add(
            "$kind FROM THE CACHE: $published, taken $age ${if (age == 1L) "day" else "days"} ago " +
                "— ${why?.message ?: "the source did not answer"}. The window check still applies.",
        )
        return target
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
            // RETRY THE FIRST DATE ONLY. This loop is already a retry — ten candidate days — and
            // wrapping each of them in three transport attempts multiplies the two: a GSSC outage
            // on 2026-09-15 cost nineteen minutes of thirty-second timeouts to reach a conclusion
            // the cache could have given in one. A dropped connection on TODAY'S file is worth a
            // second try; the ninth day back is not, and the answer for it is the next candidate.
            val attempt = runCatching {
                fetch(
                    "galileo_$date.xml", "$GSSC/$date.xml", ::looksLikeGalileoAlmanac,
                    attempts = if (back == 0) TRANSPORT_ATTEMPTS else 1,
                )
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

    /**
     * The BKG mixed broadcast navigation file — Klobuchar, the UTC set and the BeiDou ephemeris.
     *
     * **Two products, IGS first.** `BRDC00WRD_R` stopped carrying an `IONOSPHERIC CORR` block: its
     * header is down to a single `LEAP SECONDS` line, with no GPSA, no GPSB and no GPUT. That is
     * not a partial file — it is what BKG's `gfzrnx` conversion now emits, confirmed by fetching
     * days 248 and 249 by hand. The build needs GPS Klobuchar, so it died on every run from
     * 2026-09-02 onwards and left 白い熊's band on a set four days old (2026-09-06).
     *
     * `BRDC00IGS_R` carries GPSA, GPSB and GPUT and is a MIXED file like the other, so it serves
     * both purposes. It is a daily product published after the day closes, so TODAY is a 404 and
     * today falls back to `WRD` — which is fine, because the caller fetches several days and the
     * header is taken from whichever of them has one. Checked and rejected: `BRDM00DLR_S` has GPUT
     * but its ionosphere is GAL/BDS/QZS/IRN only, and `BRD400DLR_S` and `BRDC00WRD_S` have neither.
     */
    fun fetchBrdcNav(date: LocalDate): File {
        val year = date.year
        val doy = String.format(Locale.ROOT, "%03d", date.dayOfYear)
        var last: Exception? = null
        // FIVE SOURCES ACROSS FOUR ORGANISATIONS, and every one of them exists because of an
        // evening this file spent failing.
        //
        // On 2026-09-14 six builds in a row died because `igs.bkg.bund.de` was answering empty
        // replies. BKG was NOT down: it runs a second host on another address, `igs-ftp`, which
        // measured 10/10 on listings and 6/6 on fetches that same evening and served byte-identical
        // files. One hostname was mistaken for an organisation, and 白い熊 said so before the
        // measurement did. The orbit product has had mirrors since the day it was written; this
        // file, which is just as necessary, had one.
        //
        // Each source names its own product, because they are not the same product under different
        // roofs: ROB publishes `BRDC00GOP_R` and GOP's own server `BRDC01GOP_R`, with a different
        // path shape and no day directory. All three merged products verified identical in content
        // for day 256 — 447 GPS records and 886 BeiDou, GPSA, GPSB and GPUT present.
        // ONE SHOT EACH, PERSISTENCE ONLY AT THE END. Five sources across three days is fifteen
        // chances; giving each of them three transport attempts makes it forty-five, and on
        // 2026-09-15 that arithmetic turned a dead server into nineteen minutes of timeouts.
        // Trying the NEXT data centre is cheaper and more likely to work than trying the same one
        // again, so the alternatives are single-shot and only the last one — where there is nothing
        // left to fall back to — is worth retrying.
        for ((index, source) in BRDC_SOURCES.withIndex()) {
            val attempts = if (index == BRDC_SOURCES.size - 1) TRANSPORT_ATTEMPTS else 1
            try {
                return gunzip(source.fetch(this, year, doy, attempts))
            } catch (e: IOException) {
                // A product not published for this day YET is the ordinary case, not a fault, and
                // so is a data centre that does not carry it. Only running out of all of them is a
                // failure.
                last = e
            }
        }
        throw last ?: IOException(
            "no broadcast navigation file for $year/$doy from " +
                BRDC_SOURCES.joinToString(", ") { it.name },
        )
    }

    /**
     * Today's ephemeris from the stations themselves, when no merged file covers today.
     *
     * **This is the only independent same-day path that exists.** A sweep of every public data
     * centre on 2026-09-14 found no second organisation publishing a merged, global, same-day mixed
     * navigation file with a GPS Klobuchar block — `BRDC00WRD_R` at BKG is the only one of its kind.
     * The redundancy therefore has to be assembled rather than downloaded, out of IGN's hourly
     * per-station tree on the host this build already trusts for orbits.
     *
     * A handful is enough and a handful is the point. Wettzell alone at hour 18 carried 32 GPS and
     * 55 BeiDou satellites with a full Klobuchar header — MORE BeiDou than BKG's merged whole-day
     * file held at the time — and a union of five vetted stations reproduced the merged file's
     * constellation exactly, for about a hundred kilobytes. The list is vetted rather than
     * discovered because not every station writes the ionosphere block: `WSRT00NLD` has none.
     *
     * Files are published 1 to 65 minutes after their hour closes, so the hour before last is the
     * first one worth asking for.
     */
    fun fetchHourlyNav(now: LocalDateTime, wanted: Int = HOURLY_STATIONS_WANTED): List<File> {
        val got = ArrayList<File>()
        for (back in 1L..HOURLY_LOOK_BACK) {
            val at = now.minusHours(back)
            val year = at.year
            val doy = String.format(Locale.ROOT, "%03d", at.dayOfYear)
            val hh = String.format(Locale.ROOT, "%02d", at.hour)
            for (station in HOURLY_STATIONS) {
                if (got.size >= wanted) return got
                val name = "${station}_R_$year$doy${hh}00_01H_MN.rnx.gz"
                runCatching {
                    got += gunzip(
                        ftpDownload(name, IGN_HOST, "$IGN_HOURLY/$year/$doy/$name", ::looksLikeGzip),
                    )
                }
            }
            if (got.isNotEmpty()) return got
        }
        return got
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────

    /**
     * Download [url] to `workDir/[name]`, reporting progress and refusing anything that does not
     * look like the format asked for. The generic primitive behind every fetcher above.
     */
    fun fetch(
        name: String,
        url: String,
        sniff: (ByteArray) -> Boolean,
        attempts: Int = TRANSPORT_ATTEMPTS,
    ): File = retrying("$name from $url", attempts) { fetchOnce(name, url, sniff) }

    /**
     * Try [body] again when the CONNECTION broke, and never when the server answered.
     *
     * The distinction is the whole value. An `HTTP 404` means the file is not published for that day
     * and retrying it costs seconds on every build for a certainty — the nav fetch alone would walk
     * products × mirrors × days of them. A `Connection reset` or an `unexpected end of stream` means
     * the bytes were coming and stopped, which the very next attempt usually carries.
     *
     * Measured on the evening of 2026-09-14: of six consecutive failed builds, **three** died on a
     * dropped connection to a file that existed, and GSSC served three truncated bodies of a file it
     * then served whole. That evening produced no set at all.
     */
    private fun <T> retrying(what: String, attempts: Int = TRANSPORT_ATTEMPTS, body: () -> T): T {
        var last: IOException? = null
        for (attempt in 1..attempts) {
            try {
                return body()
            } catch (refused: SourceRefused) {
                // The server answered, and its answer was no. That is information, not a glitch.
                throw refused
            } catch (broken: IOException) {
                last = broken
                if (attempt < attempts) {
                    // Said out loud: a panel that goes quiet for four and a half seconds while the
                    // wire is retried reads as a hang, which is the fault this file already learned
                    // once with Wuhan's FTP.
                    progress(
                        FetchProgress(
                            "$what — retrying (${broken.message ?: "connection lost"})",
                            "", 0, 0, false, 0,
                        ),
                    )
                    Thread.sleep(TRANSPORT_BACKOFF_MS * attempt)
                }
            }
        }
        throw last ?: IOException("$what failed")
    }

    private fun fetchOnce(name: String, url: String, sniff: (ByteArray) -> Boolean): File {
        val target = File(workDir, name)
        // Remove any earlier copy FIRST, so a failed request can never leave yesterday's file
        // sitting under today's name for the build to pick up.
        target.delete()
        val started = System.currentTimeMillis()
        // SAID BEFORE THE WAIT, not after it.
        //
        // Every other report in this file is emitted from inside the read loop, so a request whose
        // server has not answered yet emits nothing at all — and the panel goes on showing the last
        // file that DID deliver bytes. 白い熊, 2026-09-21: ten minutes reading `current_yuma.alm`
        // and `7/11`, with nothing moving, while the almanac after it was the one being waited for.
        // The display was not merely still; it was naming the wrong file.
        progress(FetchProgress(name, url, 0, 0, false, 0))
        val request = Request.Builder()
            .url(url)
            // No conditional GET, no stored response: see the class KDoc.
            .header("Cache-Control", "no-store, no-cache, max-age=0")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SourceRefused(response.code, url)
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

    private fun ftpDownload(
        name: String,
        host: String,
        path: String,
        sniff: (ByteArray) -> Boolean,
        attempts: Int = TRANSPORT_ATTEMPTS,
    ): File = retrying("$name from $host", attempts) { ftpDownloadOnce(name, host, path, sniff) }

    private fun ftpDownloadOnce(
        name: String,
        host: String,
        path: String,
        sniff: (ByteArray) -> Boolean,
    ): File {
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
        /**
         * How old a cached almanac may be before the build refuses it.
         *
         * Ten days, measured from the day it was CACHED — and the almanac inside it may be several
         * days older still, because the live Galileo fetch walks back up to ten days itself to find
         * one. That is why the note names the published file: the limit bounds the cache, the name
         * shows the vintage.
         *
         * The EXTRA file this feeds claims a week's validity, and an almanac is coarse enough that
         * some days past that still tells a receiver where to look. A month-old one is a memory,
         * not an input, and shipping it inside a file stamped today would be the same lie the
         * four-day-old set told on 2026-09-06. Past this the build fails loudly, exactly as it did
         * before the cache existed.
         */
        const val MAX_ALMANAC_AGE_DAYS = 10L

        private const val AIUB = "https://download.aiub.unibe.ch/CODE"
        private const val ICGEM = "https://icgem.gfz-potsdam.de"
        private const val GSSC = "https://www.gsc-europa.eu/sites/default/files/sites/all/files"
        /**
         * BKG's FTP host, NOT its HTTPS gateway.
         *
         * `igs.bkg.bund.de` over HTTPS answered 3 of 10 listings and 5 of 8 file fetches on the
         * evening of 2026-09-14; `igs-ftp.bkg.bund.de`, a different address, answered 10 of 10 and
         * 6 of 6 with byte-identical files. The gateway is the flaky part, not the archive.
         */
        private const val BKG_HOST = "igs-ftp.bkg.bund.de"
        private const val BKG_PATH = "/IGS/BRDC"

        /**
         * Where a merged broadcast navigation file can be had, in the order worth asking.
         *
         * BKG's two products first: `IGS` has the fullest header and `WRD` is the only same-day
         * merged file anywhere. Then the same IGS product from IGN, then two other organisations
         * entirely — the Royal Observatory of Belgium and GOP — so that a bad night at one data
         * centre stops nothing.
         */
        internal val BRDC_SOURCES = listOf(
            BrdcSource("BKG BRDC00IGS_R") { f, year, doy, a ->
                val n = "BRDC00IGS_R_$year${doy}0000_01D_MN.rnx.gz"
                f.ftpDownload(n, BKG_HOST, "$BKG_PATH/$year/$doy/$n", ::looksLikeGzip, a)
            },
            BrdcSource("BKG BRDC00WRD_R (same-day)") { f, year, doy, a ->
                val n = "BRDC00WRD_R_$year${doy}0000_01D_MN.rnx.gz"
                f.ftpDownload(n, BKG_HOST, "$BKG_PATH/$year/$doy/$n", ::looksLikeGzip, a)
            },
            BrdcSource("IGN BRDC00IGS_R") { f, year, doy, a ->
                val n = "BRDC00IGS_R_$year${doy}0000_01D_MN.rnx.gz"
                f.ftpDownload(n, IGN_HOST, "$IGN_DATA/$year/$doy/$n", ::looksLikeGzip, a)
            },
            // A different organisation, and its own product name. No day directory: the year holds
            // every day's file.
            BrdcSource("ROB BRDC00GOP_R") { f, year, doy, a ->
                val n = "BRDC00GOP_R_$year${doy}0000_01D_MN.rnx.gz"
                f.ftpDownload(n, "ftp.epncb.oma.be", "/pub/obs/BRDC/$year/$n", ::looksLikeGzip, a)
            },
            // GOP's own server carries the same content under `BRDC01GOP`, not `BRDC00GOP`.
            BrdcSource("GOP BRDC01GOP_R") { f, year, doy, a ->
                val n = "BRDC01GOP_R_$year${doy}0000_01D_MN.rnx.gz"
                f.ftpDownload(n, "ftp.pecny.cz", "/LDC/orbits_brd/gop3/$year/$n", ::looksLikeGzip, a)
            },
        )

        /**
         * IGN stations that carry BOTH a GPS Klobuchar header and a full BeiDou set, measured.
         *
         * Vetted, not guessed: `WSRT00NLD` writes no ionosphere block at all, and a station picked
         * at random is as likely to be that as to be Wettzell. Ordered by what they carried at hour
         * 18 on 2026-09-14 — WTZR 32 GPS / 55 BeiDou PRNs, MATE 32/48, ALAC 32/47, EBRE 32/44,
         * NTUS 32/37, BUCU 32/35 — and spread across Europe and Asia so one site's outage is not
         * the list's.
         */
        internal val HOURLY_STATIONS = listOf(
            "WTZR00DEU", "MATE00ITA", "ALAC00ESP", "EBRE00ESP", "NTUS00SGP", "BUCU00ROU",
        )

        /** Three stations reproduced the merged file's constellation; take three and stop. */
        const val HOURLY_STATIONS_WANTED = 3

        /** Hours to walk back before giving up. Publication lags the hour by up to ~65 minutes. */
        const val HOURLY_LOOK_BACK = 4L

        private const val IGN_HOST = "igs.ign.fr"
        private const val IGN_DATA = "/pub/igs/data"
        private const val IGN_HOURLY = "/pub/igs/data/hourly"
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

        /** Three tries at the wire. Not at a 404 — see [retrying]. */
        /**
         * How old a cached CODE product may be: two days, which is arithmetic and not taste.
         *
         * A five-day prediction issued on day D reaches D+5; a 72-hour window opened on D+k closes
         * at D+k+3. Past k = 2 it cannot reach the end of the window at all, and `validate()` would
         * refuse it anyway — this simply says so before nine megabytes are copied.
         */
        const val MAX_CODE_SP3_AGE_DAYS = 2L

        const val TRANSPORT_ATTEMPTS = 3

        /** Multiplied by the attempt number: 1.5 s, then 3 s. Long enough to matter, short enough. */
        const val TRANSPORT_BACKOFF_MS = 1500L

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
/**
 * One place a broadcast navigation file can be had, and how to ask for it.
 *
 * Each carries its own product name and path shape, because these are not one product under
 * different roofs — ROB's is `BRDC00GOP_R` under a year directory with no day, GOP's own server
 * calls the same content `BRDC01GOP_R`. Named, so a failure can say which one declined, which is
 * what distinguishes "not published for that day yet" from "that host is down".
 */
/**
 * The server answered, and its answer was no.
 *
 * Distinct from a broken connection, which the very next attempt usually carries. The difference is
 * what keeps [PgnssFetcher.retrying] from burning seconds on a certainty: "not published for this
 * day yet" is the ORDINARY case for a daily product, and the navigation fetch alone would otherwise
 * retry it across two products, two mirrors and three days.
 */
class SourceRefused(val code: Int, what: String) : IOException("$code for $what")

internal class BrdcSource(
    val name: String,
    private val ask: (PgnssFetcher, Int, String, Int) -> File,
) {
    fun fetch(fetcher: PgnssFetcher, year: Int, doy: String, attempts: Int): File =
        ask(fetcher, year, doy, attempts)
}

data class PgnssSources(
    val codeSp3: File,
    val codeErp: File,
    val wuhanOrbits: List<File>,
    val egm96: File,
    val yuma: File,
    val galileoXml: File,
    val glonassAgl: File,
    val brdcNav: List<File>,
    /** What the fetch wants said about itself — which almanac came from the cache, and how old. */
    val notes: List<String> = emptyList(),
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
        // A 5xx is the server declining — a file that is not there, a path that is not served.
        // Anything else that goes wrong here is the wire, and the wire is worth another try.
        if (accept.isNotEmpty() && code !in accept.toList()) {
            if (code in 500..599) throw SourceRefused(code, "FTP: $line")
            throw IOException("FTP said: $line")
        }
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
