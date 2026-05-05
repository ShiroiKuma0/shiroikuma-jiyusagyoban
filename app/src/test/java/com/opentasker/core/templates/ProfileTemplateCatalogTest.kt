package com.opentasker.core.templates

import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTemplateCatalogTest {
    @Test
    fun catalogIncludesRoadmapPatterns() {
        val ids = ProfileTemplateCatalog.all.map { it.id }.toSet()

        assertEquals(
            setOf(
                "work-hours-focus",
                "headphones-media",
                "low-battery-saver",
                "wifi-arrival",
                "app-usage-reminder",
                "find-my-phone",
                "meeting-mode-calendar",
                "nightstand-nfc-sleep",
            ),
            ids,
        )
    }

    @Test
    fun installableTemplatesAvoidUnsupportedActions() {
        val unsupported = ProfileTemplateCatalog.all
            .filter { it.installable }
            .flatMap { template -> template.actions.map { template.id to it.type } }
            .filter { (_, actionId) -> !ActionCapabilityRegistry.get(actionId).canAdd }

        assertTrue("Installable templates use unsupported actions: $unsupported", unsupported.isEmpty())
    }

    @Test
    fun installableTemplatesCreateDisabledProfilesAndTasksWithoutUnresolvedSlots() {
        ProfileTemplateCatalog.all.filter { it.installable }.forEach { template ->
            val applied = template.instantiate(template.defaults())

            assertFalse("${template.id} should be created disabled", applied.profile.enabled)
            assertTrue("${template.id} should include at least one context", applied.profile.contexts.isNotEmpty())
            assertTrue("${template.id} should include at least one action", applied.task.actions.isNotEmpty())

            val rendered = buildString {
                append(applied.profile.name)
                applied.profile.contexts.forEach { context ->
                    append(context.config.values.joinToString())
                }
                applied.task.actions.forEach { action ->
                    append(action.label)
                    append(action.args.values.joinToString())
                }
            }
            assertFalse("${template.id} left unresolved slots in output: $rendered", rendered.contains('{') || rendered.contains('}'))
        }
    }

    @Test
    fun plannedTemplatesAreVisibleButNotInstallable() {
        val planned = ProfileTemplateCatalog.all.filter { it.availability == TemplateAvailability.Planned }

        assertEquals(1, planned.size)
        planned.forEach { template ->
            assertFalse(template.installable)
            var failed = false
            try {
                template.instantiate(template.defaults())
            } catch (expected: IllegalArgumentException) {
                failed = true
            }
            assertTrue("${template.id} should reject installation", failed)
        }
    }

    @Test
    fun nfcTemplateInstallsWithTagIdEventContext() {
        val template = ProfileTemplateCatalog.get("nightstand-nfc-sleep")!!

        assertTrue(template.installable)

        val applied = template.instantiate(template.defaults())
        val context = applied.profile.contexts.single()

        assertEquals(ContextType.EVENT, context.type)
        assertEquals("nfc", context.config["event"])
        assertEquals("04AABBCC", context.config["tagId"])
    }

    @Test
    fun calendarTemplateInstallsWithRedactedCalendarEventContext() {
        val template = ProfileTemplateCatalog.get("meeting-mode-calendar")!!

        assertTrue(template.installable)

        val applied = template.instantiate(template.defaults())
        val context = applied.profile.contexts.single()

        assertEquals(ContextType.EVENT, context.type)
        assertEquals("calendar", context.config["event"])
        assertEquals("during", context.config["state"])
        assertEquals("Work", context.config["calendar"])
    }
}
