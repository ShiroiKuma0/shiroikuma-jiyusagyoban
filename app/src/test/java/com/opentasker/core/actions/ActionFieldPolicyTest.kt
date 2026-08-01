package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionFieldPolicyTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun everyRegisteredFieldHasACompleteRendererAndValidatorDefinition() {
        ActionMetadataRegistry.all().forEach { metadata ->
            metadata.fields.forEach { field ->
                assertNotNull(
                    "${metadata.id}.${field.key} has no complete renderer definition",
                    ActionFieldPolicy.rendererFor(field),
                )
                val representativeValue = when (field.fieldType) {
                    FieldType.TEXT, FieldType.MULTILINE -> "value"
                    FieldType.NUMBER -> "%dynamic"
                    FieldType.DROPDOWN -> field.options.first().value
                    FieldType.CHECKBOX -> "true"
                    FieldType.TASK -> "7"
                    FieldType.APP -> "com.example.app"
                    FieldType.FILE -> "folder/file.txt"
                }
                assertNull(
                    "${metadata.id}.${field.key} rejected its representative value",
                    ActionFieldPolicy.validate(field, representativeValue, setOf(7)),
                )
            }
        }
    }

    @Test
    fun everyDropdownHasUniqueStableStoredValues() {
        ActionMetadataRegistry.all().flatMap { it.fields }.filter { it.fieldType == FieldType.DROPDOWN }
            .forEach { field ->
                assertTrue("${field.key} must have options", field.options.isNotEmpty())
                assertEquals(field.options.size, field.options.map { it.value }.distinct().size)
                assertTrue(field.options.all { it.value.isNotBlank() })
            }
    }

    @Test
    fun numbersEnforceSyntaxKindAndRangeWhileAllowingRuntimeVariables() {
        val field = ActionField(
            key = "count",
            labelRes = 1,
            fieldType = FieldType.NUMBER,
            numberRule = ActionNumberRule(minimum = 1.0, maximum = 10.0),
        )

        assertEquals(ActionFieldPolicy.Error.INVALID_NUMBER, ActionFieldPolicy.validate(field, "1.5")?.error)
        assertEquals(ActionFieldPolicy.Error.INVALID_NUMBER, ActionFieldPolicy.validate(field, "1e3")?.error)
        assertEquals(ActionFieldPolicy.Error.BELOW_MINIMUM, ActionFieldPolicy.validate(field, "0")?.error)
        assertEquals(ActionFieldPolicy.Error.ABOVE_MAXIMUM, ActionFieldPolicy.validate(field, "11")?.error)
        assertNull(ActionFieldPolicy.validate(field, "7"))
        assertNull(ActionFieldPolicy.validate(field, "%count"))
    }

    @Test
    fun dedicatedValueTypesRejectInvalidReferencesAndEscapingPaths() {
        val task = ActionField("task", 1, FieldType.TASK)
        val app = ActionField("app", 1, FieldType.APP)
        val file = ActionField("path", 1, FieldType.FILE, fileRule = ActionFileRule())

        assertEquals(ActionFieldPolicy.Error.INVALID_TASK, ActionFieldPolicy.validate(task, "9", setOf(7))?.error)
        assertEquals(ActionFieldPolicy.Error.INVALID_APP, ActionFieldPolicy.validate(app, "not a package")?.error)
        assertEquals(ActionFieldPolicy.Error.INVALID_FILE, ActionFieldPolicy.validate(file, "../../escape")?.error)
        assertNull(ActionFieldPolicy.validate(task, "7", setOf(7)))
        assertNull(ActionFieldPolicy.validate(app, "com.example.app"))
        assertNull(ActionFieldPolicy.validate(file, "reports/today.txt"))
    }

    @Test
    fun httpRequestRejectsCrossFieldConflictsBeforeSave() {
        val metadata = requireNotNull(ActionMetadataRegistry.get("http.request"))
        val conflicts = ActionFieldPolicy.validateForm(
            metadata = metadata,
            values = mapOf(
                "url" to "https://example.com",
                "method" to "POST",
                "body" to "inline",
                "body_file" to "request.json",
                "response_var" to "result",
                "output_file" to "response.bin",
            ),
        )

        assertEquals(ActionFieldPolicy.Error.CONFLICTING_VALUE, conflicts["body"]?.error)
        assertEquals(ActionFieldPolicy.Error.CONFLICTING_VALUE, conflicts["body_file"]?.error)
        assertEquals(ActionFieldPolicy.Error.CONFLICTING_VALUE, conflicts["response_var"]?.error)
        assertEquals(ActionFieldPolicy.Error.CONFLICTING_VALUE, conflicts["output_file"]?.error)

        val oversizedInlineResponse = ActionFieldPolicy.validateForm(
            metadata = metadata,
            values = mapOf(
                "url" to "https://example.com",
                "max_response_bytes" to "1048577",
            ),
        )
        assertEquals(ActionFieldPolicy.Error.ABOVE_MAXIMUM, oversizedInlineResponse["max_response_bytes"]?.error)
        assertEquals(1_048_576.0, oversizedInlineResponse["max_response_bytes"]?.limit)
    }

    @Test
    fun savingKnownFieldsPreservesUnknownArgumentsByteForByte() {
        val opaqueValue = "  future\u0000value\r\nwith spacing  "
        val existing = linkedMapOf(
            "future.key" to opaqueValue,
            "known" to "old",
            "future.second" to "{\"v\":2}",
        )
        val result = mergeActionArguments(
            existing = existing,
            fields = listOf(ActionField("known", 1), ActionField("removed", 1)),
            editedValues = mapOf("known" to "new", "removed" to ""),
        )

        assertEquals(opaqueValue, result["future.key"])
        assertEquals("{\"v\":2}", result["future.second"])
        assertEquals("new", result["known"])
        assertEquals(listOf("future.key", "known", "future.second"), result.keys.toList())
        assertEquals(3, result.size)
    }
}
