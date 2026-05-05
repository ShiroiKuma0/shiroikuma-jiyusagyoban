package com.opentasker.core.engine

import com.opentasker.core.expressions.TemplateExpansionTrace
import com.opentasker.core.expressions.TemplateValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun runLogMessageIncludesTemplateExpressionDiagnostics() {
        val trace = ActionExecutionTrace(
            index = 0,
            actionType = "notify.show",
            label = "Notify",
            durationMs = 5,
            status = ActionTraceStatus.SUCCESS,
            message = "Completed",
            expandedArgSummary = "message=Hi ADA",
            argumentExpansions = listOf(
                ActionArgumentExpansionTrace(
                    argName = "message",
                    rawValue = "Hi {{ name | upper }}",
                    expandedValue = "Hi ADA",
                    expressions = listOf(
                        TemplateExpansionTrace(
                            rawExpression = "{{ name | upper }}",
                            expression = "name | upper",
                            value = "ADA",
                            source = TemplateValueSource.GLOBAL,
                            path = "name",
                            functions = listOf("upper"),
                        )
                    ),
                    warnings = emptyList(),
                )
            ),
        )

        val message = listOf(trace).toRunLogMessage()

        assertTrue(message.contains("args: message=Hi ADA"))
        assertTrue(message.contains("Template:\tmessage\tglobal\tname | upper\tADA\t"))
    }

    @Test
    fun runLogMessageRedactsSensitiveTemplateExpressionDiagnostics() {
        val trace = ActionExecutionTrace(
            index = 0,
            actionType = "http.post",
            label = "Post",
            durationMs = 5,
            status = ActionTraceStatus.SUCCESS,
            message = "Completed",
            argumentExpansions = listOf(
                ActionArgumentExpansionTrace(
                    argName = "apiToken",
                    rawValue = "{{ token }}",
                    expandedValue = "secret-value",
                    expressions = listOf(
                        TemplateExpansionTrace(
                            rawExpression = "{{ token }}",
                            expression = "token",
                            value = "secret-value",
                            source = TemplateValueSource.GLOBAL,
                            path = "token",
                        )
                    ),
                    warnings = emptyList(),
                )
            ),
        )

        val message = listOf(trace).toRunLogMessage()

        assertTrue(message.contains("Template:\tapiToken\tglobal\t<redacted>\t<redacted>\t"))
        assertTrue(!message.contains("secret-value"))
    }
}
