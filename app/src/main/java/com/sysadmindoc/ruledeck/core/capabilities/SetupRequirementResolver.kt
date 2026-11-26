package com.sysadmindoc.ruledeck.core.capabilities

import com.sysadmindoc.ruledeck.core.contexts.BT_NEAR_STATE_KEY
import com.sysadmindoc.ruledeck.core.contexts.stateContextKey
import com.sysadmindoc.ruledeck.core.contexts.stateSpecReadsWifiSsid
import com.sysadmindoc.ruledeck.core.health.HealthMetric
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.platform.AndroidAudioHardening

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
    CALENDAR_WRITE,
    OVERLAY,
    WRITE_SETTINGS,
    DEVICE_ADMIN,
    FOREGROUND_LOCATION,
    BACKGROUND_LOCATION,
    NEARBY_WIFI,
    BLUETOOTH,
    BLUETOOTH_SCAN,
    LOCAL_NETWORK,
    SMS,
    DND,
    CONTACTS,
    SCREEN_RECORDING,
    PHYSICAL_ACTIVITY,
    PHONE_STATE,
    CALL_LOG,
    SMS_RECEIVE,
    POST_NOTIFICATIONS,
    SECURE_SETTINGS,
    NFC_TAGS,
    HEALTH_CONNECT,
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
            // A step turned off never runs, so it needs nothing granted.
            task.actions.filter(ActionSpec::enabled).forEach { action ->
                requirements += actionRequirements(action.type, action.args)
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

    /**
     * What a template needs, before any of it exists as a stored profile. Onboarding uses this to
     * point Setup at the grants a freshly installed template is waiting on.
     *
     * Contexts matter as much as actions here. A calendar trigger that only turns the volume down
     * needs calendar access, and an app-usage trigger needs usage access, while neither action
     * requires anything at all. Reading actions alone sent exactly those templates to Profiles, or
     * worse, showed a checklist that omitted the one grant the trigger depended on.
     */
    fun resolveForTemplate(
        actionTypes: List<String>,
        contexts: List<ContextSpec>,
    ): Set<SetupRequirement> {
        val requirements = linkedSetOf<SetupRequirement>()
        contexts.forEach { context -> requirements += contextRequirements(context) }
        actionTypes.forEach { type -> requirements += actionRequirements(type) }
        return requirements
    }

    /**
     * What one action needs from Setup, so a row can say whether it's still waiting on a grant.
     * Empty means no Setup row can settle it, and the caller keeps its pill.
     */
    fun forAction(action: ActionSpec, sdkInt: Int): Set<SetupRequirement> = when {
        // Android 17 caps what an app can do to audio, and no grant lifts that.
        action.type in RESTRICTED_AUDIO_ACTIONS && AndroidAudioHardening.isRestricted(sdkInt) -> emptySet()
        // Only the pill reads this. Adding it to the profile requirements would send every
        // notification template to Setup, for a grant the engine row already asks for.
        action.type in NOTIFICATION_ACTIONS -> setOf(SetupRequirement.POST_NOTIFICATIONS)
        // Android 12 needs Bluetooth connect for this; from 13 on the action is unsupported and keeps
        // its pill whatever this says. Pill only, so the row isn't listed as needed on 13+.
        action.type == "bluetooth.toggle" -> setOf(SetupRequirement.BLUETOOTH)
        else -> actionRequirements(action.type, action.args)
    }

    /**
     * Whether an action that can need setup still does, given what Setup lists as missing. Null
     * means the grants haven't been read yet, so it still does.
     */
    fun stillNeedsSetup(action: ActionSpec, sdkInt: Int, pending: Set<SetupRequirement>?): Boolean {
        val required = forAction(action, sdkInt)
        // Typed coordinates leave a sun.times step nothing to ask for, so its pill goes.
        if (required.isEmpty() && action.type in NEEDS_SETUP_ONLY_FOR_SOME_ARGS) return false
        return pending == null || required.isEmpty() || required.any { it in pending }
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
                    val key = stateContextKey(context)
                    if (key == BT_NEAR_STATE_KEY) {
                        add(SetupRequirement.BLUETOOTH)
                        add(SetupRequirement.BLUETOOTH_SCAN)
                        return@buildSet
                    }
                    // Health Connect has its own permissions.
                    if (HealthMetric.forStateKey(key) != null) {
                        add(SetupRequirement.HEALTH_CONNECT)
                        return@buildSet
                    }
                    // Android withholds a network's name without precise location, and withholds it
                    // from a backgrounded app without "allow all the time", so an SSID profile that
                    // skipped these grants read Unknown forever (issue #17).
                    if (stateSpecReadsWifiSsid(context)) {
                        add(SetupRequirement.FOREGROUND_LOCATION)
                        add(SetupRequirement.BACKGROUND_LOCATION)
                    }
                    // Read from the key, never from words inside it or the value: "headphones" holds
                    // "phone", and a Wi-Fi network can be named "Phone Hotspot".
                    addAll(if (key != null) stateKeyRequirements(key) else stateWordRequirements(tokens))
                }
                ContextType.EVENT -> when {
                    // Read from the event key alone: a tag ID or another event's text could spell "nfc".
                    context.config["event"].equals("nfc", ignoreCase = true) -> add(SetupRequirement.NFC_TAGS)
                    // Android withholds a ringing call's number without the Call log permission, and
                    // only Contacts can say the caller is a stranger.
                    tokens.contains("incoming_call") -> {
                        add(SetupRequirement.PHONE_STATE)
                        add(SetupRequirement.CALL_LOG)
                        add(SetupRequirement.CONTACTS)
                    }
                    tokens.contains("screen_recording") -> add(SetupRequirement.SCREEN_RECORDING)
                    tokens.contains("sms_received") -> add(SetupRequirement.SMS_RECEIVE)
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

    private fun actionRequirements(
        type: String,
        args: Map<String, String> = emptyMap(),
    ): Set<SetupRequirement> = buildSet {
        when (type) {
            "brightness.set", "screen.timeout" -> add(SetupRequirement.WRITE_SETTINGS)
            "settings.write" -> when (args["table"]?.trim()?.lowercase()) {
                "system" -> add(SetupRequirement.WRITE_SETTINGS)
                "global", "secure" -> add(SetupRequirement.SECURE_SETTINGS)
            }
            // A profile scans from the background, where Android denies the scan results without
            // "allow all the time", the same as an SSID context (#17).
            "wifi.scan" -> {
                add(SetupRequirement.FOREGROUND_LOCATION)
                add(SetupRequirement.BACKGROUND_LOCATION)
                add(SetupRequirement.NEARBY_WIFI)
            }
            // Only a coordinate that isn't a number comes from the phone's location, and a profile
            // asks for it from the background.
            "sun.times" -> if (args["latitude"]?.trim()?.toDoubleOrNull() == null || args["longitude"]?.trim()?.toDoubleOrNull() == null) {
                add(SetupRequirement.FOREGROUND_LOCATION)
                add(SetupRequirement.BACKGROUND_LOCATION)
            }
            "location.get" -> {
                add(SetupRequirement.FOREGROUND_LOCATION)
                add(SetupRequirement.BACKGROUND_LOCATION)
            }
            "lock" -> add(SetupRequirement.DEVICE_ADMIN)
            // A profile runs in the background, where Android silently drops an activity start
            // unless the app can draw over others.
            "url.open", "app.launch", "home.go", "intent.launch" -> add(SetupRequirement.OVERLAY)
            "sms.send" -> add(SetupRequirement.SMS)
            "contacts.lookup" -> add(SetupRequirement.CONTACTS)
            "calendar.event.add" -> add(SetupRequirement.CALENDAR_WRITE)
            "calendar.event.query" -> add(SetupRequirement.CALENDAR)
            "dnd.set", "zen.rule.set", "zen.rule.clear", "ringer.set", "volume.set", "media.mute" -> add(SetupRequirement.DND)
            "http.request", "http.get", "http.post", "integration.home_assistant.webhook", "mqtt.publish", "ping", "download", "wol" -> add(SetupRequirement.LOCAL_NETWORK)
            // Termux and Shizuku are optional integrations, not permission blockers.
            "script.termux.run", "script.shizuku.run" -> Unit
            "scene.show", "scene.overlay" -> add(SetupRequirement.OVERLAY)
        }
    }

    /** What a state context's key reads, already through [stateContextKey]'s spellings. */
    private fun stateKeyRequirements(key: String): Set<SetupRequirement> = when {
        key == "wifi" || key.startsWith("wifi_") -> setOf(SetupRequirement.NEARBY_WIFI)
        key == "activity" -> setOf(SetupRequirement.PHYSICAL_ACTIVITY)
        key == "speed" -> setOf(SetupRequirement.FOREGROUND_LOCATION)
        key == "roaming" || key == "call_state" -> setOf(SetupRequirement.PHONE_STATE)
        key.startsWith("bluetooth") || key.startsWith("bt_") -> setOf(SetupRequirement.BLUETOOTH)
        else -> emptySet()
    }

    /** A context with no key to read keeps its family's grant, matched by whole words only. */
    private fun stateWordRequirements(tokens: String): Set<SetupRequirement> {
        val words = tokens.split(NON_WORD).filter(String::isNotEmpty).toSet()
        fun any(vararg values: String) = values.any { it in words }
        return buildSet {
            if (any("wifi", "ssid", "network")) add(SetupRequirement.NEARBY_WIFI)
            if (any("bluetooth", "bt")) add(SetupRequirement.BLUETOOTH)
            if (any("activity", "physical_activity", "motion")) add(SetupRequirement.PHYSICAL_ACTIVITY)
            if (any("speed", "velocity")) add(SetupRequirement.FOREGROUND_LOCATION)
            if (any("roaming", "phone_call", "call_state", "phone", "call")) add(SetupRequirement.PHONE_STATE)
        }
    }

    private val NON_WORD = Regex("[^a-z0-9_]+")

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

    private val RESTRICTED_AUDIO_ACTIONS = setOf("volume.set", "ringer.set", "media.mute")
    private val NOTIFICATION_ACTIONS = setOf("notify.show", "notify.cancel", "notify.progress")
    private val NEEDS_SETUP_ONLY_FOR_SOME_ARGS = setOf("sun.times")
}
