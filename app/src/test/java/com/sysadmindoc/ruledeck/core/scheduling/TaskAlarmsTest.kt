package com.sysadmindoc.ruledeck.core.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAlarmsTest {
    private fun order(key: String, atMs: Long) =
        TaskAlarms.Order(key = key, task = "中継 定期 pong", project = "携帯", atMs = atMs, params = mapOf("slot" to "0", "note" to "a·b\nc"))

    @Test
    fun anOrderIsNotRunBeforeItsTime() {
        val at = 1_000_000L
        // The alarm of a replaced order arrives while the new one is still minutes away.
        assertFalse(TaskAlarms.isDue(order("k", at), at - 60_000))
        assertTrue(TaskAlarms.isDue(order("k", at), at - TaskAlarms.EARLY_TOLERANCE_MS))
        assertTrue(TaskAlarms.isDue(order("k", at), at + 5))
    }

    @Test
    fun theSweepRunsOnlyWhatItsOwnAlarmHasMissed() {
        val now = 10_000_000L
        val fresh = order("fresh", now - TaskAlarms.LATE_MS + 1)
        val late = order("late", now - TaskAlarms.LATE_MS)
        val future = order("future", now + 600_000)
        val (run, wait) = TaskAlarms.splitLate(listOf(fresh, late, future), now)
        assertEquals(listOf(late), run)
        assertEquals(listOf(fresh, future), wait)
    }
}
