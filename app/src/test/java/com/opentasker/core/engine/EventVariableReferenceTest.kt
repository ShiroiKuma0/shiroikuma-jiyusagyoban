package com.opentasker.core.engine

import android.content.Intent
import com.opentasker.core.actions.DEFAULT_EDITOR_EVENT_VARIABLES
import com.opentasker.core.actions.editorEventVariablesFor
import com.opentasker.core.actions.typedVariableOptions
import com.opentasker.core.contexts.BroadcastContextEvents
import com.opentasker.core.contexts.CallContextEvents
import com.opentasker.core.contexts.NotificationContextEvents
import com.opentasker.core.contexts.ShareContextEvents
import com.opentasker.core.contexts.ShareInput
import com.opentasker.core.contexts.SmsContextEvents
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A-282: the context editor and the chips name exactly what the engine hands a task. */
class EventVariableReferenceTest {
    private val events = listOf("share", "sms_received", "notification", "incoming_call", "broadcast")

    private fun listed(event: String): Set<String> = EventTaskVariables.declaredFor(event).map { it.name }.toSet()

    private fun eventSpec(event: String) = ContextSpec(ContextType.EVENT, mapOf("event" to event))

    @Test
    fun eachEventListsExactlyTheKeysItsSyntheticEventHandsATask() {
        for (event in events) {
            val synthetic = SyntheticTriggerSimulator.template(eventSpec(event)).event
            assertEquals(event, EventTaskVariables.from(synthetic).keys, listed(event))
        }
    }

    @Test
    fun theShareEditorListsItsVariablesWithAMeaningForEach() {
        val share = EventTaskVariables.declaredFor("share")

        assertEquals(
            listOf("share_event", "share_text", "share_uri", "share_uris", "share_mime", "share_count", "share_multiple"),
            share.map { it.name },
        )
        assertTrue(share.all { it.meaningRes != 0 })
        assertEquals("a context saved in another case still finds its list", share, EventTaskVariables.declaredFor(" Share "))
        assertEquals(emptyList<EventVariable>(), EventTaskVariables.declaredFor("usb"))
        assertEquals(emptyList<EventVariable>(), EventTaskVariables.declaredFor(null))
    }

    @Test
    fun realEventsFillTheListedVariablesFromTheirOwnFields() {
        val share = ShareContextEvents.parseInput(
            ShareInput(
                action = Intent.ACTION_SEND_MULTIPLE,
                mime = "image/*",
                textValue = "two photos",
                streamValue = arrayListOf("content://example/1", "content://example/2"),
            ),
        )
        val shareVariables = EventTaskVariables.from(share)
        assertEquals(listed("share"), shareVariables.keys)
        assertEquals(
            mapOf(
                "share_event" to "true",
                "share_text" to "two photos",
                "share_uri" to "content://example/1",
                "share_uris" to "content://example/1\ncontent://example/2",
                "share_mime" to "image/*",
                "share_count" to "2",
                "share_multiple" to "true",
            ),
            shareVariables,
        )

        val sms = SmsContextEvents.buildSmsEvent(listOf(SmsContextEvents.SmsPart("+15550100", "hi")), subscriptionId = 1, sdkInt = 36)
        assertEquals(listed("sms_received"), EventTaskVariables.from(sms).keys)
        assertEquals(
            mapOf(
                "sms_sender" to "+15550100",
                "sms_body" to "hi",
                "sms_kind" to SmsContextEvents.KIND_SMS,
                "sms_message_count" to "1",
                "sms_subscription_id" to "1",
            ),
            EventTaskVariables.from(sms),
        )

        val notification = NotificationContextEvents.buildEvent("com.example", "Title", "Body")
        assertEquals(
            mapOf("notification_package" to "com.example", "notification_title" to "Title", "notification_body" to "Body"),
            EventTaskVariables.from(notification),
        )

        val call = CallContextEvents.buildEvent("+15550100", true)
        assertEquals(mapOf("incoming_number" to "+15550100", "incoming_known_contact" to "true"), EventTaskVariables.from(call))

        val broadcast = BroadcastContextEvents.buildEvent(
            action = "com.example.GO",
            senderPackage = "com.example",
            extras = BroadcastContextEvents.SanitizedExtras(mapOf("mode" to "on"), lossy = true),
        )
        val broadcastVariables = EventTaskVariables.from(broadcast)
        assertEquals(listed("broadcast") + "broadcast_extra_mode", broadcastVariables.keys)
        assertEquals("com.example.GO", broadcastVariables["broadcast_action"])
        assertEquals("1", broadcastVariables["broadcast_extra_count"])
        assertEquals("true", broadcastVariables["broadcast_extras_lossy"])
    }

    @Test
    fun theDefaultChipsAreEveryListedVariable() {
        assertEquals(
            events.flatMap { event -> EventTaskVariables.declaredFor(event).map { it.name } }.toSet(),
            DEFAULT_EDITOR_EVENT_VARIABLES.map { it.name }.toSet(),
        )
        assertEquals(DEFAULT_EDITOR_EVENT_VARIABLES.size, DEFAULT_EDITOR_EVENT_VARIABLES.map { it.name }.toSet().size)
    }

    @Test
    fun aTaskAShareProfileStartsIsOfferedTheShareVariablesOnly() {
        val profiles = listOf(
            Profile(id = 1, name = "Shared", contexts = listOf(eventSpec("share")), enterTaskId = 10),
            Profile(id = 2, name = "Texts", contexts = listOf(eventSpec("sms_received")), enterTaskId = 20, exitTaskId = 10),
        )

        val references = typedVariableOptions(
            actions = emptyList(),
            eventVariables = editorEventVariablesFor(10, profiles),
        ).map { it.reference }

        assertTrue(references.toString(), "{{ event.share_text }}" in references)
        assertFalse("an exit run gets no event variables", "{{ event.sms_body }}" in references)
        assertEquals(listed("share"), editorEventVariablesFor(10, profiles).map { it.name }.toSet())
    }

    @Test
    fun aTaskThatOnlyRunsOnExitOrFromATimeProfileGetsNoEventChips() {
        val profiles = listOf(
            Profile(id = 1, name = "Texts", contexts = listOf(eventSpec("sms_received")), enterTaskId = 20, exitTaskId = 30, fallbackTaskId = 50),
            Profile(id = 2, name = "Morning", contexts = listOf(ContextSpec(ContextType.TIME, mapOf("from" to "08:00"))), enterTaskId = 40),
        )

        assertEquals(emptyList<Any>(), editorEventVariablesFor(30, profiles))
        assertEquals(emptyList<Any>(), editorEventVariablesFor(40, profiles))
        assertEquals("a fallback run gets the failure, not the event", emptyList<Any>(), editorEventVariablesFor(50, profiles))
    }

    @Test
    fun aTaskNoProfileUsesYetKeepsEveryEventsChips() {
        val profiles = listOf(Profile(id = 1, name = "Shared", contexts = listOf(eventSpec("share")), enterTaskId = 10))

        assertEquals(DEFAULT_EDITOR_EVENT_VARIABLES, editorEventVariablesFor(99, profiles))
    }

    @Test
    fun aTaskAnotherTaskRunsKeepsEveryEventsChips() {
        val profiles = listOf(Profile(id = 1, name = "Shared", contexts = listOf(eventSpec("share")), enterTaskId = 10))
        fun caller(ref: String, enabled: Boolean = true) = Task(
            id = 20,
            name = "Caller",
            actions = listOf(ActionSpec(type = SUB_TASK_ACTION_ID, args = mapOf("task" to ref), enabled = enabled)),
        )
        val shared = Task(id = 10, name = "Save share")

        assertEquals(DEFAULT_EDITOR_EVENT_VARIABLES, editorEventVariablesFor(10, profiles, listOf(shared, caller("10"))))
        assertEquals(DEFAULT_EDITOR_EVENT_VARIABLES, editorEventVariablesFor(10, profiles, listOf(shared, caller("save SHARE"))))
        assertEquals("a name built at run time may be this task", DEFAULT_EDITOR_EVENT_VARIABLES, editorEventVariablesFor(10, profiles, listOf(shared, caller("%target"))))
        assertEquals(listed("share"), editorEventVariablesFor(10, profiles, listOf(shared, caller("Other"))).map { it.name }.toSet())
        assertEquals("a step turned off runs nothing", listed("share"), editorEventVariablesFor(10, profiles, listOf(shared, caller("10", enabled = false))).map { it.name }.toSet())
    }

    @Test
    fun anEventContextWithNoEventNamedFiresOnAnyEventSoKeepsEveryChip() {
        val profiles = listOf(Profile(id = 1, name = "Any", contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to " "))), enterTaskId = 10))
        val unnamed = listOf(Profile(id = 1, name = "Any", contexts = listOf(ContextSpec(ContextType.EVENT, emptyMap())), enterTaskId = 10))

        assertEquals(DEFAULT_EDITOR_EVENT_VARIABLES, editorEventVariablesFor(10, profiles))
        assertEquals(DEFAULT_EDITOR_EVENT_VARIABLES, editorEventVariablesFor(10, unnamed))
    }
}
