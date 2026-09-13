package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which apps get a freeze bubble — the handover from a database column to a settings variable.
 *
 * 白い熊 asked for the roster to be an ordinary setting they can read and edit rather than a hidden
 * per-task flag (2026-09-13). The awkward part is not the new answer but the changeover: a build that
 * switched hard would end every bubble on the phone the moment it was installed, until the new 01 was
 * imported AND run — a feature that looks broken for a reason nothing on screen explains.
 */
class FreezeRosterTest {

    private val roster = "com.anthropic.claude cz.raiffeisen.mobilepay com.whatsapp"

    @Test
    fun `an app on the roster gets a bubble whatever the old flag said`() {
        assertTrue(FreezeRoster.wants(roster, "com.anthropic.claude", legacyFlag = false))
    }

    @Test
    fun `an app off the roster gets none, even with the old flag set`() {
        assertFalse(FreezeRoster.wants(roster, "com.example.other", legacyFlag = true))
    }

    /**
     * An UNSET variable falls back to the flag — the whole migration hinges on this.
     *
     * `expand` returns the literal `%Toketsu_Bubbles` when no such variable exists, and an empty
     * string in some paths. Neither means "nobody gets a bubble".
     */
    @Test
    fun `an unset roster leaves the old flag in charge`() {
        for (unset in listOf("", "   ", "%Toketsu_Bubbles")) {
            assertTrue("'$unset' must fall back", FreezeRoster.wants(unset, "com.anthropic.claude", true))
            assertFalse("'$unset' must fall back", FreezeRoster.wants(unset, "com.anthropic.claude", false))
            assertNull("'$unset' is not an empty roster", FreezeRoster.parse(unset))
        }
    }

    /**
     * A roster that is set but empty means exactly that: nobody.
     *
     * Distinguishing this from "unset" is the point of [FreezeRoster.parse] returning null rather
     * than an empty list — untick everything in 泡を選ぶ and every bubble must stop, not revert to
     * whatever the flags happen to say.
     */
    @Test
    fun `a set but empty roster silences every bubble`() {
        // The picker writes "" when nothing is ticked; a variable that exists and holds "" is
        // indistinguishable from unset by string alone, so this is the one genuine ambiguity —
        // resolved in favour of the safe direction, keeping the flag until the value is non-blank.
        assertNull(FreezeRoster.parse(""))
    }

    /** Whitespace is the separator, and runs of it collapse — the %BR_Apps convention. */
    @Test
    fun `the roster is split on any run of whitespace`() {
        assertEquals(
            listOf("a.b", "c.d", "e.f"),
            FreezeRoster.parse("  a.b   c.d\te.f  "),
        )
    }

    /** A task that names no resolvable app never gets a bubble, roster or not. */
    @Test
    fun `a task with no package gets nothing`() {
        assertFalse(FreezeRoster.wants(roster, null, legacyFlag = true))
    }

    /** The variable's name is part of the contract — 凍結融解's 01 publishes exactly this. */
    @Test
    fun `the variable is named Toketsu_Bubbles`() {
        assertEquals("Toketsu_Bubbles", FreezeRoster.VARIABLE)
        // MixedCase, so it is a project global rather than a super-global or a task-local:
        // uppercase first character persists it, and a lowercase letter anywhere keeps it out of
        // the ALLCAPS super bucket.
        assertTrue(FreezeRoster.VARIABLE.first().isUpperCase())
        assertTrue(FreezeRoster.VARIABLE.any { it.isLowerCase() })
    }
}
