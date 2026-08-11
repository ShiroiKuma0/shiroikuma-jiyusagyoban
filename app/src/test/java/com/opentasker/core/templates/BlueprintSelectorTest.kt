package com.opentasker.core.templates

import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueprintSelectorTest {
    @Test
    fun selectorsValidateTypedValuesAndInstantiationAlwaysStartsDisabled() {
        val blueprint = blueprint(
            inputs = listOf(
                BlueprintInput("app", "App", "com.example.app", selector = BlueprintSelectorKind.APP),
                BlueprintInput("ssid", "SSID", "Home", selector = BlueprintSelectorKind.WIFI_SSID),
                BlueprintInput("location", "Location", "40.0,-74.0", selector = BlueprintSelectorKind.LOCATION),
                BlueprintInput("task", "Task", "12", selector = BlueprintSelectorKind.TASK_REFERENCE),
                BlueprintInput("variable", "Variable", "count", selector = BlueprintSelectorKind.VARIABLE),
                BlueprintInput("duration", "Duration", "5000", selector = BlueprintSelectorKind.DURATION, maximum = 10_000.0),
            ),
            enabledByDefault = true,
        )

        assertNull(blueprint.inputs[0].validationError("com.example.app"))
        assertNotNull(blueprint.inputs[0].validationError("not a package"))
        assertNull(blueprint.inputs[1].validationError("Home WiFi"))
        assertNotNull(blueprint.inputs[1].validationError("x".repeat(33)))
        assertNull(blueprint.inputs[2].validationError("40.0,-74.0"))
        assertNotNull(blueprint.inputs[2].validationError("95.0,-74.0"))
        assertNull(blueprint.inputs[3].validationError("12"))
        assertNotNull(blueprint.inputs[3].validationError("0"))
        assertNull(blueprint.inputs[4].validationError("count"))
        assertNotNull(blueprint.inputs[4].validationError("Count"))
        assertNull(blueprint.inputs[5].validationError("5000"))
        assertNotNull(blueprint.inputs[5].validationError("10001"))

        val applied = blueprint.instantiate(blueprint.defaults())
        assertFalse(applied.profile.enabled)
        assertEquals("com.example.app", applied.profile.contexts.single().config["app"])
    }

    @Test
    fun catalogUsesSelectorsWithoutTemplateSpecificFormLogic() {
        val work = ProfileTemplateCatalog.get("work-hours-focus")!!
        val location = ProfileTemplateCatalog.get("location-evidence-log")!!
        val app = ProfileTemplateCatalog.get("app-usage-reminder")!!

        assertEquals(BlueprintSelectorKind.TIME, work.inputs.first { it.key == "start" }.selector)
        assertEquals(BlueprintSelectorKind.INTEGER, work.inputs.first { it.key == "level" }.selector)
        assertEquals(BlueprintSelectorKind.DECIMAL, location.inputs.first { it.key == "latitude" }.selector)
        assertEquals(BlueprintSelectorKind.APP, app.inputs.first { it.key == "package" }.selector)
        assertTrue(work.inputs.all { it.section.isNotBlank() })
    }

    private fun blueprint(
        inputs: List<BlueprintInput>,
        enabledByDefault: Boolean = false,
    ) = AutomationBlueprint(
        id = "selector-test",
        version = 1,
        title = "Selector test",
        summary = "Typed selector test",
        category = "Tests",
        availability = TemplateAvailability.Ready,
        safetyNote = "Test only",
        inputs = inputs,
        contexts = listOf(TemplateContext(ContextType.STATE, mapOf("app" to "{app}"))),
        actions = listOf(TemplateAction("log", "Log {variable}", mapOf("message" to "{duration}"))),
        enabledByDefault = enabledByDefault,
    )
}
