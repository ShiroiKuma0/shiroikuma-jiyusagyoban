package com.opentasker.core.actions

import com.opentasker.core.capabilities.ActionCapability
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionRetrySafety

typealias DeclaredAction = com.opentasker.core.engine.DeclaredAction

/**
 * The single runtime catalogue for built-in actions.
 *
 * Each entry owns the stable action identity, runtime category, retry contract, implementation
 * factory, and capability resolver. UI metadata is registered against the same identity and is
 * exposed through [ActionDefinition.metadata], so the runtime, editor, capability, and release
 * count surfaces can no longer invent independent action lists.
 */
class ActionDefinition(
    val id: String,
    val category: ActionCategory,
    val retrySafety: ActionRetrySafety,
    val factory: () -> Action,
    val capability: () -> ActionCapability = { ActionCapabilityRegistry.resolveDeclared(id) },
) {
    private var boundMetadata: ActionMetadata? = null

    val metadata: ActionMetadata
        get() = requireNotNull(boundMetadata) {
            "No UI metadata declared for action $id"
        }

    internal fun bindMetadata(metadata: ActionMetadata) {
        require(metadata.id == id) {
            "Metadata id ${metadata.id} does not match action declaration $id"
        }
        boundMetadata = metadata
    }

    internal fun metadataOrNull(): ActionMetadata? = boundMetadata
}

object ActionCatalog {
    val all: List<ActionDefinition> = listOf(
        define("notify.show", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::NotifyAction),
        define("notify.cancel", ActionCategory.NOTIFICATION, ActionRetrySafety.IDEMPOTENT, ::NotifyCancelAction),
        define("notify.progress", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::ProgressNotificationAction),
        define("var.set", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::SetVariableAction),
        define("var.persist", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::PersistVariableAction),
        define("clipboard.get", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::GetClipboardAction),
        define("clipboard.set", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SetClipboardAction),
        define("contacts.lookup", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ContactsLookupAction),
        define("data.read", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DataReadAction),
        define("datetime.format", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeFormatAction),
        define("datetime.parse", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeParseAction),
        define("datetime.add", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeAddAction),
        define("text.match", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextMatchAction),
        define("text.replace", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextReplaceAction),
        define("text.split", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextSplitAction),
        define("text.join", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextJoinAction),
        define("text.substring", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::TextSubstringAction),
        define("tts.speak", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::SayAction),
        define("flow.wait", ActionCategory.FLOW, ActionRetrySafety.NEVER, ::WaitAction),
        define("intent.launch", ActionCategory.APP, ActionRetrySafety.NEVER, ::LaunchIntentAction),
        define("wifi.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::WiFiToggleAction),
        // IDEMPOTENT: it only reads. Retrying re-reads the same cache and rewrites the same
        // variables, and the platform decides whether a fresh scan happens either way.
        define("wifi.scan", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::WifiScanAction),
        define("bluetooth.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::BluetoothToggleAction),
        define("brightness.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::BrightnessAction),
        define("volume.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::VolumeAction),
        define("airplane.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::AirplaneModeAction),
        define("mobile.toggle", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::MobileDataAction),
        // NEVER, not IDEMPOTENT: "toggle" recomputes from the live value, so a retry flips back.
        define("aod.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::AlwaysOnDisplayAction),
        define("screen.timeout", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ScreenTimeoutAction),
        define("dnd.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::DoNotDisturbAction),
        define("zen.rule.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::ZenRuleSetAction),
        define("zen.rule.clear", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ZenRuleClearAction),
        define("ringer.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::RingerModeAction),
        define("torch.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::TorchAction),
        define("tile.set", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::TileStateAction),
        define("state.temporary", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::TemporaryStateAction),
        define("app.launch", ActionCategory.APP, ActionRetrySafety.NEVER, ::LaunchAppAction),
        define("app.archive", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::AppArchiveAction),
        define("app.unarchive", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::AppUnarchiveAction),
        define("shortcut.publish", ActionCategory.APP, ActionRetrySafety.NEVER, ::ShortcutPublishAction),
        define("plugin.locale.fire", ActionCategory.APP, ActionRetrySafety.NEVER, ::LocalePluginSettingAction),
        define("plugin.locale.query", ActionCategory.PLUGIN, ActionRetrySafety.IDEMPOTENT, ::LocalePluginConditionQueryAction),
        define("app.kill", ActionCategory.APP, ActionRetrySafety.NEVER, ::KillAppAction),
        define("home.go", ActionCategory.APP, ActionRetrySafety.NEVER, ::GoHomeAction),
        define("url.open", ActionCategory.APP, ActionRetrySafety.NEVER, ::OpenUrlAction),
        define("sms.send", ActionCategory.APP, ActionRetrySafety.NEVER, ::SendSmsAction),
        define("screenshot.take", ActionCategory.APP, ActionRetrySafety.NEVER, ::ScreenshotAction),
        define("file.read", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::ReadFileAction),
        define("file.write", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::WriteFileAction),
        define("file.append", ActionCategory.FILE, ActionRetrySafety.NEVER, ::AppendFileAction),
        define("file.delete", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::DeleteFileAction),
        define("file.list", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::ListFilesAction),
        define("http.request", ActionCategory.NET, ActionRetrySafety.NEVER, ::HttpRequestAction),
        define("http.get", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::HttpGetAction),
        define("http.post", ActionCategory.NET, ActionRetrySafety.NEVER, ::HttpPostAction),
        define("ime.info", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ImeInfoAction),
        define("ime.set", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::SetImeAction),
        define("integration.home_assistant.webhook", ActionCategory.NET, ActionRetrySafety.NEVER, ::HomeAssistantWebhookAction),
        define("mqtt.publish", ActionCategory.NET, ActionRetrySafety.NEVER, ::MqttPublishAction),
        define("ping", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::PingAction),
        define("download", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::DownloadAction),
        define("wol", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::WakeOnLanAction),
        define("sound.play", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::PlaySoundAction),
        define("sound.stop", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::StopSoundAction),
        define("sound.pause", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::PauseSoundAction),
        define("track.next", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::NextTrackAction),
        define("track.previous", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::PreviousTrackAction),
        define("media.mute", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::MuteAction),
        define("vibrate", ActionCategory.NOTIFICATION, ActionRetrySafety.NEVER, ::VibrateAction),
        define("reboot", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::RebootAction),
        define("lock", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::LockDeviceAction),
        define("screen.off", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::ScreenOffAction),
        define("wake", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::WakeAction),
        define("log", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::LogAction),
        define("script.termux.run", ActionCategory.PLUGIN, ActionRetrySafety.NEVER, ::TermuxScriptAction),
        define("tasker.unsupported", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::TaskerUnsupportedAction),
        define("macrodroid.unsupported", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::MacroDroidUnsupportedAction),

        // ── Fork actions (白い熊 自由作業盤) ─────────────────────────────────────────────────
        // Declared here for the same reason upstream's are: the catalogue is the single source
        // for the runtime factory, the editor metadata binding, retry safety and the release
        // action count. Retry safety is read conservatively — IDEMPOTENT only where repeating the
        // action converges on the same state (a read, or a write that sets rather than accumulates);
        // anything that sends, appends, opens a window, or asks the user is NEVER.
        define("notify.dismiss", ActionCategory.NOTIFICATION, ActionRetrySafety.IDEMPOTENT, ::NotifyDismissAction),
        define("intent.send", ActionCategory.APP, ActionRetrySafety.NEVER, ::SendIntentAction),
        define("volume.get", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::VolumeGetAction),
        define("state.get", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::StateGetAction),
        define("system.get_locale", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::GetLocaleAction),
        define("system.set_locale", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::SetLocaleAction),
        define("app.freeze", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::FreezeAppAction),
        define("app.unfreeze", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::UnfreezeAppAction),
        define("app.frozen", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::AppFrozenAction),
        define("bubble.flash_add", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::FlashBubbleAddAction),
        define("bubble.flash_remove", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::FlashBubbleRemoveAction),
        define("bubble.flash_clear", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::FlashBubbleClearAction),
        define("bubble.flashkill_show", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::FlashKillIconShowAction),
        define("bubble.flashkill_hide", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::FlashKillIconHideAction),
        define("tasks.launchers", ActionCategory.APP, ActionRetrySafety.NEVER, ::MakeLauncherTasksAction),
        define("share.relays", ActionCategory.APP, ActionRetrySafety.NEVER, ::GenerateShareRelaysAction),
        define("task.editaction", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::EditActionAction),
        define("task.addaction", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::AddActionAction),
        define("task.exists", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::TaskExistsAction),
        define("tasks.sort", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SortGroupTasksAction),
        define("app.previous", ActionCategory.APP, ActionRetrySafety.NEVER, ::PreviousAppAction),
        define("app.next", ActionCategory.APP, ActionRetrySafety.NEVER, ::NextAppAction),
        define("app.pick", ActionCategory.APP, ActionRetrySafety.NEVER, ::PickAppDialogAction),
        define("app.pickmulti", ActionCategory.APP, ActionRetrySafety.NEVER, ::PickAppsToVariableAction),
        define("apps.list", ActionCategory.APP, ActionRetrySafety.IDEMPOTENT, ::ListAppsAction),
        define("media.playpause", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::TogglePlayPauseAction),
        define("audio.record.start", ActionCategory.MEDIA, ActionRetrySafety.NEVER, ::AudioRecordStartAction),
        define("audio.record.stop", ActionCategory.MEDIA, ActionRetrySafety.IDEMPOTENT, ::AudioRecordStopAction),
        define("power.off", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::PowerOffAction),
        define("var.clear", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ClearVariableAction),
        define("var.split", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::SplitVariableAction),
        define("var.join", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::JoinVariableAction),
        define("var.replace", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::SearchReplaceVariableAction),
        define("var.convert", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ConvertVariableAction),
        define("var.add", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::AddVariableAction),
        define("datetime", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::DateTimeAction),
        define("array.set", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ArraySetAction),
        define("array.push", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayPushAction),
        define("array.pop", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayPopAction),
        define("array.clear", ActionCategory.VARIABLE, ActionRetrySafety.IDEMPOTENT, ::ArrayClearAction),
        define("array.process", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayProcessAction),
        define("array.merge", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::ArrayMergeAction),
        define("file.move", ActionCategory.FILE, ActionRetrySafety.NEVER, ::MoveFileAction),
        define("file.mkdir", ActionCategory.FILE, ActionRetrySafety.IDEMPOTENT, ::MakeDirectoryAction),
        define("file.open", ActionCategory.FILE, ActionRetrySafety.NEVER, ::OpenFileAction),
        define("flash", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::FlashAction),
        define("flow.comment", ActionCategory.FLOW, ActionRetrySafety.IDEMPOTENT, ::CommentAction),
        define("email.compose", ActionCategory.APP, ActionRetrySafety.NEVER, ::ComposeEmailAction),
        define("wallpaper.set", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SetWallpaperAction),
        define("wallpaper.live", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SetLiveWallpaperAction),
        define("wifi.settings", ActionCategory.SETTINGS, ActionRetrySafety.NEVER, ::WifiSettingsAction),
        define("ime.pick", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::ImePickerAction),
        define("nav.back", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::NavBackAction),
        define("nav.recents", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::NavRecentsAction),
        define("nav.screenshot", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::TakeScreenshotAction),
        define("nav.power", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::PowerDialogAction),
        define("panel.notifications", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::NotificationsPanelAction),
        define("panel.quicksettings", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::QuickSettingsPanelAction),
        define("screen.lock", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::LockScreenAction),
        define("screen.lockdown", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::LockdownAction),
        define("call.place", ActionCategory.APP, ActionRetrySafety.NEVER, ::PlaceCallAction),
        define("brightness.auto", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::AutoBrightnessAction),
        define("profile.toggle", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::ToggleProfileAction),
        define("setting.get", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::GetSettingAction),
        define("setting.put", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::PutSettingAction),
        define("dialog.input", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::InputDialogAction),
        define("dialog.list", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::ListDialogAction),
        define("dialog.pickmulti", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::PickListToVariableAction),
        define("dialog.text", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::TextDialogAction),
        define("scene.gestures", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SceneGesturesAction),
        define("key.bindings", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::KeyBindingsAction),
        define("shell.run", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::ShellRunAction),
        define("net.speedtest", ActionCategory.NET, ActionRetrySafety.NEVER, ::SpeedTestAction),
        define("net.speedtest.cancel", ActionCategory.NET, ActionRetrySafety.IDEMPOTENT, ::CancelSpeedTestAction),
        define("sim.list", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::ReadSimsAction),
        define("sim.data.set", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SetDataSimAction),
        define("location.get", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::GetLocationAction),
        define("location.mode", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::LocationModeAction),
        define("band.sync", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::BandSyncAction),
        // IDEMPOTENT: a scan reads the air and writes only variables, so a retry costs a few seconds
        // and changes nothing — unlike band.sync, which moves records into the archive.
        define("band.scan", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::BandScanAction),
        define("band.session", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::BandSessionAction),
        define("band.charts", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::BandChartsAction),
        // The HUAWEI Band 11 Pro, running in parallel with the Hume band. NEVER retry-safe
        // for the same reason band.sync is not: it moves records off a device that will
        // overwrite them, and a retry can re-drive a radio that is mid-handshake.
        define("huawei.sync", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::HuaweiSyncAction),
        // Pairing plus the HiChain bind plus the configuration set, as ONE run — the band
        // gives a new companion only seconds, so these cannot be separate steps.
        define("huawei.pair", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::HuaweiPairAction),
        define("huawei.charts", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::HuaweiChartsAction),
        // A read-only census of what the band supports. IDEMPOTENT: it counts and reads, never writes.
        define("huawei.probe", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HuaweiProbeAction),
        // NEVER retry-safe: it drops a credential and a Bluetooth bond.
        define("huawei.unpair", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::HuaweiUnpairAction),
        // IDEMPOTENT: setting the same locale twice is the same one frame with the same answer.
        define("huawei.language", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HuaweiLanguageAction),
        // IDEMPOTENT: it reads files and writes them to disk; the band is unchanged.
        define("huawei.files", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HuaweiFilesAction),
        // NEVER retry-safe: a half-sent face is refused by the band, but re-running blind while one
        // transfer is still live would fight it for the link.
        define("huawei.watchface", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::HuaweiWatchFaceAction),
        // Both write to the band, and both are safe to repeat: setting the same switch or pushing
        // the same weather twice leaves it exactly where it was.
        define("huawei.settings", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HuaweiSettingsAction),
        define("huawei.weather", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HuaweiWeatherAction),
        define("huawei.workouts", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HuaweiWorkoutsAction),
        define("band.compare", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::BandCompareAction),
        define("ocr.recognize", ActionCategory.VARIABLE, ActionRetrySafety.NEVER, ::OcrRecognizeAction),
        define("ocr.models", ActionCategory.SETTINGS, ActionRetrySafety.IDEMPOTENT, ::OcrModelsAction),
        define("ocr.article", ActionCategory.FILE, ActionRetrySafety.NEVER, ::ArticleToHtmlAction),
        define("widget.set", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::SetWidgetAction),
        define("widget.refresh", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::RefreshWidgetsAction),
        define("scene.show", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::ShowSceneAction),
        define("scene.hide", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HideSceneAction),
        define("progress.show", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::ShowProgressPanelAction),
        define("progress.row", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::ProgressPanelStepAction),
        define("progress.item", ActionCategory.SYSTEM, ActionRetrySafety.NEVER, ::ProgressPanelItemAction),
        define("progress.finish", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::FinishProgressPanelAction),
        define("progress.hide", ActionCategory.SYSTEM, ActionRetrySafety.IDEMPOTENT, ::HideProgressPanelAction),
        define("backup.categories", ActionCategory.APP, ActionRetrySafety.NEVER, ::BackupCategoriesAction),
        define("backup.edititems", ActionCategory.APP, ActionRetrySafety.NEVER, ::BackupEditItemsAction),
        define("backup.plan", ActionCategory.APP, ActionRetrySafety.NEVER, ::BackupPlanAction),
        define("backup.runitems", ActionCategory.APP, ActionRetrySafety.NEVER, ::BackupRunItemsAction),
        define("backup.prune", ActionCategory.FILE, ActionRetrySafety.NEVER, ::PruneBackupsAction),
        define("task.return", ActionCategory.FLOW, ActionRetrySafety.IDEMPOTENT, ::ReturnValuesAction),
        define("flow.fail", ActionCategory.FLOW, ActionRetrySafety.NEVER, ::FailAction),
    )

    /** Engine-handled flow markers have metadata but no runtime Action implementation. */
    private val engineHandled = listOf(
        defineEngine("task.run", ActionCategory.FLOW),
        defineEngine("flow.if", ActionCategory.FLOW),
        defineEngine("flow.else", ActionCategory.FLOW),
        defineEngine("flow.endif", ActionCategory.FLOW),
        defineEngine("flow.foreach", ActionCategory.FLOW),
        defineEngine("flow.endfor", ActionCategory.FLOW),
        defineEngine("flow.stop", ActionCategory.FLOW),
        defineEngine("flow.try", ActionCategory.FLOW),
        defineEngine("flow.catch", ActionCategory.FLOW),
        defineEngine("flow.endtry", ActionCategory.FLOW),
    )

    val allDefinitions: List<ActionDefinition> = all + engineHandled

    private val byId = allDefinitions.associateBy(ActionDefinition::id)

    init {
        require(byId.size == allDefinitions.size) { "Duplicate action declaration in ActionCatalog" }
    }

    fun get(id: String): ActionDefinition? = byId[id]

    fun require(id: String): ActionDefinition = requireNotNull(get(id)) {
        "No action declaration for $id"
    }

    private fun define(
        id: String,
        category: ActionCategory,
        retrySafety: ActionRetrySafety,
        factory: () -> Action,
    ) = ActionDefinition(id, category, retrySafety, factory)

    private fun defineEngine(
        id: String,
        category: ActionCategory,
    ) = ActionDefinition(
        id = id,
        category = category,
        retrySafety = ActionRetrySafety.NEVER,
        factory = { error("$id is handled by the task engine, not an Action implementation") },
    )
}
