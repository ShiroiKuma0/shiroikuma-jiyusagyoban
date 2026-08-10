package com.opentasker.core.capabilities

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

/**
 * The smallest set of platform capabilities required by enabled automations.
 *
 * This stays pure so Setup can be tested without an Android device. Unknown action/context
 * payloads conservatively keep their family requirement; an empty workspace produces no
 * automation-specific requirements.
 */
enum class SetupRequirement {
    USAGE_ACCESS,
    NOTIFICATION_ACCESS,
    CALENDAR,
    OVERLAY,
    WRITE_SETTINGS,
    FOREGROUND_LOCATION,
    BACKGROUND_LOCATION,
    NEARBY_WIFI,
    BLUETOOTH,
    LOCAL_NETWORK,
    SMS,
    DND,
    CONTACTS,
    SCREEN_RECORDING,
    PHYSICAL_ACTIVITY,
    PHONE_STATE,
}

object SetupRequirementResolver {
    fun resolve(profiles: List<Profile>, tasks: List<Task>): Set<SetupRequirement> {
        val enabled = profiles.filter { it.enabled && !it.requiresRiskAcknowledgement }
        if (enabled.isEmpty()) return emptySet()

        val requirements = linkedSetOf<SetupRequirement>()
        enabled.flatMap { it.contexts }.forEach { context ->
            requirements += contextRequirements(context)
        }

        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.trim().lowercase() }
        val visited = hashSetOf<Long>()
        val queue = ArrayDeque<Task>()
        enabled.flatMap { listOfNotNull(it.enterTaskId, it.exitTaskId) }
            .mapNotNullTo(queue) { byId[it] }
        while (queue.isNotEmpty()) {
            val task = queue.removeFirst()
            if (!visited.add(task.id)) continue
            task.actions.forEach { action ->
                requirements += actionRequirements(action.type)
                if (action.type != "task.run") return@forEach
                val reference = listOf("task", "name", "id")
                    .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                    ?: return@forEach
                val targets = reference.toLongOrNull()?.let { listOfNotNull(byId[it]) }
                    ?: byName[reference.lowercase()].orEmpty()
                queue.addAll(targets)
            }
        }
        return requirements
    }

    private fun contextRequirements(context: ContextSpec): Set<SetupRequirement> {
        val tokens = context.config.entries
            .flatMap { (key, value) -> listOf(key, value) }
            .joinToString(" ")
            .lowercase()
        return buildSet {
            when (context.type) {
                ContextType.APPLICATION -> add(SetupRequirement.USAGE_ACCESS)
                ContextType.LOCATION -> {
                    add(SetupRequirement.FOREGROUND_LOCATION)
                    add(SetupRequirement.BACKGROUND_LOCATION)
                }
                ContextType.STATE -> {
                    if (tokens.containsAny("wifi", "ssid", "network")) add(SetupRequirement.NEARBY_WIFI)
                    if (tokens.containsAny("bluetooth", "bt")) add(SetupRequirement.BLUETOOTH)
                    if (tokens.containsAny("activity", "physical_activity", "motion")) {
                        add(SetupRequirement.PHYSICAL_ACTIVITY)
                    }
                    if (tokens.containsAny("speed", "velocity")) add(SetupRequirement.FOREGROUND_LOCATION)
                    if (tokens.containsAny("roaming", "phone_call", "call_state", "phone", "call")) {
                        add(SetupRequirement.PHONE_STATE)
                    }
                }
                ContextType.EVENT -> when {
                    tokens.contains("screen_recording") -> add(SetupRequirement.SCREEN_RECORDING)
                    tokens.contains("sms_received") -> add(SetupRequirement.SMS)
                    tokens.containsAny("notification", "notify") -> add(SetupRequirement.NOTIFICATION_ACCESS)
                    tokens.contains("calendar") -> add(SetupRequirement.CALENDAR)
                }
                ContextType.PLUGIN,
                ContextType.TIME,
                ContextType.DAY,
                -> Unit
            }
        }
    }

    private fun actionRequirements(type: String): Set<SetupRequirement> = buildSet {
        when (type) {
            "brightness.set", "screen.timeout" -> add(SetupRequirement.WRITE_SETTINGS)
            "sms.send" -> add(SetupRequirement.SMS)
            "contacts.lookup" -> add(SetupRequirement.CONTACTS)
            "dnd.set", "zen.rule.set", "zen.rule.clear", "ringer.set", "volume.set", "media.mute" -> add(SetupRequirement.DND)
            "http.request", "http.get", "http.post", "integration.home_assistant.webhook", "mqtt.publish", "ping", "download", "wol" -> add(SetupRequirement.LOCAL_NETWORK)
            "script.termux.run" -> Unit // Termux is an optional integration, not a permission blocker.
            "scene.show", "scene.overlay" -> add(SetupRequirement.OVERLAY)
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}
