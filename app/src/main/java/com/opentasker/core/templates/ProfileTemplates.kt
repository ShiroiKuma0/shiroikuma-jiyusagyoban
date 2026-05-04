package com.opentasker.core.templates

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

enum class TemplateAvailability {
    Ready,
    RequiresSetup,
    Planned,
}

data class TemplateSlot(
    val key: String,
    val label: String,
    val defaultValue: String,
    val required: Boolean = true,
    val hint: String? = null,
)

data class TemplateAction(
    val type: String,
    val label: String,
    val args: Map<String, String> = emptyMap(),
)

data class TemplateContext(
    val type: ContextType,
    val config: Map<String, String> = emptyMap(),
    val invert: Boolean = false,
)

data class AppliedProfileTemplate(
    val task: Task,
    val profile: Profile,
)

data class ProfileTemplate(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val availability: TemplateAvailability,
    val safetyNote: String,
    val slots: List<TemplateSlot>,
    val contexts: List<TemplateContext>,
    val actions: List<TemplateAction>,
    val enabledByDefault: Boolean = false,
) {
    val installable: Boolean
        get() = availability != TemplateAvailability.Planned

    fun defaults(): Map<String, String> = slots.associate { it.key to it.defaultValue }

    fun instantiate(slotValues: Map<String, String>): AppliedProfileTemplate {
        require(installable) { "Template '$title' is not installable yet." }

        val values = defaults() + slotValues.mapValues { it.value.trim() }
        val missing = slots.filter { it.required && values[it.key].isNullOrBlank() }
        require(missing.isEmpty()) {
            "Missing template values: ${missing.joinToString { it.label }}"
        }

        val taskName = expand("$title Task", values)
        val task = Task(
            name = taskName,
            actions = actions.map { action ->
                ActionSpec(
                    type = action.type,
                    label = expand(action.label, values),
                    args = action.args.mapValues { (_, value) -> expand(value, values) },
                )
            },
        )
        val profile = Profile(
            name = expand(title, values),
            enabled = enabledByDefault,
            enterTaskId = 0,
            contexts = contexts.map { context ->
                ContextSpec(
                    type = context.type,
                    config = context.config.mapValues { (_, value) -> expand(value, values) },
                    invert = context.invert,
                )
            },
        )
        return AppliedProfileTemplate(task = task, profile = profile)
    }

    private fun expand(value: String, slotValues: Map<String, String>): String {
        var expanded = value
        repeat(2) {
            expanded = slotValues.entries.fold(expanded) { current, (key, replacement) ->
                current.replace("{$key}", replacement)
            }
        }
        return expanded
    }
}

object ProfileTemplateCatalog {
    val all: List<ProfileTemplate> = listOf(
        ProfileTemplate(
            id = "work-hours-focus",
            title = "Work-hours focus",
            summary = "Lower notification volume and log when the work window starts.",
            category = "Focus",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile. Review DND/volume access before enabling.",
            slots = listOf(
                TemplateSlot("start", "Start time", "09:00", hint = "HH:mm"),
                TemplateSlot("end", "End time", "17:00", hint = "HH:mm"),
                TemplateSlot("level", "Notification volume", "20", hint = "0-100"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.TIME, mapOf("start" to "{start}", "end" to "{end}")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Lower notification volume", mapOf("stream" to "notification", "level" to "{level}")),
                TemplateAction("log", "Log focus profile", mapOf("message" to "Work-hours focus active from {start} to {end}")),
            ),
        ),
        ProfileTemplate(
            id = "headphones-media",
            title = "Headphones media",
            summary = "Set a safe media volume when headphones connect.",
            category = "Media",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and may need DND/volume access.",
            slots = listOf(
                TemplateSlot("level", "Media volume", "55", hint = "0-100"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.STATE, mapOf("key" to "headphones", "value" to "true")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Set media volume", mapOf("stream" to "music", "level" to "{level}")),
                TemplateAction("log", "Log headphones profile", mapOf("message" to "Headphones connected; media volume set to {level}")),
            ),
        ),
        ProfileTemplate(
            id = "low-battery-saver",
            title = "Low-battery saver",
            summary = "Dim brightness and log when battery drops under a chosen threshold.",
            category = "Battery",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and needs Write Settings access for brightness.",
            slots = listOf(
                TemplateSlot("threshold", "Battery threshold", "20", hint = "Percent"),
                TemplateSlot("brightness", "Brightness", "48", hint = "0-255"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.STATE, mapOf("key" to "battery_level", "operator" to "<=", "value" to "{threshold}")),
            ),
            actions = listOf(
                TemplateAction("brightness.set", "Dim screen", mapOf("level" to "{brightness}")),
                TemplateAction("log", "Log low battery", mapOf("message" to "Battery saver template triggered below {threshold}%")),
            ),
        ),
        ProfileTemplate(
            id = "wifi-arrival",
            title = "WiFi arrival",
            summary = "Run a notification/log pattern when a trusted SSID is detected.",
            category = "Location-lite",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and may need nearby WiFi/location permission.",
            slots = listOf(
                TemplateSlot("ssid", "WiFi SSID", "Home WiFi", hint = "Exact SSID"),
                TemplateSlot("message", "Message", "Arrived on {ssid}"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.STATE, mapOf("key" to "wifi", "value" to "{ssid}")),
            ),
            actions = listOf(
                TemplateAction("notify.show", "Show arrival notification", mapOf("title" to "OpenTasker", "text" to "{message}")),
                TemplateAction("log", "Log WiFi arrival", mapOf("message" to "{message}")),
            ),
        ),
        ProfileTemplate(
            id = "app-usage-reminder",
            title = "App usage reminder",
            summary = "Wait after an app opens, then show a reminder notification.",
            category = "Habits",
            availability = TemplateAvailability.RequiresSetup,
            safetyNote = "Creates a disabled profile and requires Usage Access plus notification permission.",
            slots = listOf(
                TemplateSlot("package", "App package", "com.android.chrome", hint = "com.example.app"),
                TemplateSlot("delayMillis", "Delay milliseconds", "900000", hint = "900000 = 15 minutes"),
                TemplateSlot("message", "Reminder", "Time check: {package} has been open long enough for a break."),
            ),
            contexts = listOf(
                TemplateContext(ContextType.APPLICATION, mapOf("package" to "{package}")),
            ),
            actions = listOf(
                TemplateAction("flow.wait", "Wait before reminder", mapOf("millis" to "{delayMillis}")),
                TemplateAction("notify.show", "Show app reminder", mapOf("title" to "OpenTasker reminder", "text" to "{message}")),
            ),
        ),
        ProfileTemplate(
            id = "find-my-phone",
            title = "Find my phone",
            summary = "Pattern for a future external trigger that vibrates and raises volume.",
            category = "Safety",
            availability = TemplateAvailability.Planned,
            safetyNote = "Blocked until external trigger intents are exposed safely.",
            slots = listOf(
                TemplateSlot("trigger", "Trigger action", "com.opentasker.intent.FIND_PHONE"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.EVENT, mapOf("event" to "intent", "filter" to "{trigger}")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Raise media volume", mapOf("stream" to "music", "level" to "100")),
                TemplateAction("vibrate", "Vibrate", mapOf("millis" to "1500")),
                TemplateAction("notify.show", "Show finder alert", mapOf("title" to "Find my phone", "text" to "Finder pattern triggered")),
            ),
        ),
        ProfileTemplate(
            id = "meeting-mode-calendar",
            title = "Meeting mode from calendar",
            summary = "Calendar-driven focus template planned for CalendarProvider context support.",
            category = "Calendar",
            availability = TemplateAvailability.Planned,
            safetyNote = "Blocked until calendar context support and permission copy are implemented.",
            slots = listOf(
                TemplateSlot("calendar", "Calendar name", "Work"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.EVENT, mapOf("event" to "calendar", "filter" to "{calendar}")),
            ),
            actions = listOf(
                TemplateAction("volume.set", "Lower notification volume", mapOf("stream" to "notification", "level" to "0")),
            ),
        ),
        ProfileTemplate(
            id = "nightstand-nfc-sleep",
            title = "Nightstand NFC sleep mode",
            summary = "NFC-tag sleep routine planned for NFC trigger support.",
            category = "Sleep",
            availability = TemplateAvailability.Planned,
            safetyNote = "Blocked until NFC trigger support is implemented.",
            slots = listOf(
                TemplateSlot("tag", "NFC tag label", "Nightstand"),
            ),
            contexts = listOf(
                TemplateContext(ContextType.EVENT, mapOf("event" to "nfc", "filter" to "{tag}")),
            ),
            actions = listOf(
                TemplateAction("brightness.set", "Dim screen", mapOf("level" to "24")),
                TemplateAction("volume.set", "Lower media volume", mapOf("stream" to "music", "level" to "15")),
            ),
        ),
    )

    fun get(id: String): ProfileTemplate? = all.firstOrNull { it.id == id }
}
