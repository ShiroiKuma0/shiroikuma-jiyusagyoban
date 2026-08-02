package com.opentasker.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorLifecycleTest {
    @Test
    fun registrationIsIdempotentAndTeardownRunsOnce() {
        val lifecycle = MonitorLifecycle()
        var registrations = 0
        var teardowns = 0

        assertTrue(lifecycle.start { registrations++; true })
        assertTrue(lifecycle.start { registrations++; true })
        lifecycle.stop { teardowns++ }
        lifecycle.stop { teardowns++ }

        assertEquals(1, registrations)
        assertEquals(1, teardowns)
        assertFalse(lifecycle.isActive())
    }

    @Test
    fun failedRegistrationLeavesLifecycleRetryableAndCleanupExceptionsAreContained() {
        val lifecycle = MonitorLifecycle()
        var attempts = 0

        assertFalse(lifecycle.start { attempts++; false })
        assertTrue(lifecycle.start { attempts++; true })
        lifecycle.stop { error("platform callback already removed") }

        assertEquals(2, attempts)
        assertFalse(lifecycle.isActive())
    }
}
