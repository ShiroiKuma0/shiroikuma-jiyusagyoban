package com.opentasker.core.huawei.pgnss

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The build must not be able to report success over a store it never rewrote.
 *
 * On 2026-09-06 白い熊's band was still being served a predicted set built on **2026-09-02**, whose
 * 72-hour window had expired 37 hours earlier — through repeated runs whose panel read "Download
 * done, Building the set done". Two consecutive runs produced byte-identical files, the broadcast
 * file included, while the run's own log showed the orbit products downloading correctly.
 *
 * The shape of the fault is in the write path: all six files are assembled in memory and written at
 * the END, so anything that throws before that loop leaves the PREVIOUS set on disk and writes
 * nothing — and the only thing that said so was a variable nobody reads.
 *
 * These are source gates rather than behaviour tests because reproducing the fault needs a real
 * five-day orbit product, a phone-sized fit and several minutes; what has to hold is a property of
 * the code, and it is cheap to state directly.
 */
class PgnssStoreWriteTest {

    @Test
    fun `the build reads its own output back before calling it built`() {
        val body = ProductionSources.block(
            "com/opentasker/core/huawei/pgnss/PredictedSet.kt",
            "val written = LinkedHashMap<String, File>()",
            "return PgnssBuildResult(",
        )
        assertTrue(
            "the write loop must re-read the files it wrote",
            body.contains("reads back stamped"),
        )
        assertTrue(
            "and compare them against the window this run planned",
            body.contains("plan.stamps.first()") && body.contains("throw IOException("),
        )
    }

    @Test
    fun `every exit records whether the set was rebuilt`() {
        val src = ProductionSources.read("com/opentasker/core/actions/HuaweiPgnssAction.kt")
        // Success writes the summary; both failure paths write why not. A panel that goes green
        // while the store keeps a four-day-old set is the exact thing being prevented.
        assertTrue("success must record the result", src.contains("PgnssResult\", result.summary"))
        assertTrue(
            "a cancel must say the set was NOT rebuilt",
            src.contains("NOT REBUILT — cancelled at step"),
        )
        assertTrue(
            "and so must any other failure, carrying its reason",
            src.contains("NOT REBUILT — \$why"),
        )
    }

    @Test
    fun `a copied set carries the window it was built for, and nothing is copied unasked`() {
        val src = ProductionSources.read("com/opentasker/core/actions/HuaweiPgnssAction.kt")
        // The copy runs even on a cancelled run, so a folder of files proves nothing on its own —
        // it looked identical for a set four days dead and one built minutes ago.
        assertTrue("the copy must write a note beside the bytes", src.contains("built.txt"))
        assertTrue("naming the window the files carry", src.contains("window starts"))
        // And it is OFF unless a folder is given. It was defaulted on for exactly one evening, to
        // make a broken set gradeable; diagnostic machinery that outlives its diagnosis is litter
        // in the one folder 白い熊 actually looks at (白い熊, 2026-09-07).
        assertFalse(
            "no default destination — an empty argument must mean do not copy",
            src.contains("DEFAULT_COPY_TO"),
        )
    }

    @Test
    fun `a broadcast file with no ionosphere block does not stop the build`() {
        // The nav files are read in `validate` now, not in `buildExtra` — which is the same fix
        // told twice: the refusal below used to fire AFTER the ten minutes of fitting.
        val body = ProductionSources.block(
            "com/opentasker/core/huawei/pgnss/PredictedSet.kt",
            "private fun readBroadcastNav(",
            "// ── BeiDou ",
        )
        // The header used to be read from brdcNav.first() alone. Today's BRDC is still being
        // written while this runs, so a partial one has no IONOSPHERIC CORR block, the parse threw,
        // and the build died BEFORE its write loop — leaving the previous set on disk and the panel
        // reading "Build done". Four days of 白い熊's walks were served a set from 2026-09-02.
        assertTrue(
            "the header must be searched for across every downloaded day",
            body.contains("for ((file, _) in usable)") && body.contains("if (header == null)"),
        )
        assertTrue(
            "a missing block in one file must not throw out of the loop",
            body.contains("runCatching { Almanac.parseRinexHeader(text) }"),
        )
        assertTrue(
            "and only a total absence is fatal, naming the files",
            body.contains("none of the") && body.contains("ionospheric correction"),
        )
        assertTrue(
            "using a fallback day is worth saying out loud",
            body.contains("today's broadcast file carries none yet"),
        )
    }

    @Test
    fun `the broadcast navigation fetch tries more than one product`() {
        val body = ProductionSources.block(
            "com/opentasker/core/huawei/pgnss/Fetch.kt",
            "fun fetchBrdcNav(",
            "// \u2500\u2500 transport",
        )
        // BRDC00WRD_R stopped carrying IONOSPHERIC CORR entirely — its header is one LEAP SECONDS
        // line — so the only source of GPS Klobuchar disappeared and the build died on it for four
        // days. BRDC00IGS_R has GPSA, GPSB and GPUT; it is a daily published after the day closes,
        // so today 404s and today falls back. A single hard-coded product is the fault itself.
        assertTrue("it must walk a list of products", body.contains("for (product in BRDC_PRODUCTS)"))
        assertTrue(
            "a 404 for one product must not end the attempt",
            body.contains("catch (e: IOException)"),
        )
        val src = ProductionSources.read("com/opentasker/core/huawei/pgnss/Fetch.kt")
        assertTrue(
            "IGS first, because it is the one with the ionosphere",
            src.contains("""listOf("BRDC00IGS_R", "BRDC00WRD_R")"""),
        )
    }
}
