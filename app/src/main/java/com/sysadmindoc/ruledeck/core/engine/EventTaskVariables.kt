package com.sysadmindoc.ruledeck.core.engine

import androidx.annotation.StringRes
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.actions.ActionValueType
import com.sysadmindoc.ruledeck.core.contexts.BroadcastContextEvents
import com.sysadmindoc.ruledeck.core.contexts.CallContextEvents
import com.sysadmindoc.ruledeck.core.contexts.ContextEvent
import com.opentasker.core.contexts.SmsContextEvents
import java.util.Locale

/**
 * One variable a matched event hands the task it starts: its name, its type for the variable
 * chips, a one-line meaning for the context editor, and how the engine reads it off the event.
 */
internal class EventVariable(
    val name: String,
    val type: ActionValueType,
    @StringRes val meaningRes: Int,
    val read: (Map<String, String>) -> String,
)

/**
 * The variables a matched event hands the task it starts. SMS and notification fields are listed
 * by name rather than forwarded wholesale, so a metadata key added for matching doesn't reach
 * tasks by accident. The same table feeds the context editor's list and the action editor's
 * chips, so neither can name a variable the engine doesn't set.
 */
internal object EventTaskVariables {
    private const val EVENT_SHARE = "share"
    private const val EVENT_NOTIFICATION = "notification"

    /**
     * Other people's words and numbers. An action still receives the real value, but the run log,
     * previews and held-trigger storage show a placeholder, and anything derived from it is
     * tainted the same way.
     */
    private val SENSITIVE = setOf(
        "sms_sender",
        "sms_body",
        "notification_title",
        "notification_body",
        "incoming_number",
    )

    private fun field(name: String, type: ActionValueType, @StringRes meaningRes: Int, key: String = name) =
        EventVariable(name, type, meaningRes) { it[key].orEmpty() }

    private val BY_EVENT: Map<String, List<EventVariable>> = mapOf(
        EVENT_SHARE to listOf(
            EventVariable("share_event", ActionValueType.BOOLEAN, R.string.event_variable_share_event) { "true" },
            field("share_text", ActionValueType.TEXT, R.string.event_variable_share_text, "text"),
            field("share_uri", ActionValueType.TEXT, R.string.event_variable_share_uri, "uri"),
            field("share_uris", ActionValueType.TEXT, R.string.event_variable_share_uris, "uris"),
            field("share_mime", ActionValueType.TEXT, R.string.event_variable_share_mime, "mime"),
            field("share_count", ActionValueType.NUMBER, R.string.event_variable_share_count, "count"),
            field("share_multiple", ActionValueType.BOOLEAN, R.string.event_variable_share_multiple, "multiple"),
        ),
        SmsContextEvents.EVENT_SMS_RECEIVED to listOf(
            field("sms_sender", ActionValueType.TEXT, R.string.event_variable_sms_sender, "sender"),
            field("sms_body", ActionValueType.TEXT, R.string.event_variable_sms_body, "body"),
            field("sms_kind", ActionValueType.TEXT, R.string.event_variable_sms_kind, "kind"),
            field("sms_message_count", ActionValueType.NUMBER, R.string.event_variable_sms_message_count, "messageCount"),
            field("sms_subscription_id", ActionValueType.TEXT, R.string.event_variable_sms_subscription_id, "subscriptionId"),
        ),
        EVENT_NOTIFICATION to listOf(
            field("notification_package", ActionValueType.TEXT, R.string.event_variable_notification_package, "package"),
            field("notification_title", ActionValueType.TEXT, R.string.event_variable_notification_title, "title"),
            field("notification_body", ActionValueType.TEXT, R.string.event_variable_notification_body, "body"),
        ),
        CallContextEvents.EVENT_INCOMING_CALL to listOf(
            field("incoming_number", ActionValueType.TEXT, R.string.event_variable_incoming_number, "number"),
            field("incoming_known_contact", ActionValueType.TEXT, R.string.event_variable_incoming_known_contact, "knownContact"),
        ),
        BroadcastContextEvents.EVENT_BROADCAST to listOf(
            field("broadcast_action", ActionValueType.TEXT, R.string.event_variable_broadcast_action),
            field("broadcast_sender", ActionValueType.TEXT, R.string.event_variable_broadcast_sender),
            field("broadcast_extra_count", ActionValueType.NUMBER, R.string.event_variable_broadcast_extra_count),
            EventVariable("broadcast_extras_lossy", ActionValueType.BOOLEAN, R.string.event_variable_broadcast_extras_lossy) {
                it["broadcast_extras_lossy"] ?: "false"
            },
        ),
    )

    /** Every event's variables, in the order the action editor's chips list them. */
    val all: List<EventVariable> = BY_EVENT.values.flatten()

    /**
     * What a task started by an event context configured with [event] can read. A broadcast's
     * extras are named by the sender, so only their count is listed here.
     */
    fun declaredFor(event: String?): List<EventVariable> =
        BY_EVENT[event?.trim()?.lowercase(Locale.ROOT)].orEmpty()

    fun isSensitive(name: String): Boolean = name in SENSITIVE

    fun from(event: ContextEvent?): Map<String, String> {
        val metadata = event?.metadata ?: return emptyMap()
        val name = metadata["event"]
        val declared = BY_EVENT[name] ?: return emptyMap()
        return buildMap {
            declared.forEach { put(it.name, it.read(metadata)) }
            if (name == BroadcastContextEvents.EVENT_BROADCAST) {
                // Already bounded and string-only when the event was built, so forwarding them
                // wholesale cannot introduce anything the sanitiser refused.
                metadata.forEach { (key, value) ->
                    if (key.startsWith("broadcast_extra_") && key != "broadcast_extra_count") put(key, value)
                }
            }
        }
    }
}
