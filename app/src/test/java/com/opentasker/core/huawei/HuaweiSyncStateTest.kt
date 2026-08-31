package com.opentasker.core.huawei

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The progress singleton's contract. Small, but every rule here exists because getting it wrong
 * shows the wrong thing on screen during the one operation 白い熊 cannot otherwise observe.
 */
class HuaweiSyncStateTest {

    @Before fun reset() = HuaweiSyncState.idle()

    @After fun clean() = HuaweiSyncState.idle()

    @Test
    fun `arm announces the sync before any coroutine runs`() {
        assertTrue(HuaweiSyncState.arm())
        val p = HuaweiSyncState.progress.value
        assertTrue(p.running)
        assertEquals("starting", p.phase)
        assertTrue("the elapsed counter needs its origin", p.startedAtMillis > 0L)
    }

    @Test
    fun `a second arm is refused while one is in flight`() {
        // The state on screen belongs to the running sync; a second press must not overwrite it.
        assertTrue(HuaweiSyncState.arm())
        val first = HuaweiSyncState.progress.value.startedAtMillis
        assertFalse(HuaweiSyncState.arm())
        assertEquals(first, HuaweiSyncState.progress.value.startedAtMillis)
    }

    @Test
    fun `begin keeps the moment the button was pressed`() {
        // Otherwise the elapsed-seconds counter jumps back to zero when the runner takes over.
        HuaweiSyncState.arm()
        val armed = HuaweiSyncState.progress.value.startedAtMillis
        HuaweiSyncState.begin(windowCount = 3)
        val p = HuaweiSyncState.progress.value
        assertEquals(armed, p.startedAtMillis)
        assertEquals(3, p.windowCount)
        assertEquals("connecting", p.phase)
    }

    @Test
    fun `begin without arm still stamps a start time`() {
        HuaweiSyncState.begin(windowCount = 1)
        assertTrue(HuaweiSyncState.progress.value.startedAtMillis > 0L)
    }

    @Test
    fun `percent spans the whole run, not one window`() {
        HuaweiSyncState.arm()
        HuaweiSyncState.begin(windowCount = 2)
        HuaweiSyncState.window(1)
        HuaweiSyncState.record(index = 5, count = 10)
        // Halfway through the first of two windows.
        assertEquals(25, HuaweiSyncState.progress.value.percent)
        HuaweiSyncState.window(2)
        HuaweiSyncState.record(index = 10, count = 10)
        assertEquals(100, HuaweiSyncState.progress.value.percent)
    }

    @Test
    fun `a zero-record window does not divide by zero`() {
        HuaweiSyncState.begin(windowCount = 1)
        HuaweiSyncState.window(1)
        HuaweiSyncState.record(index = 0, count = 0)
        assertEquals(0, HuaweiSyncState.progress.value.percent)
    }

    @Test
    fun `finish clears running and completes the bar`() {
        HuaweiSyncState.arm()
        HuaweiSyncState.begin(1)
        HuaweiSyncState.counted(samples = 120, inserted = 118)
        HuaweiSyncState.finish("120 samples")
        val p = HuaweiSyncState.progress.value
        assertFalse(p.running)
        assertEquals("done", p.phase)
        assertEquals(100, p.percent)
        assertEquals("120 samples", p.message)
        assertEquals(120, p.samples)
        assertEquals(118, p.inserted)
    }

    @Test
    fun `arm is available again after finish`() {
        HuaweiSyncState.arm()
        HuaweiSyncState.finish("done")
        assertTrue("a finished sync must not block the next one", HuaweiSyncState.arm())
    }

    @Test
    fun `idle resets everything`() {
        HuaweiSyncState.arm()
        HuaweiSyncState.begin(4)
        HuaweiSyncState.idle()
        assertEquals(HuaweiSyncProgress.IDLE, HuaweiSyncState.progress.value)
    }
}
