package com.opentasker.core.huawei.pgnss

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The whole on-device build: downloads, fits, integrates, encodes, and writes the six
 * `HW_PGNSS_*` files `huawei.gnss` serves.
 *
 * This is the orchestration only. Every number it produces comes from the ported pieces beside it —
 * [PgnssFetcher], [Sp3], [Orbit], [LeastSquares], [Records], [ForceModel], [Propagator], [Egm96],
 * [Almanac] and [PgnssExtraFile] — and the sequence it runs them in is `scripts/pgnss-build.py`'s
 * `main()` followed by `scripts/pgnss-extra-build.py`'s. Those two scripts are the reference
 * implementations and they are the only thing this is allowed to disagree with by accident.
 *
 * ## The rule this file exists to obey
 * **A missing or unusable input fails, loudly, before anything is written.** On 2026-08-30 the
 * Python printed one line about falling back and then produced a complete, plausible, correctly
 * sized set whose BeiDou file had expired two days earlier — and reported success. So:
 *
 * * every input is validated up front, before ten minutes of CPU is spent ([validate]);
 * * nothing is substituted for anything: there is no cache, no "keep the old one", no default;
 * * the six files are assembled **entirely in memory** and written only once all six exist, so a
 *   failure half way through cannot leave a store holding three fresh files and three stale ones.
 *
 * ## What is generated and what is carried
 * GPS, Galileo, GLONASS and BeiDou are built here from free orbit products. `HW_PGNSS_EXTRA` is
 * assembled by [PgnssExtraFile] from public almanacs.
 *
 * **QZSS is a byte-for-byte copy of Huawei's captured file, deliberately.** QZSS is regional: its
 * satellites hold longitudes around 135 degrees east, which from Prague at 14 east are below the
 * horizon at every hour of every day. Generating it is possible — JAXA's `JGX0OPSULT` carries the
 * five QZSS satellites on the same anonymous mirror BeiDou comes from, and the propagator that
 * stretches BeiDou to 72 hours would stretch that too — but it would cost a second data source and
 * buy a constellation this band will never see from here. The 28 kB file is carried instead. The
 * copy is shipped even once its own window has closed: measured on the band, carrying the stale
 * pair the fix took about a minute and removing them took it back to about three (白い熊,
 * 2026-08-29). Its age is reported, never acted on.
 *
 * ## Where the carried bytes come from
 * Three things cannot be fitted from any orbit product because they are hardware calibrations:
 * the GPS and Galileo group delays and BeiDou's TGD1 (bytes 20-23 of its record). They are lifted
 * per satellite from Huawei's own captured set, which is safe precisely because it is CONSTANT —
 * every satellite carries the identical value across all 36 epochs of both captured vintages three
 * days apart. See [CapturedSet].
 */
object PredictedSet {

    const val NAME_GPS = "HW_PGNSS_GPS"
    const val NAME_GALILEO = "HW_PGNSS_GALILEO"
    const val NAME_GLONASS = "HW_PGNSS_GLONASS"
    const val NAME_BDS = "HW_PGNSS_BDS"
    const val NAME_QZS = "HW_PGNSS_QZS"
    const val NAME_EXTRA = "HW_PGNSS_EXTRA"

    /** The six files, in the order Huawei Health serves them. */
    val NAMES = listOf(NAME_GPS, NAME_BDS, NAME_GLONASS, NAME_GALILEO, NAME_QZS, NAME_EXTRA)

    /** The subdirectory of the store that keeps Huawei's own capture, seeded once. */
    const val CAPTURED_DIR = "captured"

    /** 1980-01-06 in Unix seconds, and GPS - UTC as of 2026. */
    private const val GPS_UNIX_EPOCH = 315_964_800L
    private const val LEAP = Orbit.LEAP.toLong()

    private const val WEEK = 604_800L

    /**
     * Galileo satellites whose eccentricity would reach a byte the capture never touches.
     *
     * E14 and E18 (GSAT0201/0202) sit in the wrong, highly elliptical orbits they were launched
     * into, e ~ 0.168 — and a 32-bit count of 2^-33 needs its fourth byte past e = 1/512, where all
     * 18 satellites Huawei ships stay two orders of magnitude below. Huawei simply leaves those two
     * out, and so does this: the capture is the only evidence of what the band accepts.
     */
    private const val GALILEO_E_CEILING = 1.0 / 512.0

    /** Full GPS seconds now. */
    fun nowGps(nowMillis: Long = System.currentTimeMillis()): Long =
        nowMillis / 1000L - GPS_UNIX_EPOCH + LEAP

    /** The Unix millisecond a full GPS second names. */
    fun unixMillis(gps: Long): Long = (gps - LEAP + GPS_UNIX_EPOCH) * 1000L

    /** The size a finished file must have, from the layout alone. */
    fun expectedSize(name: String, blocks: Int = Records.BLOCKS): Int = when (name) {
        NAME_GPS -> 1008 + blocks * (4 + Records.GPS_CAPACITY * Records.GPS_RECLEN)
        NAME_GALILEO -> 1008 + blocks * (4 + Records.GALILEO_CAPACITY * Records.GALILEO_RECLEN)
        NAME_BDS -> 1008 + blocks * (4 + Records.BDS_CAPACITY * Records.BDS_RECLEN)
        NAME_GLONASS -> 1008 + blocks * 8 * (4 + Records.GLONASS_CAPACITY * Records.GLONASS_RECLEN)
        NAME_EXTRA -> PgnssExtraFile.SIZE
        else -> throw IllegalArgumentException("$name has no size fixed by the layout")
    }

    // ── the entry point ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the set.
     *
     * @param workDir scratch: every downloaded input lands here and may be deleted afterwards.
     * @param outDir the store `huawei.gnss` serves from. The six files are written here, and
     *   nothing is written until all six exist.
     * @param capturedDir where Huawei's own set is kept so the un-fittable bytes can be lifted from
     *   it run after run. Seeded once from [outDir]; see [seedCaptured].
     * @param nowGpsSeconds the instant the window is anchored to — a parameter so a test can pin it.
     * @param sources the fetch, as a seam so a test can supply files instead of a network.
     * @param dispatcher where the arithmetic runs; the caller's thread is never used for it.
     * @param cancelled polled between units of work; `true` cancels the build.
     */
    suspend fun build(
        workDir: File,
        outDir: File,
        capturedDir: File = File(outDir, CAPTURED_DIR),
        config: PgnssBuildConfig = PgnssBuildConfig(),
        nowGpsSeconds: Long = nowGps(),
        sources: PgnssSourceSupplier = PgnssNetworkSources(config.wuhanIssues),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        cancelled: () -> Boolean = { false },
        progress: (PgnssProgress) -> Unit = {},
    ): PgnssBuildResult {
        val report = Reporter(progress)
        workDir.mkdirs()
        outDir.mkdirs()

        // The capture has to be readable BEFORE anything is downloaded: without it there is no
        // BeiDou group delay to write and no QZSS to carry, and finding that out after six minutes
        // of downloading and ten of arithmetic would be the most expensive possible ordering.
        seedCaptured(outDir, capturedDir)
        val captured = CapturedSet.read(capturedDir)
        report.line("captured set: ${captured.describe()}")

        // ── step 1, download ────────────────────────────────────────────────────────────────────
        val expectedFiles = 8 + config.wuhanIssues
        val fetched = AtomicInteger(0)
        val fetchedBytes = AtomicLong(0)
        report.step(PgnssStep.DOWNLOAD, "Downloading", "", 0, expectedFiles, 0.0)
        val src = withContext(Dispatchers.IO) {
            ensureRunning(cancelled)
            sources.fetch(workDir) { p ->
                // POLLED HERE, on every chunk — not once before the fetch.
                //
                // It used to be checked only on the line above, and the fetch is a single blocking
                // call lasting about six minutes, five of them one FTP transfer. So Cancel could not
                // work during a download at all: 白い熊 pressed it, nothing happened, and the run had
                // to be force-stopped — which costs the Accessibility grant on his phone
                // (2026-08-30). Throwing from here is safe because nothing is written until all six
                // files exist.
                ensureRunning(cancelled)
                if (p.complete) {
                    // Only the files the build actually reads are counted. A `.gz` and the file it
                    // unpacks into are one unit, not two, and the contract says the count must be
                    // real things finished.
                    fetchedBytes.addAndGet(p.bytesRead)
                    val units = if (p.name.endsWith(".gz")) fetched.get() else fetched.incrementAndGet()
                    report.step(
                        PgnssStep.DOWNLOAD, "Downloading", p.name,
                        min(units, expectedFiles), expectedFiles,
                        DOWNLOAD_SHARE * min(units.toDouble() / expectedFiles, 1.0),
                        bytes = fetchedBytes.get(),
                        line = "${p.name}: ${p.bytesRead} B in ${p.elapsedMillis / 1000}s",
                    )
                } else {
                    // The NAME ALONE IS NOT PROGRESS. Wuhan's FTP takes a hundred seconds a file at
                    // 18 KB/s, so a panel showing only the file name sat unchanged for minutes and
                    // read as a hang (白い熊, 2026-08-30). Carry the bytes so something moves.
                    report.step(
                        PgnssStep.DOWNLOAD, "Downloading", "${p.name}  ${megabytes(p)}",
                        min(fetched.get(), expectedFiles), expectedFiles,
                        DOWNLOAD_SHARE * min(
                            (fetched.get() + fraction(p)) / expectedFiles, 1.0,
                        ),
                        bytes = fetchedBytes.get() + p.bytesRead,
                    )
                }
            }
        }
        ensureRunning(cancelled)
        report.step(
            PgnssStep.DOWNLOAD, "Downloaded", "", expectedFiles, expectedFiles, DOWNLOAD_SHARE,
            bytes = fetchedBytes.get(), line = "all sources on disk (${fetchedBytes.get() / 1024} KB)",
        )

        // ── step 2, build ───────────────────────────────────────────────────────────────────────
        report.step(PgnssStep.BUILD, "Reading the orbit products", "", 0, 0, DOWNLOAD_SHARE)
        val plan = withContext(dispatcher) { validate(src, captured, config, nowGpsSeconds) }
        for (note in plan.notes) report.line(note)
        ensureRunning(cancelled)

        val built = LinkedHashMap<String, ByteArray>()
        val stats = ArrayList<String>()

        // GPS and Galileo: 2196 independent element sets in the shipping configuration.
        val keplerJobs = ArrayList<KeplerJob>()
        for ((system, arcs) in listOf("GPS" to plan.gps, "GALILEO" to plan.galileo)) {
            for (index in plan.stamps.indices) {
                for (sat in arcs) keplerJobs.add(KeplerJob(system, sat, index))
            }
        }

        // ONE DENOMINATOR FOR THE WHOLE OF STEP 2.
        //
        // Each sub-phase used to count its own units against its own size, so the panel showed
        // "n/36", then "n/720", then "n/6" — 白い熊, 2026-08-30: "it's ridiculous, we don't know at
        // all how many steps in total". Every one of those numbers was true and the sequence was
        // useless, because a denominator that changes is not a denominator.
        //
        // So the total is pinned HERE, before any fitting, and never moves again. It can be,
        // because everything in it is known from `plan`: the Kepler jobs are enumerated above, the
        // BeiDou satellites are the keys of its arc, and the epochs are the stamps. The BeiDou
        // element sets are counted as PLANNED rather than as scheduled — a satellite that turns out
        // not to be trustworthy across the window is dropped later, and its epochs are then
        // credited in one lump. Deciding not to fit a satellite is finished work too, and counting
        // it that way is what lets the counter actually reach its total.
        val tally = Tally(
            keplerJobs.size +
                1 +                                             // the GLONASS file
                plan.bdsArc.size +                              // BeiDou orbit integrations
                plan.bdsArc.size * plan.stamps.size +           // BeiDou element sets, planned
                1 +                                             // the almanac file
                NAMES.size,                                     // the six files written out
        )
        val fitted = runParallel(keplerJobs, config, dispatcher, cancelled) { job ->
            val arc = plan.sats.getValue(job.sat)
            val stamp = plan.stamps[job.index]
            val tow = Math.floorMod(stamp, WEEK)
            val week = Math.floorDiv(stamp, WEEK)
            var el = Orbit.fit(arc, stamp.toDouble(), tow.toDouble()).el
            var err = Orbit.checkError(el, arc, stamp.toDouble(), tow.toDouble())
            if (err > Orbit.MAX_ERROR_M) {
                el = Orbit.fit(arc, stamp.toDouble(), tow.toDouble(), half = 1800.0, samples = 13).el
                err = Orbit.checkError(el, arc, stamp.toDouble(), tow.toDouble())
            }
            val n = tally.advance()
            report.step(
                PgnssStep.BUILD, "Fitting ${job.system}", job.sat, n, tally.total,
                DOWNLOAD_SHARE + KEPLER_SHARE * tally.fractionOfKepler(keplerJobs.size),
            )
            if (err > Orbit.MAX_ERROR_M) return@runParallel FittedRecord(job, null, err)
            val clock = Orbit.clockFit(arc, stamp.toDouble())
            // 0-BASED, for every system in this format except GLONASS. Anchored against ESA's own
            // almanac, not against our decoder: Huawei's number = SVID-1 lands at 27 km median
            // against their files, = SVID at 45 120 km.
            val idx = job.sat.substring(1).toInt() - 1
            val record = if (job.system == "GPS") {
                Records.encodeGps(
                    idx, week, el, clock[0], clock[1], tow, tow, captured.gpsTgd[idx] ?: 0,
                )
            } else {
                Records.encodeGalileo(
                    idx, el, clock[0], clock[1], tow, tow, captured.galileoTgd[idx] ?: 0,
                )
            }
            FittedRecord(job, record, err)
        }
        for ((system, capacity, reclen) in listOf(
            Triple("GPS", Records.GPS_CAPACITY, Records.GPS_RECLEN),
            Triple("GALILEO", Records.GALILEO_CAPACITY, Records.GALILEO_RECLEN),
        )) {
            val mine = fitted.filter { it.job.system == system }
            val dropped = mine.count { it.record == null }
            val blocks = plan.stamps.indices.map { index ->
                mine.filter { it.job.index == index && it.record != null }
                    .sortedBy { it.job.sat }
                    .map { it.record!! }
            }
            if (blocks.any { it.isEmpty() }) {
                throw PgnssBuildException(
                    "$system: a block came out with no satellites at all — the fit is not " +
                        "converging and a block of zeros is not a prediction",
                )
            }
            val name = if (system == "GPS") NAME_GPS else NAME_GALILEO
            built[name] = Records.assemble(plan.stamps, blocks, capacity, reclen)
            val errs = mine.mapNotNull { if (it.record == null) null else it.error }.sorted()
            stats.add(
                "$system ${blocks.minOf { it.size }}-${blocks.maxOf { it.size }}/block, " +
                    "worst-case ${"%.2f".format(median(errs))} m median, " +
                    "${"%.2f".format(errs.last())} m max" +
                    if (dropped > 0) ", $dropped dropped" else "",
            )
        }
        ensureRunning(cancelled)

        // GLONASS: state vectors straight off the precise orbit, no fit anywhere in it, and an
        // hour earlier than the rest.
        report.step(
            PgnssStep.BUILD, "Writing GLONASS", "", tally.advance(), tally.total,
            DOWNLOAD_SHARE + KEPLER_SHARE,
        )
        val gloStamps = LongArray(plan.stamps.size) { plan.stamps[it] - 3600L }
        built[NAME_GLONASS] = withContext(dispatcher) {
            Records.buildGlonassFile(plan.sats, gloStamps)
        }
        stats.add("GLONASS ${plan.glonass.size} satellites")
        ensureRunning(cancelled)

        // BeiDou: the only constellation with no free product spanning the window, so its orbit is
        // made here — a dynamical fit to the observed half, integrated forward.
        val bds = buildBeiDou(plan, captured, config, dispatcher, cancelled, report, tally)
        built[NAME_BDS] = bds.file
        stats.add(bds.summary)
        ensureRunning(cancelled)

        // QZSS: carried, not made. See the class note.
        built[NAME_QZS] = captured.qzs
        stats.add("QZSS carried from the capture (${captured.qzs.size} B)")

        // EXTRA: almanacs, the GLONASS channel table and Klobuchar, from public feeds.
        report.step(
            PgnssStep.BUILD, "Building the almanac file", NAME_EXTRA,
            tally.advance(), tally.total,
            DOWNLOAD_SHARE + KEPLER_SHARE + GLONASS_SHARE + BDS_SHARE,
        )
        built[NAME_EXTRA] = withContext(dispatcher) { buildExtra(src, nowGpsSeconds, stats) }

        // ── write, once every one of the six exists ─────────────────────────────────────────────
        require(built.keys.containsAll(NAMES)) {
            "internal error: built ${built.keys} but the set is $NAMES"
        }
        val written = LinkedHashMap<String, File>()
        var bytes = 0L
        for (name in NAMES) {
            val data = built.getValue(name)
            val target = File(outDir, name)
            val temp = File(outDir, "$name.part")
            temp.writeBytes(data)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    throw IOException("could not put $name into ${outDir.absolutePath}")
                }
            }
            written[name] = target
            bytes += data.size
            report.step(
                PgnssStep.BUILD, "Writing", name, tally.advance(), tally.total, 1.0,
            )
        }

        val summary = buildString {
            append("${written.size} files, ${bytes / 1024} KB · ")
            append("${utc(plan.stamps.first())} → ${utc(plan.stamps.last())} UTC · ")
            append(stats.joinToString(" · "))
        }
        report.step(PgnssStep.BUILD, "Built", "", tally.get(), tally.total, 1.0, line = summary)
        return PgnssBuildResult(
            files = written,
            bytes = bytes,
            windowStartGps = plan.stamps.first(),
            windowEndGps = plan.stamps.last(),
            summary = summary,
            notes = plan.notes + bds.notes,
        )
    }

    // ── validation, before a single fit ─────────────────────────────────────────────────────────

    /**
     * Everything that can refuse the build, asked before any of it is spent.
     *
     * The Python learned this the hard way: it used to check BeiDou's inputs after fitting GPS and
     * Galileo, so a missing Earth-orientation file cost twenty minutes before it said so — and, in
     * the version that shipped the expired file, said so by substituting the capture.
     */
    private fun validate(
        src: PgnssSources,
        captured: CapturedSet,
        config: PgnssBuildConfig,
        nowGpsSeconds: Long,
    ): BuildPlan {
        val notes = ArrayList<String>()

        val sats = src.codeSp3.bufferedReader().use { Sp3.parse(it.lineSequence()) }
        if (sats.isEmpty()) throw PgnssBuildException("${src.codeSp3.name} carries no satellites")
        val gps = sats.keys.filter { it[0] == 'G' }.sorted()
        val glonass = sats.keys.filter { it[0] == 'R' }.sorted()
        val galileoAll = sats.keys.filter { it[0] == 'E' }.sorted()
        if (gps.isEmpty() || glonass.isEmpty() || galileoAll.isEmpty()) {
            throw PgnssBuildException(
                "${src.codeSp3.name} carries ${gps.size} GPS, ${glonass.size} GLONASS and " +
                    "${galileoAll.size} Galileo satellites — a five-day prediction has all three",
            )
        }
        notes.add("orbit product: ${gps.size} GPS / ${glonass.size} GLONASS / ${galileoAll.size} Galileo")

        val t0 = Math.floorDiv(nowGpsSeconds, Records.STEP) * Records.STEP
        val stamps = LongArray(config.blocks) { t0 + it * Records.STEP }
        val span = sats.getValue(gps.first()).t
        if (stamps.last() + 3600.0 > span[span.size - 1]) {
            throw PgnssBuildException(
                "the orbit product ends before the ${config.blocks * 2} h window does " +
                    "(product to ${utc(span[span.size - 1].toLong())} UTC, window to " +
                    "${utc(stamps.last())} UTC)",
            )
        }
        if (stamps.first() - 3600.0 < span[0]) {
            throw PgnssBuildException(
                "the window starts before the orbit product does — GLONASS is stamped an hour " +
                    "early and would extrapolate off the front of the file",
            )
        }

        // The two satellites Huawei leaves out, identified from the orbit rather than by name so a
        // third one launched into the wrong place is caught too.
        val p = DoubleArray(3)
        val v = DoubleArray(3)
        val wrongOrbit = galileoAll.filter { sat ->
            Sp3.stateAt(sats.getValue(sat), stamps[0].toDouble(), p, v)
            Orbit.seedElements(p, v, 0.0, 0.0).e >= GALILEO_E_CEILING
        }
        if (wrongOrbit.isNotEmpty()) {
            notes.add("Galileo: leaving out ${wrongOrbit.joinToString(", ")} — eccentric orbits the capture omits")
        }

        // BeiDou's inputs. Nothing here is optional and nothing falls back.
        if (src.wuhanOrbits.isEmpty()) {
            throw PgnssBuildException("no BeiDou orbit product was downloaded")
        }
        val wuhan = src.wuhanOrbits.sortedBy { it.name }
            .map { f -> f.bufferedReader().use { Sp3.parse(it.lineSequence()) } }
        val arc = Sp3.merge(wuhan, prefix = 'C', keepSeconds = 86_400.0)
        val full = Sp3.merge(wuhan, prefix = 'C')
        if (arc.isEmpty()) {
            throw PgnssBuildException(
                "the ${src.wuhanOrbits.size} multi-GNSS file(s) carry no BeiDou at all — " +
                    "BeiDou cannot be built and the captured file will NOT be substituted",
            )
        }
        val frame = EarthOrientation.read(listOf(src.codeErp))
        if (!frame.available()) {
            throw PgnssBuildException("${src.codeErp.name} parsed to no Earth-orientation rows")
        }
        val needFrom = stamps.first() - (config.bdsArcHours + 2.0) * 3600.0
        val needTo = stamps.last() + 7200.0
        if (!frame.covers(needFrom, needTo)) {
            val (lo, hi) = frame.span()!!
            throw PgnssBuildException(
                "the Earth-orientation file covers MJD ${"%.1f".format(lo)}..${"%.1f".format(hi)}, " +
                    "and the window needs ${utc(needFrom.toLong())} .. ${utc(needTo.toLong())} UTC. " +
                    "Outside its span the pole is CLAMPED, not extrapolated, so this would " +
                    "integrate against yesterday's pole and never say so",
            )
        }
        val field = Egm96.read(src.egm96, config.geopotentialDegree)
        // C20 is -J2 and is common knowledge. A silently normalised field parses perfectly and
        // makes J2 2.24 times too small, which a fit half absorbs and then reports a small residual.
        val j2 = -field.c(2, 0)
        if (j2 < 1.0e-3 || j2 > 1.2e-3) {
            throw PgnssBuildException(
                "${src.egm96.name} gives J2 = $j2, which is not the Earth's 1.0826e-3 — the " +
                    "gravity field is not the one this integrator expects",
            )
        }
        val observedHours = (arc.values.maxOf { it.t[it.size - 1] } - arc.values.minOf { it.t[0] }) / 3600.0
        notes.add(
            "BeiDou: ${arc.size} satellites, ${"%.0f".format(observedHours)} h of observed arc, " +
                "observed to ${utc(arc.values.maxOf { it.t[it.size - 1] }.toLong())} UTC",
        )
        if (observedHours < 0.75 * config.bdsArcHours) {
            notes.add(
                "BeiDou: only ${"%.0f".format(observedHours)} h of observed orbit is available and " +
                    "the dynamical fit wants ${"%.0f".format(config.bdsArcHours)} h — consecutive " +
                    "hourly issues overlap and do not lengthen the arc",
            )
        }
        if (captured.bdsTail.isEmpty()) {
            throw PgnssBuildException("the captured BeiDou file carries no group delays to lift")
        }

        return BuildPlan(
            sats = sats,
            gps = gps,
            galileo = galileoAll.filterNot { it in wrongOrbit },
            glonass = glonass,
            bdsArc = arc,
            bdsFull = full,
            erp = listOf(src.codeErp),
            field = field,
            stamps = stamps,
            notes = notes,
        )
    }

    // ── BeiDou ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Stretch a 48-hour product across the 72-hour window.
     *
     * Two orbits come out of the same files. [BuildPlan.bdsArc] is the FITTED half of each — 24
     * hours per file, observations only — and is what the dynamics are fitted to, so nothing
     * predicted by anyone else gets into the epoch state. [BuildPlan.bdsFull] is everything,
     * including the newest file's own 24-hour prediction, and is used DIRECTLY for whichever blocks
     * it covers: the 36 element sets are independent, so there is no continuity to preserve across
     * the seam, and Wuhan's own prediction is better over its span than anything integrated here.
     */
    private suspend fun buildBeiDou(
        plan: BuildPlan,
        captured: CapturedSet,
        config: PgnssBuildConfig,
        dispatcher: CoroutineDispatcher,
        cancelled: () -> Boolean,
        report: Reporter,
        tally: Tally,
    ): BeiDouResult {
        val notes = ArrayList<String>()
        val bstamps = LongArray(plan.stamps.size) { plan.stamps[it] + Records.BDS_STAMP_OFFSET }
        val gridFrom = bstamps.first() - 7200.0
        val gridTo = bstamps.last() + 7200.0
        val gridSize = ((gridTo - gridFrom) / config.bdsGridSeconds).toInt() + 1
        val grid = DoubleArray(gridSize) { gridFrom + it * config.bdsGridSeconds }

        // One satellite's dynamics, fitted and integrated. This is the expensive half of the build.
        val names = plan.bdsArc.keys.sorted()
        val tracked = AtomicInteger(0)
        val tracks = runParallel(names, config, dispatcher, cancelled) { sat ->
            val d = plan.bdsArc.getValue(sat)
            // A frame per job, though it no longer has to be.
            //
            // This was written while `EarthOrientation` still held a two-double scratch buffer that
            // `toTirs`/`toItrs` filled before reading, which one shared instance would have let two
            // threads interleave — quietly rotating a satellite by another's polar motion, at the
            // 0.2-0.3 arcsec scale that class calls 82 m over four hours. It is now immutable and
            // documents itself as shareable, so this is belt and braces; re-parsing the ERP costs a
            // millisecond against a sixteen-second fit, and per-job construction cannot be wrong.
            val frame = EarthOrientation.read(plan.erp)
            val force = ForceModel(plan.field, config.geopotentialDegree, frame = frame)
            val propagator = Propagator(force, config.integratorStepSeconds)
            val tEpoch = d.t[d.size - 1]
            val obsTimes = ArrayList<Double>()
            var t = tEpoch - config.bdsArcHours * 3600.0
            while (t <= tEpoch + 1e-6) {
                if (t >= d.t[4] && Sp3.spanned(d.t, t)) obsTimes.add(t)
                t += 900.0
            }
            val n = tracked.incrementAndGet()
            report.step(
                PgnssStep.BUILD, "Integrating BeiDou", sat, tally.advance(), tally.total,
                DOWNLOAD_SHARE + KEPLER_SHARE + GLONASS_SHARE + BDS_SHARE * TRACK_SHARE * n / names.size,
            )
            if (obsTimes.size < 40) {
                return@runParallel Track(sat, null, 0.0, "arc too short or too holed (${obsTimes.size} samples)")
            }
            val tObs = obsTimes.toDoubleArray()
            val pObs = DoubleArray(3 * tObs.size)
            val tmp = DoubleArray(3)
            val rotated = DoubleArray(3)
            for (i in tObs.indices) {
                Sp3.interpolatePosition(d, tObs[i], tmp)
                frame.toTirs(tmp, tObs[i], rotated)
                pObs[3 * i] = rotated[0]
                pObs[3 * i + 1] = rotated[1]
                pObs[3 * i + 2] = rotated[2]
            }
            val p0 = DoubleArray(3)
            val v0 = DoubleArray(3)
            Sp3.stateAt(d, tEpoch - 1.0, p0, v0)
            val rp = DoubleArray(3)
            val rv = DoubleArray(3)
            frame.toTirs(p0, tEpoch, rp)
            frame.toTirs(v0, tEpoch, rv)
            val y0 = doubleArrayOf(rp[0], rp[1], rp[2], rv[0], rv[1], rv[2])
            val fit = try {
                propagator.fitArc(tObs, pObs, tEpoch, y0, config.nsrp)
            } catch (error: Exception) {
                // A diverged dynamical fit costs one satellite, not the build.
                return@runParallel Track(sat, null, 0.0, "${error.javaClass.simpleName}: ${error.message}")
            }
            val pos = propagator.propagateOne(fit.state(), tEpoch, grid, fit.srp(), config.nsrp)
            val itrs = DoubleArray(3 * grid.size)
            val one = DoubleArray(3)
            val back = DoubleArray(3)
            for (i in grid.indices) {
                one[0] = pos[3 * i]
                one[1] = pos[3 * i + 1]
                one[2] = pos[3 * i + 2]
                frame.toItrs(one, grid[i], back)
                itrs[3 * i] = back[0]
                itrs[3 * i + 1] = back[1]
                itrs[3 * i + 2] = back[2]
            }
            val clock = Orbit.clockExtrapolate(d, grid, config.bdsClockHours)
            Track(sat, Sp3.Arc(grid.copyOf(), itrs, clock), fit.rms, null)
        }
        val failed = tracks.filter { it.arc == null }
        if (failed.isNotEmpty()) {
            notes.add("BeiDou fits that would not converge: " +
                failed.take(6).joinToString(", ") { "${it.sat} (${it.why})" })
        }
        val track = LinkedHashMap<String, Sp3.Arc>()
        val rms = HashMap<String, Double>()
        for (result in tracks.sortedBy { it.sat }) {
            val a = result.arc ?: continue
            track[result.sat] = a
            rms[result.sat] = result.rms
        }
        if (track.isEmpty()) {
            throw PgnssBuildException(
                "no BeiDou satellite converged — nothing has been substituted for it",
            )
        }
        notes.add(
            "BeiDou dynamical fit: median ${"%.3f".format(median(rms.values.sorted()))} m, " +
                "worst ${"%.3f".format(rms.values.max())} m",
        )

        // Wuhan's own numbers wherever they reach, ours beyond.
        var spliced = 0
        for ((sat, d) in track) {
            val f = plan.bdsFull[sat] ?: continue
            val lo = f.t[4]
            val hi = f.t[f.size - 5]
            val clockTimes = ArrayList<Double>()
            val clockValues = ArrayList<Double>()
            for (i in 0 until f.size) {
                if (f.clock[i].isFinite()) {
                    clockTimes.add(f.t[i])
                    clockValues.add(f.clock[i])
                }
            }
            val tmp = DoubleArray(3)
            var count = 0
            for (i in d.t.indices) {
                val t = d.t[i]
                if (t < lo || t > hi || !Sp3.spanned(f.t, t)) continue
                Sp3.interpolatePosition(f, t, tmp)
                d.p[3 * i] = tmp[0]
                d.p[3 * i + 1] = tmp[1]
                d.p[3 * i + 2] = tmp[2]
                // The clock is interpolated LINEARLY, not by the 9-point rule used on positions: an
                // orbit is smooth by construction and a clock is not, and a high-order polynomial
                // run through a steered clock rings between its samples.
                if (clockTimes.size >= 2 && t >= clockTimes.first() && t <= clockTimes.last()) {
                    d.clock[i] = linear(clockTimes, clockValues, t)
                }
                count++
            }
            spliced = max(spliced, count)
        }
        notes.add(
            "BeiDou: first ${"%.0f".format(spliced * config.bdsGridSeconds / 3600.0)} h of the " +
                "track taken from the product itself, the remaining " +
                "${"%.0f".format((grid.size - spliced) * config.bdsGridSeconds / 3600.0)} h integrated here",
        )

        val kinds = track.keys.associateWith { Orbit.bdsKind(track.getValue(it), bstamps[0].toDouble()) }
        val fresh = plan.bdsArc.values.maxOf { it.t[it.size - 1] }
        val trust = HashMap<String, Double>()
        val held = ArrayList<String>()
        for (sat in track.keys.sorted()) {
            val f = plan.bdsFull[sat]
            val productEnd = if (f == null) Double.NEGATIVE_INFINITY else f.t[f.size - 5]
            val why = when {
                // They manoeuvre. A geostationary satellite holds its slot with station-keeping
                // burns every couple of weeks, and a burn is in no dynamical model. Propagated
                // across one, C01 was 58 km out while every MEO in the same file stayed under 30 m.
                // From Prague the cost of leaving them out is close to nothing: C01, C03 and C04
                // are permanently below the horizon and C02 reaches about 7 degrees.
                kinds[sat] == "GEO" && !config.integrateBdsGeo -> "geostationary"
                (rms[sat] ?: Double.MAX_VALUE) > config.bdsMaxArcRms ->
                    "arc fit ${"%.0f".format(rms[sat])} m"
                // The product stopped carrying it, which is what an analysis centre does around a
                // manoeuvre. Whatever the reason, its dynamics are not to be trusted forward.
                plan.bdsArc.getValue(sat).let { it.t[it.size - 1] } < fresh - 7200.0 ->
                    "dropped from the newest file"
                else -> null
            }
            trust[sat] = if (why == null) Double.POSITIVE_INFINITY else productEnd
            if (why != null) held.add("$sat ($why)")
        }
        if (held.isNotEmpty()) notes.add("BeiDou not integrated past the product: ${held.joinToString(", ")}")

        val shipped = captured.bdsTail.keys.map { it + 1 }.toSet()
        val jobs = ArrayList<KeplerJob>()
        for (index in bstamps.indices) {
            for (sat in track.keys.sorted()) {
                if (sat.substring(1).toInt() !in shipped) continue
                if (bstamps[index] + 3600.0 > (trust[sat] ?: Double.NEGATIVE_INFINITY)) continue
                jobs.add(KeplerJob("BDS", sat, index))
            }
        }
        if (jobs.isEmpty()) {
            throw PgnssBuildException(
                "no BeiDou element set could be scheduled: ${track.size} satellites tracked, " +
                    "${shipped.size} carried in the capture, none trusted across the window",
            )
        }
        tally.advance(names.size * bstamps.size - jobs.size)
        val done = AtomicInteger(0)
        val records = runParallel(jobs, config, dispatcher, cancelled) { job ->
            val d = track.getValue(job.sat)
            val geo = kinds[job.sat] == "GEO"
            val propagator = Orbit.Propagator { el, t, out -> Orbit.propagateBds(el, t, geo, out) }
            val seeder = if (geo) {
                Orbit.Seeder { p, v, toe, tow ->
                    val gp = DoubleArray(3)
                    val gv = DoubleArray(3)
                    Orbit.geoFrame(p, v, gp, gv)
                    Orbit.seedElements(gp, gv, toe, tow, omegaE = Orbit.BDS_OMEGA)
                }
            } else {
                Orbit.Seeder { p, v, toe, tow ->
                    Orbit.seedElements(p, v, toe, tow, omegaE = Orbit.BDS_OMEGA)
                }
            }
            val ts = bstamps[job.index].toDouble()
            val tow = Math.floorMod(bstamps[job.index] - Orbit.BDT_OFFSET, WEEK)
            var el = Orbit.fit(
                d, ts, tow.toDouble(), propagator = propagator, seeder = seeder,
                lower = Orbit.BDS_LOWER, upper = Orbit.BDS_UPPER,
            ).el
            var err = Orbit.checkError(el, d, ts, tow.toDouble(), propagator)
            if (err > Orbit.MAX_ERROR_M) {
                el = Orbit.fit(
                    d, ts, tow.toDouble(), half = 1800.0, samples = 13,
                    propagator = propagator, seeder = seeder,
                    lower = Orbit.BDS_LOWER, upper = Orbit.BDS_UPPER,
                ).el
                err = Orbit.checkError(el, d, ts, tow.toDouble(), propagator)
            }
            val n = done.incrementAndGet()
            report.step(
                PgnssStep.BUILD, "Fitting BeiDou", job.sat, tally.advance(), tally.total,
                DOWNLOAD_SHARE + KEPLER_SHARE + GLONASS_SHARE +
                    BDS_SHARE * (TRACK_SHARE + (1.0 - TRACK_SHARE) * n / jobs.size),
            )
            if (err > Orbit.MAX_ERROR_M) return@runParallel FittedRecord(job, null, err)
            val clock = Orbit.clockFit(d, ts)
            val idx = job.sat.substring(1).toInt() - 1
            FittedRecord(
                job,
                Records.encodeBds(
                    idx, el, clock[0], clock[1], tow, tow,
                    captured.bdsTail[idx] ?: ByteArray(4),
                ),
                err,
            )
        }
        val blocks = bstamps.indices.map { index ->
            records.filter { it.job.index == index && it.record != null }
                .sortedBy { it.job.sat }
                .map { it.record!! }
        }
        if (blocks.all { it.isEmpty() }) {
            throw PgnssBuildException("every BeiDou element set was rejected — nothing is substituted")
        }
        val errs = records.mapNotNull { if (it.record == null) null else it.error }.sorted()
        val dropped = records.count { it.record == null }
        return BeiDouResult(
            file = Records.assemble(bstamps, blocks, Records.BDS_CAPACITY, Records.BDS_RECLEN),
            summary = "BeiDou ${blocks.minOf { it.size }}-${blocks.maxOf { it.size }}/block, " +
                "worst-case ${"%.2f".format(median(errs))} m median, ${"%.2f".format(errs.last())} m max" +
                if (dropped > 0) ", $dropped dropped" else "",
            notes = notes,
        )
    }

    // ── EXTRA ───────────────────────────────────────────────────────────────────────────────────

    /**
     * `HW_PGNSS_EXTRA`: the almanac, ionosphere and channel-table companion.
     *
     * Its validity is a whole week rather than 72 hours, so the epoch is the hour rather than the
     * two-hour block: `pgnss-extra-build.py` floors the clock to the hour and this does the same.
     */
    private fun buildExtra(src: PgnssSources, nowGpsSeconds: Long, stats: MutableList<String>): ByteArray {
        val epoch = Math.floorDiv(nowGpsSeconds, 3600L) * 3600L
        val reference = PgnssExtraFile.capturedReference()
        val yuma = Almanac.parseYuma(src.yuma.readText(), epoch.toDouble())
        val gssc = Almanac.parseGssc(src.galileoXml.readText())
        val agl = Almanac.parseAgl(src.glonassAgl.readText())
        if (src.brdcNav.isEmpty()) {
            throw PgnssBuildException("no broadcast navigation file — Klobuchar and the BeiDou almanac come from it")
        }
        // One file at a time: these are 1.5-8.5 MB of text and holding two decoded at once is
        // tens of megabytes of `String` on a phone for no reason.
        val first = src.brdcNav.first().readText()
        val header = Almanac.parseRinexHeader(first)
        val nav = Almanac.parseRinexBds(first)
        for (extra in src.brdcNav.drop(1)) {
            Almanac.mergeBdsNav(nav, Almanac.parseRinexBds(extra.readText()))
        }
        val bds = PgnssExtraFile.buildBds(nav, reference, epoch)
        val out = PgnssExtraFile.build(
            epoch, reference, yuma, gssc, agl, header.first, header.second, bds,
        )
        stats.add(
            "EXTRA ${yuma.size} GPS / ${gssc.size} Galileo / ${agl.size} GLONASS almanacs, " +
                "${bds.records.size} BeiDou slots" +
                if (bds.carried.isNotEmpty()) " (${bds.carried.size} carried forward)" else "",
        )
        return out
    }

    // ── the captured set ────────────────────────────────────────────────────────────────────────

    /**
     * Copy Huawei's own `HW_PGNSS_*` out of the store, once, so the un-fittable bytes survive.
     *
     * Called before the first build overwrites them. Afterwards this does nothing: the directory is
     * written once and never again, which is what stops a satellite that one run happened to drop
     * from taking its group delay with it forever. Deleting [capturedDir] re-seeds it from whatever
     * is in the store at the time, which is how a freshly staged capture is adopted.
     */
    fun seedCaptured(outDir: File, capturedDir: File) {
        if (File(capturedDir, NAME_BDS).isFile && File(capturedDir, NAME_QZS).isFile) return
        capturedDir.mkdirs()
        for (name in NAMES) {
            val from = File(outDir, name)
            val to = File(capturedDir, name)
            if (from.isFile && !to.isFile) from.copyTo(to, overwrite = false)
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────────

    /**
     * Run [work] over [items] on [dispatcher], at most `parallelism` at a time.
     *
     * The parallelism is bounded rather than unbounded because this is CPU work, not I/O: 2196
     * fits handed to an unbounded dispatcher would each get a slice of the same cores and the whole
     * set would finish at the same moment, which is exactly the wrong shape for a progress bar and
     * for a phone's thermal governor. Chunking keeps the coroutine count down to one per chunk.
     */
    private suspend fun <T, R> runParallel(
        items: List<T>,
        config: PgnssBuildConfig,
        dispatcher: CoroutineDispatcher,
        cancelled: () -> Boolean,
        work: suspend (T) -> R,
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val limited = dispatcher.limitedParallelism(config.parallelism, "pgnss")
        val chunks = items.chunked(max(1, config.fitChunk))
        return withContext(limited) {
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        val out = ArrayList<R>(chunk.size)
                        for (item in chunk) {
                            // Both halves of the cancellation contract: the engine's, which arrives
                            // as a cancelled coroutine, and the task's own `cancel_var`.
                            coroutineContext.ensureActive()
                            ensureRunning(cancelled)
                            out.add(work(item))
                        }
                        out
                    }
                }.awaitAll().flatten()
            }
        }
    }

    private fun ensureRunning(cancelled: () -> Boolean) {
        if (cancelled()) throw PgnssCancelledException()
    }

    /** `2.1 / 5.0 MB` — or just what has arrived, when the server declares no length. */
    private fun megabytes(p: FetchProgress): String {
        val got = p.bytesRead / 1_048_576.0
        return if (p.totalBytes > 0) {
            "%.1f / %.1f MB".format(got, p.totalBytes / 1_048_576.0)
        } else {
            "%.1f MB".format(got)
        }
    }

    private fun fraction(p: FetchProgress): Double =
        if (p.totalBytes > 0) min(1.0, p.bytesRead.toDouble() / p.totalBytes) else 0.0

    private fun median(sorted: List<Double>): Double = when {
        sorted.isEmpty() -> Double.NaN
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    /** `numpy.interp` without the clamping: the callers check the range first. */
    private fun linear(xs: List<Double>, ys: List<Double>, x: Double): Double {
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val span = xs[hi] - xs[lo]
        if (span == 0.0) return ys[lo]
        return ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / span
    }

    /** `yyyy-MM-dd HH:mm` UTC of a full GPS second. */
    fun utc(gps: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(unixMillis(gps)))

    // Shares of the whole run, for one 0..100 bar. Measured 2026-08-30: the download is 368 s of a
    // run whose arithmetic is a few minutes, so it really is the larger half.
    private const val DOWNLOAD_SHARE = 0.55
    private const val KEPLER_SHARE = 0.18
    private const val GLONASS_SHARE = 0.02
    private const val BDS_SHARE = 0.23
    /** How much of the BeiDou share the integration takes, against the element-set fits after it. */
    private const val TRACK_SHARE = 0.7

    private class KeplerJob(val system: String, val sat: String, val index: Int)

    private class FittedRecord(val job: KeplerJob, val record: ByteArray?, val error: Double)

    private class Track(val sat: String, val arc: Sp3.Arc?, val rms: Double, val why: String?)

    private class BeiDouResult(val file: ByteArray, val summary: String, val notes: List<String>)

    private class BuildPlan(
        val sats: Map<String, Sp3.Arc>,
        val gps: List<String>,
        val galileo: List<String>,
        val glonass: List<String>,
        val bdsArc: Map<String, Sp3.Arc>,
        val bdsFull: Map<String, Sp3.Arc>,
        val erp: List<File>,
        val field: GravityField,
        val stamps: LongArray,
        val notes: List<String>,
    )

    /**
     * One place every progress report goes through.
     *
     * Reports arrive from whichever worker finished a fit, so they are serialised here rather than
     * left to the caller: the panel's sink writes variables, and a variable store written from two
     * threads at once is a race nobody would find from a screenshot.
     */
    private class Reporter(private val sink: (PgnssProgress) -> Unit) {
        private var step = PgnssStep.DOWNLOAD
        // Seeded, not empty: [line] can be called before the first [step] — the captured set is read
        // before anything is downloaded — and a report with a blank phase blanks the panel.
        private var phase = "Starting"
        private var detail = ""
        private var done = 0
        private var total = 0
        private var fraction = 0.0
        private var bytes = 0L

        @Synchronized
        fun step(
            step: PgnssStep,
            phase: String,
            detail: String,
            done: Int,
            total: Int,
            fraction: Double,
            bytes: Long = this.bytes,
            line: String? = null,
        ) {
            this.step = step
            this.phase = phase
            this.detail = detail
            this.done = done
            this.total = total
            // A bar that goes backwards reads as a failure. Workers finish out of order, so the
            // fraction is clamped monotonic rather than trusted.
            this.fraction = max(this.fraction, min(1.0, fraction))
            this.bytes = bytes
            sink(PgnssProgress(step, phase, detail, done, total, this.fraction, bytes, line))
        }

        @Synchronized
        fun line(text: String) {
            sink(PgnssProgress(step, phase, detail, done, total, fraction, bytes, text))
        }
    }
}

/** Which of the contract's four steps a report belongs to. Steps 3 and 4 are `huawei.gnss`'s. */
/**
 * One monotone counter, and one total, for the whole of the build step.
 *
 * The total is pinned before any work starts and never changes; [advance] only ever moves forwards.
 * Both are the panel's `n/N`, and the point of the class is that there is exactly one of each — see
 * the comment where it is constructed for why a per-phase denominator was useless.
 */
internal class Tally(val total: Int) {
    private val done = java.util.concurrent.atomic.AtomicInteger(0)

    /** Credit [units] of work and return the new running total. */
    fun advance(units: Int = 1): Int = done.addAndGet(units).coerceAtMost(total)

    fun get(): Int = done.get().coerceAtMost(total)

    /**
     * How far through the KEPLER fits we are, for the progress bar only.
     *
     * The bar still weights the phases by their measured share of the runtime, and the fits are no
     * longer counted against their own size — so it is recovered here rather than inferred from the
     * global count, which advances through phases the bar weights differently.
     */
    fun fractionOfKepler(keplerJobs: Int): Double =
        if (keplerJobs <= 0) 1.0 else (get().toDouble() / keplerJobs).coerceAtMost(1.0)
}

enum class PgnssStep { DOWNLOAD, BUILD }

/**
 * One progress report.
 *
 * [done] and [total] are REAL units — files on disk during the download, element sets during the
 * fit — never a percentage dressed up as a count, because the panel prints them as `3/12`.
 */
class PgnssProgress(
    val step: PgnssStep,
    val phase: String,
    val detail: String,
    val done: Int,
    val total: Int,
    /** 0..1 across the whole run, monotonic. */
    val fraction: Double,
    val bytes: Long,
    /** A line worth keeping in the log, or null for a routine tick. */
    val line: String?,
)

/** What a finished build produced. */
class PgnssBuildResult(
    val files: Map<String, File>,
    val bytes: Long,
    val windowStartGps: Long,
    val windowEndGps: Long,
    val summary: String,
    val notes: List<String>,
)

/** Tunables. The defaults are the shipping configuration and the measured ones. */
class PgnssBuildConfig(
    /** 36 blocks 7200 s apart: 72 hours. Lowered only by tests. */
    val blocks: Int = Records.BLOCKS,
    /**
     * Hours of orbit product fitted before the integration epoch. Montenbruck et al. recommend two
     * days and measure the optimum for a multi-GNSS ultra-rapid solution at 42-45 h.
     */
    val bdsArcHours: Double = 48.0,
    /** Seconds between sampled positions of the integrated BeiDou track — an SP3 cadence. */
    val bdsGridSeconds: Double = 300.0,
    /** Hours of clock history fitted for the linear extrapolation. */
    val bdsClockHours: Double = 36.0,
    /**
     * A satellite whose dynamics could not be fitted to this is not integrated past the product.
     *
     * This screen exists because the check after it CANNOT catch a diverged track: the Kepler fit
     * is graded against the very trajectory the integration produced, so a satellite integrated
     * into the wrong orbit fits its own wrong orbit beautifully and ships. The arc residual is the
     * only number in the BeiDou path measured against somebody else's orbit.
     */
    val bdsMaxArcRms: Double = 50.0,
    /** The four geostationary BeiDou satellites are not integrated past the product. */
    val integrateBdsGeo: Boolean = false,
    val geopotentialDegree: Int = PgnssConstants.NMAX_DEFAULT,
    val nsrp: Int = Propagator.NSRP_DEFAULT,
    val integratorStepSeconds: Double = Propagator.DEFAULT_STEP,
    /** How many Wuhan issues to take. Three, spaced a day apart, covers the arc. */
    val wuhanIssues: Int = 3,
    /**
     * Cores minus two.
     *
     * Not all of them: this runs while the phone is being used, and the two left over are what
     * keeps the panel it is reporting to drawing at all. On the Mate XT's eight cores that is six.
     */
    val parallelism: Int = pgnssDefaultParallelism(),
    /** Fits per coroutine. Small enough to keep the cores fed, large enough not to pay per fit. */
    val fitChunk: Int = 8,
) {
    init {
        require(blocks in 1..84) { "the 1008-byte header holds 84 blocks at most" }
        require(parallelism >= 1) { "parallelism must be at least 1" }
    }

}

/**
 * Cores minus two — the default the action uses.
 *
 * A top-level function rather than a companion member because a constructor default argument is
 * evaluated before the companion is guaranteed initialised.
 */
fun pgnssDefaultParallelism(): Int = max(1, Runtime.getRuntime().availableProcessors() - 2)

/** The build refused to run, or refused to finish. Never a substitution, always a message. */
class PgnssBuildException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The task asked for the build to stop. */
class PgnssCancelledException : IOException("the build was cancelled")

/** Where the inputs come from, as a seam so a test can supply files instead of a network. */
fun interface PgnssSourceSupplier {
    suspend fun fetch(workDir: File, onProgress: (FetchProgress) -> Unit): PgnssSources
}

/** The real one: [PgnssFetcher], which always downloads. */
class PgnssNetworkSources(private val wuhanIssues: Int = 3) : PgnssSourceSupplier {
    override suspend fun fetch(workDir: File, onProgress: (FetchProgress) -> Unit): PgnssSources =
        PgnssFetcher(workDir, progress = onProgress).fetchAll(wuhanIssues = wuhanIssues)
}

/**
 * The bytes no orbit product carries, lifted from Huawei's own captured set.
 *
 * TGD is the delay between a satellite's two carriers — a hardware calibration, not an orbital
 * quantity — so it is in no product and cannot be fitted. Lifting it is safe because it is
 * CONSTANT: across all 36 epochs of the capture every satellite carries the identical value, 29 of
 * 29 for GPS and 18 of 18 for Galileo, and BeiDou's is identical across both captured vintages
 * three days apart. That measurement is the licence; without it this would be a hope.
 *
 * A satellite the capture does not carry gets zero, which is exactly where it was before.
 */
class CapturedSet(
    /** 0-based index -> signed byte at offset 54 of a GPS record. */
    val gpsTgd: Map<Int, Int>,
    /** 0-based index -> signed 16-bit BGD at offset 20 of a Galileo record. */
    val galileoTgd: Map<Int, Int>,
    /** 0-based index -> bytes 20..23 of a BeiDou record, verbatim. */
    val bdsTail: Map<Int, ByteArray>,
    /** `HW_PGNSS_QZS`, carried whole. */
    val qzs: ByteArray,
) {
    fun describe(): String =
        "${gpsTgd.size} GPS and ${galileoTgd.size} Galileo group delays, " +
            "${bdsTail.size} BeiDou tails, QZSS ${qzs.size} B"

    companion object {
        /**
         * Read the capture, or refuse the build.
         *
         * There is deliberately no tolerant path here. A build with no BeiDou tail writes zeros
         * into a field Huawei fills for every satellite, and a build with no QZSS silently ships
         * five files where the band expects six — both of which would look like success.
         */
        fun read(dir: File): CapturedSet {
            val missing = ArrayList<String>()
            val bds = File(dir, PredictedSet.NAME_BDS)
            val qzs = File(dir, PredictedSet.NAME_QZS)
            if (!bds.isFile) missing.add(PredictedSet.NAME_BDS)
            if (!qzs.isFile) missing.add(PredictedSet.NAME_QZS)
            if (missing.isNotEmpty()) {
                throw PgnssBuildException(
                    "no captured ${missing.joinToString(" or ")} in ${dir.absolutePath}. " +
                        "BeiDou's group delay and the QZSS file cannot be derived from anything " +
                        "public, so they are lifted from Huawei's own set — stage it into the " +
                        "store first. Nothing is being substituted for them.",
                )
            }
            val tails = LinkedHashMap<Int, ByteArray>()
            forEachRecord(bds.readBytes(), Records.BDS_RECLEN) { record ->
                tails[u16(record, 0)] = record.copyOfRange(20, 24)
            }
            if (tails.isEmpty()) {
                throw PgnssBuildException("${bds.absolutePath} holds no BeiDou records to lift")
            }
            val gpsTgd = LinkedHashMap<Int, Int>()
            File(dir, PredictedSet.NAME_GPS).takeIf { it.isFile }?.let { f ->
                forEachRecord(f.readBytes(), Records.GPS_RECLEN) { record ->
                    gpsTgd[u16(record, 0)] = record[54].toInt()
                }
            }
            val galTgd = LinkedHashMap<Int, Int>()
            File(dir, PredictedSet.NAME_GALILEO).takeIf { it.isFile }?.let { f ->
                forEachRecord(f.readBytes(), Records.GALILEO_RECLEN) { record ->
                    galTgd[u32(record, 0)] = s16(record, 20)
                }
            }
            return CapturedSet(gpsTgd, galTgd, tails, qzs.readBytes())
        }

        /** Walk every record of every block a `HW_PGNSS_*` header describes. */
        private inline fun forEachRecord(bytes: ByteArray, reclen: Int, body: (ByteArray) -> Unit) {
            if (bytes.size < 1008) return
            for (i in 0 until Records.BLOCKS) {
                val offset = u32(bytes, 12 * i + 4)
                if (offset < 1008 || offset + 4 > bytes.size) continue
                val count = u32(bytes, offset)
                for (k in 0 until count) {
                    val from = offset + 4 + k * reclen
                    if (from + reclen > bytes.size) break
                    body(bytes.copyOfRange(from, from + reclen))
                }
            }
        }

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun s16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

        private fun u32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
