package com.opentasker.core.huawei

import com.opentasker.ProductionSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker records the ABSENCE of an ending, and that only works if three things hold.
 *
 * The band serves one host and keeps holding the slot after that host is gone: on 2026-09-10 it
 * refused every connection twice, hours after a good sync, and only a band restart cleared it. What
 * puts it there was untestable — `logcat` had rolled and the run log is in a table nothing can read
 * off the phone. This flag is the test: set when the link opens, cleared when it closes, so a flag
 * still standing at the next start means the close never ran.
 */
class SessionMarkerTest {

    private val marker = ProductionSources.read("com/opentasker/core/huawei/HuaweiSessionMarker.kt")
    private val client = ProductionSources.read("com/opentasker/core/huawei/HuaweiRfcommClient.kt")
    private val action = ProductionSources.read("com/opentasker/core/actions/HuaweiSyncAction.kt")

    /**
     * Committed, not applied.
     *
     * `apply()` writes to memory now and to disk when it gets round to it — and the kill is exactly
     * the event that would lose it. A marker whose whole job is to survive being killed cannot be
     * written asynchronously, and a lost CLEAR is just as bad: it would report an unclean session
     * that was fine, and a diagnostic that cries wolf is worse than none.
     */
    @Test
    fun `every write is synchronous`() {
        assertTrue("the mark must be committed", marker.contains(".putLong(KEY_SINCE, System.currentTimeMillis()).commit()"))
        assertFalse("apply() would lose the very case this exists for", marker.contains(".apply()"))
        assertTrue("and so must both clears", Regex("""remove\(KEY_SINCE\)\.commit\(\)""").findAll(marker).count() == 2)
    }

    /** Set on a link that actually opened, cleared on a link that actually existed. */
    @Test
    fun `the transport marks the session at both ends`() {
        assertTrue("opened when the connect succeeds", client.contains("HuaweiSessionMarker.open(context)"))
        assertTrue("cleared on close", client.contains("HuaweiSessionMarker.close(context)"))
        // closeQuietly also runs BETWEEN rungs of the connect ladder, where nothing was ever opened.
        // Clearing there would erase a mark left by a previous, genuinely unclean session.
        assertTrue(
            "the clear must be guarded by there being a socket at all",
            client.contains("if (socket != null) runCatching { HuaweiSessionMarker.close(context) }"),
        )
    }

    /**
     * Read before the new link opens — the only moment the two can be told apart.
     *
     * After `open` the mark belongs to the session now running, so a check afterwards would report
     * every session as unclean.
     */
    @Test
    fun `the previous session is read before this one starts`() {
        val before = action.substringBefore("""val address = args["address"]""")
        assertTrue("the check must precede the connect", before.contains("HuaweiSessionMarker.takeUnclean"))
        assertTrue("and it must be reported once, not forever", marker.contains("prefs(context).edit().remove(KEY_SINCE).commit()"))
    }

    /** And it must land in the summary line, which is the thing that gets photographed. */
    @Test
    fun `the finding reaches the panel`() {
        assertTrue(action.contains("withUncleanNote"))
        assertTrue(action.contains("never closed"))
        assertTrue("with the remedy beside it", action.contains("restart the band"))
        assertTrue("and as its own variable, for anything else that wants it",
            action.contains("""${'$'}{prefix}LastSessionUnclean"""))
    }
}
