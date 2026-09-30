package com.opentasker.ui.screens

import com.opentasker.core.diagnostics.RunLogExportFormat
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timestamps on screen follow the device's conventions (A-363), but export filenames must not: a
 * filename has to sort in time order and read the same on every phone. This pins the fixed pattern
 * so the two kinds of timestamp cannot be conflated again, including under a default locale whose
 * digits and field order differ from US English.
 */
class ExportFileNameFormatTest {
    @Test
    fun exportFileNamesKeepTheirSortableAsciiTimestampInAnyLocale() {
        val original = Locale.getDefault()
        try {
            for (tag in listOf("en-US", "ar-EG", "fa-IR", "de-DE")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val stamp = """\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}"""
                val backup = databaseBackupExportName()
                val bundle = openTaskerBundleExportName()
                val runLog = runLogExportName(RunLogExportFormat.CSV)
                assertTrue("$tag: $backup", Regex("opentasker_backup_$stamp\\.db").matches(backup))
                assertTrue("$tag: $bundle", Regex("opentasker_bundle_$stamp\\.json").matches(bundle))
                assertTrue("$tag: $runLog", Regex("opentasker_run_log_$stamp\\.csv").matches(runLog))
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
