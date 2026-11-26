package com.sysadmindoc.ruledeck.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #30: whole-app backups went over the token budget, and a password-protected export was
 * scanned as JSON. Now it's named for what it is, so the user can export again without one.
 */
class MacroDroidLargeImportTest {
    @Test
    fun aBackupOverTheDefaultTokenBudgetImportsAndItsBundleStillValidates() {
        val raw = backup(macroCount = 400, filler = 1_000)
        try {
            ImportResourceGuard.requireJsonPreflight(raw)
            fail("The fixture has to be over the default budget to prove anything")
        } catch (expected: ImportBudgetExceededException) {
            assertEquals("JSON tokens", expected.budgetName)
        }

        val report = MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0)

        assertEquals(400, report.sourceMacroCount)
        // The write path validates against the default budget, so a preview that passes has to
        // be one that can actually be confirmed.
        RuleDeckBundleCodec.validate(MacroDroidImportPlanner.confirmedBundle(report))
    }

    @Test
    fun deepNestingInsideABackupIsNoLongerAnObstacle() {
        val nested = "[".repeat(100) + "]".repeat(100)
        val raw = """{"macroList":[{"m_name":"Deep","m_GUID":1,"extra":$nested,"m_actionList":[],"m_triggerList":[]}]}"""

        assertEquals(1, MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0).sourceMacroCount)
    }

    @Test
    fun theSourceBudgetIsStillABudget() {
        val nested = "[".repeat(300) + "]".repeat(300)
        val raw = """{"macroList":[{"m_name":"Deep","extra":$nested}]}"""

        assertBudget("nesting depth") { MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0) }
    }

    @Test
    fun whatComesOutOfABackupKeepsTheDefaultEntityLimit() {
        val raw = backup(macroCount = ImportResourceBudget.Default.maxEntities.toInt() / 2 + 1, filler = 0)

        assertBudget("entities") { MacroDroidImporter.parse(rawJson = raw, appVersion = "test", importedAtEpochMs = 0) }
    }

    @Test
    fun anythingButAJsonObjectIsRejectedAsTheWrongFileNotAsTooBig() {
        val garbage = "[".repeat(500)
        try {
            MacroDroidImporter.parse(rawJson = garbage, appVersion = "test", importedAtEpochMs = 0)
            fail("Expected a rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("MacroDroid import root must be a JSON object.", error.message)
        }
    }

    @Test
    fun classifiesByContentBecauseAProtectedExportCanOpenWithAnyByte() {
        fun classify(bytes: ByteArray) = MacroDroidDocument.classify(bytes)

        assertEquals(MacroDroidDocument.Text(PLAIN_EXPORT), classify(PLAIN_EXPORT.toByteArray()))
        assertEquals(MacroDroidDocument.PasswordProtected, classify(BINARY_BLOCKS))

        val opensLikeJson = BINARY_BLOCKS.copyOf().also { it[0] = '{'.code.toByte() }
        assertEquals(MacroDroidDocument.PasswordProtected, classify(opensLikeJson))

        val notBlocks = BINARY_BLOCKS.copyOf(BINARY_BLOCKS.size - 1)
        assertEquals(MacroDroidDocument.Unrecognized, classify(notBlocks))
        assertEquals(MacroDroidDocument.Unrecognized, classify("<root/>".toByteArray()))
    }

    @Test
    fun textInAnotherEncodingStillReachesTheParser() {
        val latin1 = """{"macroList":[{"m_name":"café"}]}""".toByteArray(Charsets.ISO_8859_1)
        assertTrue("A whole number of blocks would read as a protected export", latin1.size % 16 != 0)

        assertTrue(MacroDroidDocument.classify(latin1) is MacroDroidDocument.Text)
    }

    private fun backup(macroCount: Int, filler: Int): String = buildString {
        append("""{"macroList":[""")
        repeat(macroCount) { index ->
            if (index > 0) append(',')
            append("""{"m_name":"Macro $index","m_GUID":${index + 1},"m_actionList":[],"m_triggerList":[],"padding":[""")
            append((0 until filler).joinToString(","))
            append("]}")
        }
        append("]}")
    }

    private fun assertBudget(name: String, block: () -> Unit) {
        try {
            block()
            fail("Expected the $name budget to refuse this")
        } catch (error: ImportBudgetExceededException) {
            assertEquals(name, error.budgetName)
        }
    }

    private companion object {
        const val PLAIN_EXPORT =
            """{"macroList":[{"m_name":"Plain macro","m_GUID":1,"m_actionList":[],"m_triggerList":[]}]}"""

        /** Four 16-byte blocks that can't be UTF-8: every one is a stray continuation byte. */
        val BINARY_BLOCKS = ByteArray(64) { (0x80 + it).toByte() }
    }
}
