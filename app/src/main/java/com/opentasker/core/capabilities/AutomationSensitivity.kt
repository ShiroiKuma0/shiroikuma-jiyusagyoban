package com.opentasker.core.capabilities

import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import kotlinx.serialization.Serializable

/** User-reviewable powers that an action can exercise under OpenTasker's app-wide grants. */
@Serializable
enum class AutomationPower {
    DATA_ACCESS,
    EXTERNAL_TRANSMISSION,
    DEVICE_CONTROL,
    DESTRUCTIVE,
}

data class ActionSensitivityClassification(
    val actionId: String,
    val powers: Set<AutomationPower>,
    val known: Boolean,
)

data class DataToExternalChain(
    val sourceActionId: String,
    val sinkActionId: String,
)

data class AutomationRiskSummary(
    val powers: Set<AutomationPower>,
    val sensitiveActionIds: Set<String>,
    val unknownActionIds: Set<String>,
    val dataToExternalChains: List<DataToExternalChain>,
)

/**
 * Explicit sensitivity registry for every built-in action. There is intentionally no permissive
 * default: an action absent from these sets is treated as unknown and receives every power until
 * it is reviewed and classified.
 */
object AutomationSensitivityRegistry {
    private val localOnlyActionIds = setOf(
        "var.set",
        "var.persist",
        "data.read",
        "datetime.format",
        "datetime.parse",
        "datetime.add",
        "text.match",
        "text.replace",
        "text.split",
        "text.join",
        "text.substring",
        "flow.wait",
        "task.run",
        "flow.if",
        "flow.else",
        "flow.endif",
        "flow.foreach",
        "flow.endfor",
        "flow.try",
        "flow.catch",
        "flow.endtry",
        "flow.stop",
        "tasker.unsupported",
        "macrodroid.unsupported",
        "log",
        // Fork actions (白い熊 自由作業盤). Classified local-only deliberately: this catalog gates the
        // imported-profile risk dialog and the unknown-action fail-closed path, and fork bundles are
        // authored locally — leaving fork ids out would classify them "unknown" and block every task.
        "app.freeze",
        "app.frozen",
        "app.pick",
        "backup.categories",
        "backup.edititems",
        "dialog.pickmulti",
        "flow.fail",
        "system.get_locale",
        "task.return",
        "backup.plan",
        "backup.runitems",
        "app.next",
        "app.pickmulti",
        "app.previous",
        "app.unfreeze",
        "apps.list",
        "array.clear",
        "array.merge",
        "array.pop",
        "array.process",
        "array.push",
        "array.set",
        "audio.record.start",
        "audio.record.stop",
        "brightness.auto",
        "bubble.flash_add",
        "bubble.flash_clear",
        "bubble.flash_remove",
        "bubble.flashkill_hide",
        "bubble.flashkill_show",
        "call.place",
        "clipboard.get",
        "clipboard.set",
        "datetime",
        "dialog.input",
        "dialog.list",
        "dialog.text",
        "email.compose",
        "file.mkdir",
        "file.move",
        "file.open",
        "flash",
        "flow.comment",
        "ime.pick",
        "ime.set",
        "intent.send",
        "location.mode",
        "media.playpause",
        "nav.back",
        "nav.power",
        "nav.recents",
        "nav.screenshot",
        "notify.dismiss",
        "panel.notifications",
        "panel.quicksettings",
        "power.off",
        "profile.toggle",
        "progress.finish",
        "progress.hide",
        "progress.item",
        "progress.row",
        "progress.show",
        "key.bindings",
        "scene.gestures",
        "scene.hide",
        "scene.show",
        "screen.lock",
        "share.relays",
        "screen.lockdown",
        "setting.get",
        "setting.put",
        "shell.run",
        "sim.data.set",
        "sim.list",
        "state.get",
        "location.get",
        "net.speedtest",
        "net.speedtest.cancel",
        "task.addaction",
        "task.editaction",
        "task.exists",
        "tasks.sort",
        "tasks.launchers",
        "var.add",
        "var.clear",
        "var.convert",
        "var.join",
        "var.replace",
        "var.split",
        "volume.get",
        "wallpaper.set",
        "wallpaper.live",
        "widget.refresh",
        "widget.set",
        "wifi.settings",
    )

    private val dataAccessActionIds = setOf(
        // Reads personal health history off the band — heart rate, sleep, blood pressure.
        "band.sync",
        // The same, from the second band. A separate declaration because it is a separate body of
        // personal history in separate tables, not a variant of the entry above.
        "huawei.sync",
        // Puts that history on screen. It transmits nothing and displays a body's worth of it.
        "huawei.charts",
        "huawei.board",
        // Puts that same health history on screen. It transmits nothing, but it displays a body's
        // worth of it, which is the thing worth declaring.
        "band.charts",
        // Records that 白い熊 trained, and when. It reads nothing and transmits nothing, but a log of
        // when someone exercises is personal history and belongs in the same declaration.
        "band.session",
        "clipboard.get",
        "contacts.lookup",
        "plugin.locale.query",
        "script.termux.run",
        // Turns an image into text on-device. It transmits nothing and needs no permission, but a
        // screenshot can hold a message, an address or a one-time code, so reading one is data access.
        "ocr.models",
        "ocr.recognize",
        // Reads a whole article out of a screenshot. Same reasoning as ocr.recognize and rather more
        // of it: what comes out is not a phrase but the entire page, photographs included.
        "ocr.article",
        "screenshot.take",
        "file.read",
        "file.list",
    )

    private val externalTransmissionActionIds = setOf(
        "intent.launch",
        "plugin.locale.fire",
        "plugin.locale.query",
        "script.termux.run",
        "app.launch",
        "url.open",
        "sms.send",
        "http.request",
        "http.get",
        "http.post",
        "integration.home_assistant.webhook",
        "mqtt.publish",
        "ping",
        "download",
        "wol",
        // Downloads about 25 MB from six public science mirrors on every run. Nothing is uploaded
        // and nothing personal is read, but a bundle that reaches six hosts unprompted is exactly
        // the kind of thing an import should be told about.
        "huawei.pgnss",
    )

    private val deviceControlActionIds = setOf(
        // Drives the Bluetooth radio and connects to a paired-free peripheral.
        "band.sync",
        // Drives the same radio over Bluetooth Classic instead, to a band that IS paired.
        "huawei.sync",
        // Same radio, asking the band about itself rather than for its data.
        "huawei.probe",
        // Reads the band's stored sleep and RR-interval files — health data, not device control.
        "huawei.files",
        // WRITES to the band — the only Huawei action that changes what is stored on it.
        "huawei.time",
        "huawei.watchface",
        // Changes what the band RECORDS — the most consequential of the write actions, because
        // switching a recorder off loses data that cannot be recovered afterwards.
        "huawei.settings",
        // Writes to the band's display only.
        "huawei.weather",
        // Writes satellite assistance data the band cannot fetch itself. Device control rather than
        // data access: nothing personal is read, and nothing leaves the phone — the files served
        // are ones already on disk, never the URL the band asks us to fetch.
        "huawei.gnss",
        // Presses controls in OTHER apps. Device control in a fairly direct sense: it can drive
        // any interface the accessibility service can see.
        "ui.click",
        "huawei.workouts",
        "band.compare",
        // Drops a bond and a credential — device control in its most consequential form here.
        "huawei.unpair",
        // Bonds a new peripheral to the phone outright, which is the strongest form of this.
        "huawei.pair",
        // Changes what the band displays. Device control, though the mildest kind here: it reads
        // no data and the worst outcome is a wrist reading the wrong language.
        "huawei.language",
        // Drives the same radio, listening rather than reading: it enumerates every BLE device in
        // range and may connect to a few to identify them. Device control, not data access — it
        // reads no health history, and the addresses it learns are broadcast to the whole room.
        "band.scan",
        "backup.prune",
        "clipboard.set",
        "notify.show",
        "notify.cancel",
        "notify.progress",
        "tts.speak",
        "intent.launch",
        "plugin.locale.fire",
        "plugin.locale.query",
        "script.termux.run",
        "wifi.toggle",
        "bluetooth.toggle",
        "brightness.set",
        "volume.set",
        "airplane.toggle",
        "aod.set",
        "wifi.scan",
        "mobile.toggle",
        "screen.timeout",
        "system.set_locale",
        "dnd.set",
        "zen.rule.set",
        "zen.rule.clear",
        "ringer.set",
        "torch.set",
        "tile.set",
        "state.temporary",
        "ime.info",
        "ime.set",
        "app.launch",
        "shortcut.publish",
        "app.kill",
        "app.archive",
        "app.unarchive",
        "home.go",
        "url.open",
        "sms.send",
        "screenshot.take",
        "file.write",
        "file.append",
        "file.delete",
        // Writes the HTML it produced into a folder of 白い熊's choosing. Not classed destructive: the
        // filename leads with a to-the-second stamp, so a run cannot land on an earlier run's file.
        "ocr.article",
        "download",
        "wol",
        "sound.play",
        "sound.stop",
        "sound.pause",
        "track.next",
        "track.previous",
        "media.mute",
        "vibrate",
        "reboot",
        "lock",
        "screen.off",
        "wake",
    )

    private val destructiveActionIds = setOf(
        "backup.prune",
        "script.termux.run",
        "app.kill",
        "app.archive",
        "file.write",
        "file.delete",
        "download",
        "reboot",
    )

    private val explicitActionIds = localOnlyActionIds +
        dataAccessActionIds +
        externalTransmissionActionIds +
        deviceControlActionIds +
        destructiveActionIds

    fun classifiedActionIds(): Set<String> = explicitActionIds

    fun isKnown(actionId: String): Boolean = actionId in explicitActionIds

    fun classify(actionId: String): ActionSensitivityClassification {
        if (!isKnown(actionId)) {
            return ActionSensitivityClassification(
                actionId = actionId,
                powers = AutomationPower.entries.toSet(),
                known = false,
            )
        }
        return ActionSensitivityClassification(
            actionId = actionId,
            powers = buildSet {
                if (actionId in dataAccessActionIds) add(AutomationPower.DATA_ACCESS)
                if (actionId in externalTransmissionActionIds) add(AutomationPower.EXTERNAL_TRANSMISSION)
                if (actionId in deviceControlActionIds) add(AutomationPower.DEVICE_CONTROL)
                if (actionId in destructiveActionIds) add(AutomationPower.DESTRUCTIVE)
            },
            known = true,
        )
    }

    fun summarize(task: Task): AutomationRiskSummary {
        val powers = linkedSetOf<AutomationPower>()
        val sensitiveActionIds = linkedSetOf<String>()
        val unknownActionIds = linkedSetOf<String>()
        val chains = linkedSetOf<DataToExternalChain>()
        var latestDataSource: String? = null

        task.actions.forEach { action ->
            val classification = classify(action.type)
            powers += classification.powers
            if (classification.powers.isNotEmpty()) sensitiveActionIds += action.type
            if (!classification.known) unknownActionIds += action.type

            val readsData = AutomationPower.DATA_ACCESS in classification.powers
            val transmits = AutomationPower.EXTERNAL_TRANSMISSION in classification.powers
            if (readsData) latestDataSource = action.type
            if (transmits && latestDataSource != null) {
                chains += DataToExternalChain(latestDataSource, action.type)
            }
        }

        return AutomationRiskSummary(powers, sensitiveActionIds, unknownActionIds, chains.toList())
    }

    fun reachableTasks(profile: Profile, tasks: List<Task>): List<Task> {
        val byId = tasks.associateBy(Task::id)
        val byName = tasks.groupBy { it.name.lowercase() }
        val queued = ArrayDeque<Task>()
        val visited = linkedSetOf<Long>()

        listOfNotNull(profile.enterTaskId, profile.exitTaskId).forEach { taskId ->
            byId[taskId]?.let(queued::addLast)
        }
        while (queued.isNotEmpty()) {
            val task = queued.removeFirst()
            if (!visited.add(task.id)) continue
            task.actions.filter { it.type == "task.run" }.forEach { action ->
                val reference = listOf("task", "name", "id")
                    .firstNotNullOfOrNull { key -> action.args[key]?.trim()?.takeIf(String::isNotBlank) }
                    ?: return@forEach
                val targets = when {
                    '%' in reference || "{{" in reference -> tasks
                    reference.toLongOrNull() != null -> listOfNotNull(byId[reference.toLong()])
                    else -> byName[reference.lowercase()].orEmpty()
                }
                targets.forEach(queued::addLast)
            }
        }
        return visited.mapNotNull(byId::get)
    }

    fun summarize(profile: Profile, tasks: List<Task>): AutomationRiskSummary {
        val summaries = reachableTasks(profile, tasks).map(::summarize)
        val powers = summaries.flatMapTo(linkedSetOf()) { it.powers }
        val sensitiveActionIds = summaries.flatMapTo(linkedSetOf()) { it.sensitiveActionIds }
        val unknownActionIds = summaries.flatMapTo(linkedSetOf()) { it.unknownActionIds }
        val chains = summaries.flatMapTo(linkedSetOf()) { it.dataToExternalChains }

        if (
            chains.isEmpty() &&
            AutomationPower.DATA_ACCESS in powers &&
            AutomationPower.EXTERNAL_TRANSMISSION in powers
        ) {
            val source = sensitiveActionIds.firstOrNull { AutomationPower.DATA_ACCESS in classify(it).powers }
            val sink = sensitiveActionIds.firstOrNull { AutomationPower.EXTERNAL_TRANSMISSION in classify(it).powers }
            if (source != null && sink != null) chains += DataToExternalChain(source, sink)
        }
        return AutomationRiskSummary(powers, sensitiveActionIds, unknownActionIds, chains.toList())
    }
}

data class ImportedProfileEnableReview(
    val risk: AutomationRiskSummary,
    val feedbackLoopRisks: List<FeedbackLoopRisk>,
    val lintFindings: List<AutomationLintFinding> = emptyList(),
    val unsupportedActionIds: Set<String>,
    val missingTaskIds: Set<Long>,
    val requiresAcknowledgement: Boolean,
) {
    val canAcknowledge: Boolean
        get() = unsupportedActionIds.isEmpty() &&
            risk.unknownActionIds.isEmpty() &&
            missingTaskIds.isEmpty() &&
            lintFindings.none { it.severity == AutomationLintSeverity.BLOCKING }
}

object ImportedProfileEnablePolicy {
    fun review(
        profile: Profile,
        tasks: List<Task>,
        otherProfiles: List<Profile> = emptyList(),
        strings: AutomationLintStrings = AutomationLintStrings.English,
    ): ImportedProfileEnableReview {
        val reachable = AutomationSensitivityRegistry.reachableTasks(profile, tasks)
        val unsupported = reachable
            .flatMap { it.actions }
            .map { it.type }
            .filterNot { ActionCapabilityRegistry.get(it).canAdd }
            .toSortedSet()
        val taskIds = tasks.mapTo(hashSetOf(), Task::id)
        val missingTaskIds = listOfNotNull(profile.enterTaskId, profile.exitTaskId)
            .filterNot(taskIds::contains)
            .toSortedSet()
        return ImportedProfileEnableReview(
            risk = AutomationSensitivityRegistry.summarize(profile, tasks),
            feedbackLoopRisks = AutomationFeedbackRiskAnalyzer.analyze(profile, tasks),
            lintFindings = AutomationLint.analyze(
                profile.copy(enabled = true),
                tasks,
                otherProfiles,
                strings,
            ).forProfile(profile.id),
            unsupportedActionIds = unsupported,
            missingTaskIds = missingTaskIds,
            requiresAcknowledgement = profile.requiresRiskAcknowledgement,
        )
    }
}
