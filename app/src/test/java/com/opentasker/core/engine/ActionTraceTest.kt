package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionTraceTest {
    @Test
    fun runLogMessageSummarizesActionTracesAndOverflow() {
        val traces = (0..9).map { index ->
            ActionExecutionTrace(
                index = index,
                actionType = "log",
                label = "Log $index",
                durationMs = index.toLong(),
                status = ActionTraceStatus.SUCCESS,
                message = "Completed",
            )
        }

        val message = traces.toRunLogMessage(maxLines = 2)

        assertEquals(
            "1. success: Log 0 [log] 0ms - Completed\n2. success: Log 1 [log] 1ms - Completed\n... 8 more action(s)",
            message,
        )
    }
}
