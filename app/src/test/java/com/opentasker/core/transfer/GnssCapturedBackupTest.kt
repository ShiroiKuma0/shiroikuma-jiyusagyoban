package com.opentasker.core.transfer

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Huawei's captured satellite reference travels in the backup, and can travel ALONE.
 *
 * ## What this is guarding
 *
 * Two things in a predicted-ephemeris set cannot be derived from any public product: BeiDou's
 * per-satellite group delays and the whole QZSS file. They are lifted once from a capture of
 * Huawei's own set and kept in the app's private storage, and a phone without that capture cannot
 * build a set at all — it refuses rather than substituting.
 *
 * They were in no export category. 白い熊's second phone came up on 2026-09-09 unable to generate,
 * and the only remedy was staging files by hand from a folder outside the app — *"these are our
 * internal files … they should live in the app's data and be parts of backup/restore"* (白い熊).
 *
 * Two failures are possible and this covers both: forgetting the files on either side of the
 * archive, and — the subtler one — an archive that carries the files WITHOUT the preferences JSON
 * restoring nothing at all, which is exactly the archive that seeds a phone.
 */
class GnssCapturedBackupTest {

    private val backup = ProductionSources.read("com/opentasker/core/transfer/SettingsBackup.kt")

    /**
     * The path in the exporter is the path the satellite build actually writes.
     *
     * `SettingsBackup` spells it out rather than importing it, deliberately — the alternative drags
     * an Android-side object into the transfer layer. This is the price of that: the two spellings
     * are compared here, so a renamed folder is a red test rather than an export of nothing.
     */
    @Test
    fun `the exported directory is the one the satellite build writes`() {
        val action = ProductionSources.read("com/opentasker/core/actions/HuaweiPgnssAction.kt")
        val dir = Regex("""DEFAULT_DIR = "([^"]+)"""").find(action)?.groupValues?.get(1)
        assertEquals("gnss", dir)
        assertTrue(
            "SettingsBackup must point at user_files/$dir",
            backup.contains("""File(File(context.filesDir, "user_files"), "$dir")"""),
        )
    }

    /**
     * The excluded names ARE the generated set, and the day one is renamed this must go red.
     *
     * The category carries the whole store now, so the exclusion list is the only thing standing
     * between a backup and a stale 72-hour set being handed to a restored phone. A name that drifts
     * out of it would be carried silently, which is precisely the failure the satellite build
     * refuses to commit in every other place it could.
     */
    @Test
    fun `the excluded names are the generated set`() {
        val set = ProductionSources.read("com/opentasker/core/huawei/pgnss/PredictedSet.kt")
        val names = Regex("""NAME_[A-Z]+ = "([^"]+)"""").findAll(set).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "HW_PGNSS_GPS", "HW_PGNSS_GALILEO", "HW_PGNSS_GLONASS",
                "HW_PGNSS_BDS", "HW_PGNSS_QZS", "HW_PGNSS_EXTRA",
            ),
            names,
        )
        val excluded = ProductionSources.block(
            "com/opentasker/core/transfer/SettingsBackup.kt",
            "private val EXPIRING = setOf(",
            ")",
        )
        for (name in names) {
            assertTrue("the generated $name would be carried into a backup", excluded.contains("\"$name\""))
        }
        assertTrue(
            "the downloaded broadcast ephemeris expires too",
            excluded.contains("\"HW_AGNSS_RTCM_33\""),
        )
        // And the capture must survive, though it shares those names one directory down.
        assertTrue(
            "the exclusion must be relative-path based, not name based",
            backup.contains("relative -> relative in EXPIRING"),
        )
    }

    /** The archive keeps the store's shape, so `captured/` is not flattened onto the store. */
    @Test
    fun `the tree is preserved on both sides`() {
        assertTrue(
            "the export must recurse",
            backup.contains("exportDirFiles(zip, file, entryDir, skip, relative)"),
        )
        assertTrue(
            "the import must keep the entry's relative path",
            backup.contains("val relative = name.removePrefix("),
        )
        assertTrue(
            "and refuse one that escapes the destination",
            backup.contains("!target.path.startsWith(root.path + File.separator)"),
        )
    }

    /** Both halves of the archive loop reach the files through the same two helpers. */
    @Test
    fun `export and import both carry the captured set`() {
        assertTrue(
            "archiveDirOf must map 健康 to the satellite store",
            Regex("""Cat\.HEALTH -> GNSS_DIR""").containsMatchIn(backup),
        )
        assertTrue(
            "filesDirOf must map 健康 to the on-disk store",
            Regex("""Cat\.HEALTH -> gnssDir\(context\)""").containsMatchIn(backup),
        )
        val export = ProductionSources.block(
            "com/opentasker/core/transfer/SettingsBackup.kt",
            "Cat.HEALTH_DATA -> exportTables(zip, db, isCancelled)",
            "private fun writeEntry(",
        )
        assertTrue(
            "the export loop no longer writes a category's files",
            export.contains("exportDirFiles(zip, dir, entryDir, skipInDir(cat))"),
        )
        val import = ProductionSources.block(
            "com/opentasker/core/transfer/SettingsBackup.kt",
            "suspend fun import(",
            "private fun importDirFiles(",
        )
        assertTrue("the import loop no longer restores a category's files", import.contains("importDirFiles(entries, entryDir, dir)"))
    }

    /**
     * The seeding archive: the captured files and no preferences.
     *
     * This is the shape that has to work, because it is the one a phone with its own live data can
     * accept — it carries nothing that could overwrite anything. `import` used to read
     * `<category>.json` first and `continue` when it was absent, so this archive restored silently
     * nothing; the files are read first now, and this is what says so.
     */
    @Test
    fun `an archive of only the captured files still declares 健康`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("gnss/captured/HW_PGNSS_BDS"))
            out.write(ByteArray(32) { 0x5A })
            out.closeEntry()
        }
        val cats = SettingsBackup.categoriesIn(zip.toByteArray())
        assertTrue("an archive carrying only the captured set must restore as 健康", SettingsBackup.Cat.HEALTH in cats)
        assertEquals("and as nothing else", setOf(SettingsBackup.Cat.HEALTH), cats)
    }

    /** And the ordinary archive is unaffected — a preferences JSON alone still declares its category. */
    @Test
    fun `a preferences-only archive is unchanged`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("health.json"))
            out.write("{}".toByteArray())
            out.closeEntry()
        }
        assertEquals(setOf(SettingsBackup.Cat.HEALTH), SettingsBackup.categoriesIn(zip.toByteArray()))
    }

    /**
     * The way in: `file.move` must resolve each side against its own base.
     *
     * A capture staged in `/sdcard/tmp` reaches the app's private files through this and nothing
     * else — `file.read`/`file.write` carry UTF-8 text and would shred 240 kB of binary. Reverting
     * either side to the sandbox-only resolver makes that impossible again.
     */
    @Test
    fun `file move resolves both sides`() {
        val move = ProductionSources.block(
            "com/opentasker/core/actions/FileActions.kt",
            "class MoveFileAction",
            "class MakeDirectoryAction",
        )
        assertFalse("a move side is back on the sandbox-only resolver", move.contains("safeUserFile"))
        assertTrue(move.contains("""sharedArg(args, "from_shared")"""))
        assertTrue(move.contains("""sharedArg(args, "to_shared")"""))
        val meta = ProductionSources.block(
            "com/opentasker/core/actions/ActionMetadata.kt",
            """id = "file.move"""",
            """id = "file.mkdir"""",
        )
        for (field in listOf("shared", "from_shared", "to_shared")) {
            assertTrue("file.move's editor form is missing $field", meta.contains("""ActionField("$field","""))
        }
    }
}


/**
 * The disabled flags survive a round trip through the bundle, and an old archive still imports.
 *
 * A switch that is lost on export is worse than no switch: the task comes back looking normal and
 * runs the next time something calls it, which is precisely the surprise the flag exists to avoid.
 */
class DisabledFlagBundleTest {

    @Test
    fun `both flags survive encode and decode`() {
        val bundle = com.opentasker.core.transfer.OpenTaskerBundleCodec.decode(
            com.opentasker.core.transfer.OpenTaskerBundleCodec.encode(
                com.opentasker.core.transfer.OpenTaskerBundle(
                    appVersion = "test",
                    exportedAtEpochMs = 0L,
                    tasks = listOf(
                        com.opentasker.core.model.Task(
                            name = "off task",
                            enabled = false,
                            actions = listOf(
                                com.opentasker.core.model.ActionSpec(type = "flash", enabled = false),
                                com.opentasker.core.model.ActionSpec(type = "flash"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val task = bundle.tasks.single()
        assertFalse("the task's own switch was lost in the archive", task.enabled)
        assertFalse("the action's switch was lost in the archive", task.actions[0].enabled)
        assertTrue("an untouched action must come back on", task.actions[1].enabled)
    }

    /** An archive written before the flags existed imports as everything ON. */
    @Test
    fun `an older archive imports as enabled`() {
        val legacy = """
            {"schemaVersion":5,"appVersion":"old","exportedAtEpochMs":0,
             "tasks":[{"name":"t","actions":[{"type":"flash","args":{}}]}]}
        """.trimIndent()
        val task = com.opentasker.core.transfer.OpenTaskerBundleCodec.decode(legacy).tasks.single()
        assertTrue(task.enabled)
        assertTrue(task.actions.single().enabled)
    }
}
