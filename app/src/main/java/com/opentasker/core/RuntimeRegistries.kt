package com.opentasker.core

import com.opentasker.core.actions.AirplaneModeAction
import com.opentasker.core.actions.AppendFileAction
import com.opentasker.core.actions.BluetoothToggleAction
import com.opentasker.core.actions.BrightnessAction
import com.opentasker.core.actions.DoNotDisturbAction
import com.opentasker.core.actions.DeleteFileAction
import com.opentasker.core.actions.DownloadAction
import com.opentasker.core.actions.GoHomeAction
import com.opentasker.core.actions.HttpGetAction
import com.opentasker.core.actions.HttpPostAction
import com.opentasker.core.actions.KillAppAction
import com.opentasker.core.actions.LaunchAppAction
import com.opentasker.core.actions.LaunchIntentAction
import com.opentasker.core.actions.LocalePluginConditionQueryAction
import com.opentasker.core.actions.LocalePluginSettingAction
import com.opentasker.core.actions.ListFilesAction
import com.opentasker.core.actions.LockDeviceAction
import com.opentasker.core.actions.LogAction
import com.opentasker.core.actions.MobileDataAction
import com.opentasker.core.actions.MuteAction
import com.opentasker.core.actions.NextTrackAction
import com.opentasker.core.actions.NotifyAction
import com.opentasker.core.actions.NotifyCancelAction
import com.opentasker.core.actions.OpenUrlAction
import com.opentasker.core.actions.PauseSoundAction
import com.opentasker.core.actions.PingAction
import com.opentasker.core.actions.PlaySoundAction
import com.opentasker.core.actions.PreviousTrackAction
import com.opentasker.core.actions.ReadFileAction
import com.opentasker.core.actions.RingerModeAction
import com.opentasker.core.actions.RebootAction
import com.opentasker.core.actions.SayAction
import com.opentasker.core.actions.ScreenOffAction
import com.opentasker.core.actions.ScreenTimeoutAction
import com.opentasker.core.actions.FailAction
import com.opentasker.core.actions.ReturnValuesAction
import com.opentasker.core.actions.ScreenshotAction
import com.opentasker.core.actions.SendIntentAction
import com.opentasker.core.actions.SendSmsAction
import com.opentasker.core.actions.SetVariableAction
import com.opentasker.core.actions.StopSoundAction
import com.opentasker.core.actions.TaskerUnsupportedAction
import com.opentasker.core.actions.TermuxScriptAction
import com.opentasker.core.actions.TileStateAction
import com.opentasker.core.actions.TorchAction
import com.opentasker.core.actions.VibrateAction
import com.opentasker.core.actions.VolumeAction
import com.opentasker.core.actions.WaitAction
import com.opentasker.core.actions.WakeAction
import com.opentasker.core.actions.WakeOnLanAction
import com.opentasker.core.actions.WiFiToggleAction
import com.opentasker.core.actions.WriteFileAction
import com.opentasker.core.actions.ClearVariableAction
import com.opentasker.core.actions.SplitVariableAction
import com.opentasker.core.actions.JoinVariableAction
import com.opentasker.core.actions.SearchReplaceVariableAction
import com.opentasker.core.actions.ConvertVariableAction
import com.opentasker.core.actions.AddVariableAction
import com.opentasker.core.actions.DateTimeAction
import com.opentasker.core.actions.ArraySetAction
import com.opentasker.core.actions.ArrayPushAction
import com.opentasker.core.actions.ArrayPopAction
import com.opentasker.core.actions.ArrayClearAction
import com.opentasker.core.actions.ArrayProcessAction
import com.opentasker.core.actions.ArrayMergeAction
import com.opentasker.core.actions.MoveFileAction
import com.opentasker.core.actions.MakeDirectoryAction
import com.opentasker.core.actions.FlashAction
import com.opentasker.core.actions.CommentAction
import com.opentasker.core.actions.SetClipboardAction
import com.opentasker.core.actions.GetClipboardAction
import com.opentasker.core.actions.ComposeEmailAction
import com.opentasker.core.actions.SetWallpaperAction
import com.opentasker.core.actions.WifiSettingsAction
import com.opentasker.core.actions.ListAppsAction
import com.opentasker.core.actions.ImePickerAction
import com.opentasker.core.actions.NavBackAction
import com.opentasker.core.actions.NavRecentsAction
import com.opentasker.core.actions.NotificationsPanelAction
import com.opentasker.core.actions.QuickSettingsPanelAction
import com.opentasker.core.actions.PowerDialogAction
import com.opentasker.core.actions.LockScreenAction
import com.opentasker.core.actions.PlaceCallAction
import com.opentasker.core.actions.AutoBrightnessAction
import com.opentasker.core.contexts.ApplicationContextSourceImpl
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.EventContextSourceImpl
import com.opentasker.core.contexts.LocationContextSourceImpl
import com.opentasker.core.contexts.StateContextSourceImpl
import com.opentasker.core.contexts.TimeContextSourceImpl
import com.opentasker.core.engine.ActionRegistry

fun registerCoreRuntime() {
    registerBuiltInActions()
    registerContextSources()
}

private fun registerBuiltInActions() {
    listOf(
        NotifyAction(),
        NotifyCancelAction(),
        SetVariableAction(),
        SayAction(),
        WaitAction(),
        LaunchIntentAction(),
        SendIntentAction(),
        ReturnValuesAction(),
        FailAction(),
        WiFiToggleAction(),
        BluetoothToggleAction(),
        BrightnessAction(),
        VolumeAction(),
        AirplaneModeAction(),
        MobileDataAction(),
        ScreenTimeoutAction(),
        DoNotDisturbAction(),
        RingerModeAction(),
        TorchAction(),
        TileStateAction(),
        LaunchAppAction(),
        LocalePluginSettingAction(),
        LocalePluginConditionQueryAction(),
        KillAppAction(),
        GoHomeAction(),
        OpenUrlAction(),
        SendSmsAction(),
        ScreenshotAction(),
        ReadFileAction(),
        WriteFileAction(),
        AppendFileAction(),
        DeleteFileAction(),
        ListFilesAction(),
        HttpGetAction(),
        HttpPostAction(),
        PingAction(),
        DownloadAction(),
        WakeOnLanAction(),
        PlaySoundAction(),
        StopSoundAction(),
        PauseSoundAction(),
        NextTrackAction(),
        PreviousTrackAction(),
        MuteAction(),
        VibrateAction(),
        RebootAction(),
        LockDeviceAction(),
        ScreenOffAction(),
        WakeAction(),
        LogAction(),
        ClearVariableAction(),
        SplitVariableAction(),
        JoinVariableAction(),
        SearchReplaceVariableAction(),
        ConvertVariableAction(),
        AddVariableAction(),
        DateTimeAction(),
        ArraySetAction(),
        ArrayPushAction(),
        ArrayPopAction(),
        ArrayClearAction(),
        ArrayProcessAction(),
        ArrayMergeAction(),
        MoveFileAction(),
        MakeDirectoryAction(),
        FlashAction(),
        CommentAction(),
        SetClipboardAction(),
        GetClipboardAction(),
        ComposeEmailAction(),
        SetWallpaperAction(),
        WifiSettingsAction(),
        ListAppsAction(),
        ImePickerAction(),
        NavBackAction(),
        NavRecentsAction(),
        NotificationsPanelAction(),
        QuickSettingsPanelAction(),
        PowerDialogAction(),
        LockScreenAction(),
        PlaceCallAction(),
        AutoBrightnessAction(),
        TermuxScriptAction(),
        TaskerUnsupportedAction(),
    ).forEach(ActionRegistry::register)
}

private fun registerContextSources() {
    listOf(
        ApplicationContextSourceImpl(),
        TimeContextSourceImpl(),
        StateContextSourceImpl(),
        EventContextSourceImpl(),
        LocationContextSourceImpl(),
    ).forEach(ContextSourceRegistry::register)
}
