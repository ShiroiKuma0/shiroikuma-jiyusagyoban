package com.opentasker.core.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateExpressionEngineTest {
    private val engine = TemplateExpressionEngine()

    @Test
    fun taskScopeOverridesEventAndGlobalScope() {
        val result = engine.expand(
            template = "Hello {{ name }} from {{ event.name }} and {{ global.name }}",
            scope = TemplateScope(
                global = mapOf("name" to "Global"),
                event = mapOf("name" to "Event"),
                task = mapOf("name" to "Task"),
            ),
        )

        assertEquals("Hello Task from Event and Global", result.value)
        assertEquals(TemplateValueSource.TASK, result.traces.first().source)
    }

    @Test
    fun defaultFallbackFillsMissingValues() {
        val result = engine.expand(
            template = "Notify {{ recipient | default:\"Owner\" }}",
            scope = TemplateScope(),
        )

        assertEquals("Notify Owner", result.value)
        assertEquals(TemplateValueSource.DEFAULT, result.traces.single().source)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun stringAndMathFunctionsAreAppliedInOrder() {
        val result = engine.expand(
            template = "{{ label | trim | upper }} {{ count | add:5 | mul:2 }} {{ score | round }}",
            scope = TemplateScope(
                task = mapOf(
                    "label" to "  open tasker  ",
                    "count" to "7",
                    "score" to "7.6",
                ),
            ),
        )

        assertEquals("OPEN TASKER 24 8", result.value)
        assertEquals(listOf("trim", "upper"), result.traces[0].functions)
    }

    @Test
    fun jsonPathsReadNestedObjectFields() {
        val result = engine.expand(
            template = "User {{ payload.user.name }} count {{ payload.items[#] }} first {{ payload.items[0].label }}",
            scope = TemplateScope(
                event = mapOf(
                    "payload" to """{"user":{"name":"Ada"},"items":[{"label":"one"},{"label":"two"}]}""",
                ),
            ),
        )

        assertEquals("User Ada count 2 first one", result.value)
    }

    @Test
    fun arrayReferencesSupportIndexCountAndJoin() {
        val result = engine.expand(
            template = "{{ items[1] }} / {{ items[#] }} / {{ items | join:\";\" }}",
            scope = TemplateScope(
                arrays = mapOf("items" to listOf("one", "two", "three")),
            ),
        )

        assertEquals("two / 3 / one;two;three", result.value)
        assertEquals(TemplateValueSource.ARRAY, result.traces.first().source)
    }

    @Test
    fun unknownFunctionsFailClosedByPreservingToken() {
        val result = engine.expand(
            template = "Value {{ name | shell:\"rm -rf\" }}",
            scope = TemplateScope(task = mapOf("name" to "OpenTasker")),
        )

        assertEquals("Value {{ name | shell:\"rm -rf\" }}", result.value)
        assertTrue(result.warnings.any { it.contains("Unknown template function") })
        assertEquals(TemplateValueSource.MISSING, result.traces.single().source)
    }

    @Test
    fun resolvedValueLimitPreventsExpansionAbuse() {
        val limitedEngine = TemplateExpressionEngine(
            TemplateExpressionLimits(maxResolvedValueLength = 8),
        )
        val result = limitedEngine.expand(
            template = "Payload {{ payload }}",
            scope = TemplateScope(task = mapOf("payload" to "0123456789")),
        )

        assertEquals("Payload {{ payload }}", result.value)
        assertTrue(result.warnings.any { it.contains("Resolved value exceeds") })
    }
}
