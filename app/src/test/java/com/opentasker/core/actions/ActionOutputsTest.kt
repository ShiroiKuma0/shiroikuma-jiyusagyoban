package com.opentasker.core.actions

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionOutputsTest {

    @Before
    fun setUp() {
        registerActionMetadata()
    }

    @Test
    fun outputDefinitionsResolveDynamicNamesAndTypes() {
        val outputs = requireNotNull(ActionMetadataRegistry.get("text.split"))
            .resolveOutputs(ActionSpec(type = "text.split", args = mapOf("var" to "%parts")))

        assertEquals(listOf("parts", "parts", "parts_count"), outputs.map { it.name })
        assertEquals(
            listOf(ActionValueType.TEXT, ActionValueType.ARRAY, ActionValueType.NUMBER),
            outputs.map { it.type },
        )
        assertEquals(listOf("{{ parts }}", "{{ array.parts }}", "{{ parts_count }}"), outputs.map { it.reference })
    }

    @Test
    fun globalOutputsUseTheRuntimePromotedNameAndConditionalOutputsStayHonest() {
        val persisted = requireNotNull(ActionMetadataRegistry.get("var.persist"))
            .resolveOutputs(ActionSpec(type = "var.persist", args = mapOf("name" to "draft")))
        assertEquals("Draft", persisted.single().name)
        assertEquals("{{ global.Draft }}", persisted.single().reference)

        val activityIntent = requireNotNull(ActionMetadataRegistry.get("intent.launch"))
            .resolveOutputs(ActionSpec(type = "intent.launch", args = mapOf("result_variable" to "code")))
        val broadcastIntent = requireNotNull(ActionMetadataRegistry.get("intent.launch"))
            .resolveOutputs(ActionSpec(type = "intent.launch", args = mapOf("mode" to "broadcast", "result_variable" to "code")))
        assertTrue(activityIntent.isEmpty())
        assertEquals("code", broadcastIntent.single().name)
    }

    @Test
    fun optionsOnlyExposeEarlierStepsAndKeepEventAndGlobalScopesValueFree() {
        val actions = listOf(
            ActionSpec(type = "text.split", args = mapOf("var" to "parts")),
            ActionSpec(type = "datetime.parse", args = mapOf("var" to "timestamp")),
            ActionSpec(type = "text.replace", args = mapOf("var" to "result")),
        )
        val options = typedVariableOptions(
            actions = actions,
            editingIndex = 2,
            globalNames = listOf("COUNT", "SecretValue", "invalid name"),
        )

        assertTrue(options.any { it.scope == VariableChipScope.STEP && it.name == "parts" && it.actionIndex == 0 })
        assertTrue(options.any { it.scope == VariableChipScope.STEP && it.name == "timestamp" && it.actionIndex == 1 })
        assertFalse(options.any { it.scope == VariableChipScope.STEP && it.name == "result" })
        assertTrue(options.any { it.scope == VariableChipScope.EVENT && it.name == "share_count" && it.reference == "{{ event.share_count }}" })
        assertTrue(options.any { it.scope == VariableChipScope.GLOBAL && it.name == "SecretValue" && it.reference == "{{ global.SecretValue }}" })
        assertFalse(options.any { it.name == "invalid name" })
    }

    @Test
    fun fieldCompatibilityKeepsNumbersAndArraysTyped() {
        val number = ActionField("number", 1, FieldType.NUMBER, numberRule = ActionNumberRule())
        val array = ActionField("array", 1, inputType = ActionValueType.ARRAY)

        assertTrue(number.acceptsVariableType(ActionValueType.NUMBER))
        assertTrue(number.acceptsVariableType(ActionValueType.ANY))
        assertFalse(number.acceptsVariableType(ActionValueType.TEXT))
        assertTrue(array.acceptsVariableType(ActionValueType.ARRAY))
        assertFalse(array.acceptsVariableType(ActionValueType.TEXT))
    }

    @Test
    fun insertionAndBundleRoundTripKeepTemplateTextUnchanged() {
        val option = VariableChipOption(
            name = "COUNT",
            type = ActionValueType.NUMBER,
            scope = VariableChipScope.GLOBAL,
            reference = "{{ global.COUNT }}",
        )
        assertEquals("before {{ global.COUNT }}", insertVariableChip("before", option))

        val chip = "{{ global.COUNT }} | add(1)"
        val task = Task(
            id = 7,
            name = "Uses a chip",
            actions = listOf(ActionSpec(type = "text.replace", args = mapOf("source" to chip))),
        )
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "test",
            exportedAtEpochMs = 1L,
            profiles = emptyList(),
            tasks = listOf(task),
        )

        val roundTripped = OpenTaskerBundleCodec.decode(OpenTaskerBundleCodec.encode(bundle))
        assertEquals(chip, roundTripped.tasks.single().actions.single().args["source"])
    }
}
