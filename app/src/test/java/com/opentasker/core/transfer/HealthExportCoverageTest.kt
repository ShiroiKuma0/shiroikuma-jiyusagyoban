package com.opentasker.core.transfer

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.io.path.readText

/**
 * EVERY store 健康 writes to must be in the export. This is the gate that keeps it true.
 *
 * 白い熊, 2026-09-03: *"All our data must be exported — this is the key."* The gap that prompted it
 * was not a bug in the exporter; it was six preferences files and seven tables that nobody had ever
 * told the exporter about. A "clear app data" therefore took every morning rating, every note, every
 * 機能訓練 tick and every marked session with it — the authored record, the one part no device can
 * re-supply.
 *
 * A list maintained by hand is exactly what failed, so this test does not hold a second list. It
 * READS the sources: every preferences file the health code opens, and every band table the database
 * declares, must appear in `SettingsBackup`. Add a store and forget the export, and this fails with
 * its name in the message.
 */
class HealthExportCoverageTest {

    private val backup = ProductionSources.read("com/opentasker/core/transfer/SettingsBackup.kt")

    /** Source files that own a store 健康 authors or needs. */
    private val healthSources = listOf(
        "com/opentasker/core/band/RecoveryLog.kt",
        "com/opentasker/core/band/DayNotes.kt",
        "com/opentasker/core/band/RehabLog.kt",
        "com/opentasker/core/band/TrainingSessions.kt",
        "com/opentasker/core/huawei/HuaweiSettings.kt",
    )

    /**
     * Every preferences file those sources name is listed in the export.
     *
     * Matched on the string literals themselves rather than on a call shape, because the stores
     * spell it several ways — a `const val PREFS`, a constructor argument, an inline literal — and a
     * gate that only understood one of them would pass while missing the others.
     */
    @Test
    fun `every health preferences file is exported`() {
        val declared = Regex("\"([a-z][a-z0-9_]{3,})\"")
        val missing = mutableListOf<String>()
        for (source in healthSources) {
            val text = ProductionSources.read(source)
            // Only the literals that are actually used as a preferences name.
            val names = declared.findAll(text).map { it.groupValues[1] }.filter { name ->
                text.contains("PREFS = \"$name\"") ||
                    text.contains("DayNotes(\"$name\")") ||
                    text.contains("getSharedPreferences(\"$name\"")
            }.toSet()
            for (name in names) {
                if (!backup.contains("\"$name\"")) missing += "$name (from $source)"
            }
        }
        assertTrue(
            "health stores missing from SettingsBackup.PREF_FILES: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The measurement dump names EVERY band table, and names NOTHING ELSE.
     *
     * Both directions, and the second is the one that matters: the list was first written from the
     * entity CLASS names, so `BandSyncEntity` became `band_sync` when the table is `band_syncs`.
     * The first export 白い熊 ran died on `no such table: band_sync` and took the whole backup with
     * it — workspace, theme, widgets, everything. The truth is the `tableName` in the entity's own
     * annotation, so that is what this reads.
     */
    @Test
    fun `the measurement dump names every band table and no others`() {
        val declared = listOf("BandDao.kt", "HuaweiDao.kt").flatMap { file ->
            Regex("tableName\\s*=\\s*\"([a-z_]+)\"")
                .findAll(ProductionSources.read("com/opentasker/core/storage/$file"))
                .map { it.groupValues[1] }
                .toList()
        }.toSet()
        assertEquals("expected seven band tables in the schema", 7, declared.size)

        val listed = Regex("HEALTH_TABLES = listOf\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(backup)!!
            .groupValues[1]
            .let { Regex("\"([a-z_]+)\"").findAll(it).map { m -> m.groupValues[1] }.toSet() }

        assertEquals(
            "tables in the schema but not in the dump: ${declared - listed}; " +
                "names in the dump that are not tables: ${listed - declared}",
            declared,
            listed,
        )
    }

    /**
     * A measurement row is ONE line. This is the whole of NDJSON's contract.
     *
     * The first archive exported was written with the settings encoder, which pretty-prints — so
     * every "line" was a fragment, every parse failed, and the reader skipped what it could not
     * read. The export looked perfect and the restore would have been empty. Nothing else in the
     * pipeline notices a newline here, so this is where it is caught.
     */
    @Test
    fun `a measurement row never contains a newline`() {
        val row = buildJsonObject {
            put("metric", JsonPrimitive("hr"))
            put("epochSeconds", JsonPrimitive(1_787_349_300L))
            put("value", JsonPrimitive(58.5))
            put("note", JsonPrimitive("a value with \"quotes\" and, commas"))
        }
        val line = SettingsBackup.ndjsonLine(row)
        assertEquals("a row must occupy exactly one line", -1, line.indexOf('\n'))
        assertTrue("and still be a whole object", line.startsWith("{") && line.endsWith("}"))
    }

    /**
     * The two categories exist and are on by default.
     *
     * `defaultSelected = false` is reserved for what is "large, derived AND re-creatable". The
     * measurements fail the third test — a band holds days in its ring buffer, not months — so
     * neither category may quietly ship unticked.
     */
    @Test
    fun `both health categories are selected by default`() {
        assertTrue("no HEALTH category", backup.contains("HEALTH(\"health\""))
        assertTrue("no HEALTH_DATA category", backup.contains("HEALTH_DATA(\"health_data\""))
        val optedOut = Regex("HEALTH[A-Z_]*\\([^)]*defaultSelected\\s*=\\s*false").findAll(backup).count()
        assertEquals("a health category must not ship unticked", 0, optedOut)
    }
}
