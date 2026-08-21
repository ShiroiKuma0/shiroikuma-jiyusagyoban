package com.opentasker.core.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The allowlist decides whether a Termux script may run at all, so its rules are asserted here
 * rather than only through the store, which needs a Context.
 */
class TermuxAllowlistPolicyTest {
    private val path = "${TermuxScriptBackend.SCRIPT_DIRECTORY}/backup.sh"
    private val hash = "a".repeat(64)

    @Test
    fun anApprovalRoundTrips() {
        val script = ApprovedTermuxScript(path, hash)

        assertEquals(script, TermuxAllowlistPolicy.decode(TermuxAllowlistPolicy.encode(script)))
    }

    @Test
    fun aRowThisVersionDidNotWriteReadsAsAbsentRatherThanApproved() {
        listOf(
            null,
            "",
            hash,
            "$hash\n",
            "\n$path",
            "not-a-hash\n$path",
            "${hash.uppercase()}X\n$path",
            "$hash\n/etc/passwd",
            "$hash\n$path/../../escape.sh",
        ).forEach { row ->
            assertNull("A malformed row must not approve anything: '$row'", TermuxAllowlistPolicy.decode(row))
        }
    }

    @Test
    fun aHashIsNormalisedBeforeItIsTrusted() {
        val decoded = TermuxAllowlistPolicy.decode("  ${hash.uppercase()}  \n$path")

        assertEquals("Case and padding must not create a second approval", hash, decoded?.sha256)
    }

    @Test
    fun theApprovalGateRejectsBadInputAndEnforcesTheCap() {
        assertEquals(
            TermuxAllowlistSaveResult.SAVED,
            TermuxAllowlistPolicy.admit(path, hash, alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            TermuxAllowlistSaveResult.INVALID_PATH,
            TermuxAllowlistPolicy.admit("/etc/passwd", hash, alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            TermuxAllowlistSaveResult.INVALID_HASH,
            TermuxAllowlistPolicy.admit(path, "short", alreadyApproved = false, approvedCount = 0),
        )
        assertEquals(
            "A bad path must be rejected before the cap is even consulted",
            TermuxAllowlistSaveResult.INVALID_PATH,
            TermuxAllowlistPolicy.admit(
                "../escape.sh",
                hash,
                alreadyApproved = false,
                approvedCount = TermuxScriptAllowlistStore.MAX_APPROVED_SCRIPTS,
            ),
        )
    }

    @Test
    fun theCapBlocksANewScriptButNeverAnUpdateToAnApprovedOne() {
        val full = TermuxScriptAllowlistStore.MAX_APPROVED_SCRIPTS

        assertEquals(
            TermuxAllowlistSaveResult.FULL,
            TermuxAllowlistPolicy.admit(path, hash, alreadyApproved = false, approvedCount = full),
        )
        assertEquals(
            "Re-approving a script already on the list must keep working when the list is full",
            TermuxAllowlistSaveResult.SAVED,
            TermuxAllowlistPolicy.admit(path, hash, alreadyApproved = true, approvedCount = full),
        )
        assertEquals(
            TermuxAllowlistSaveResult.SAVED,
            TermuxAllowlistPolicy.admit(path, hash, alreadyApproved = false, approvedCount = full - 1),
        )
    }
}
