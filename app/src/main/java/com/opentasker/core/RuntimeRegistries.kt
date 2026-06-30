package com.opentasker.core

import com.opentasker.core.actions.AirplaneModeAction
import com.opentasker.core.actions.AppendFileAction
import com.opentasker.core.actions.AudioRecordStartAction
import com.opentasker.core.actions.AudioRecordStopAction
import com.opentasker.core.actions.BluetoothToggleAction
import com.opentasker.core.actions.BrightnessAction
import com.opentasker.core.actions.DoNotDisturbAction
import com.opentasker.core.actions.DeleteFileAction
import com.opentasker.core.actions.DownloadAction
import com.opentasker.core.actions.GoHomeAction
import com.opentasker.core.actions.HttpGetAction
import com.opentasker.core.actions.HttpPostAction
import com.opentasker.core.actions.FreezeAppAction
import com.opentasker.core.actions.KillAppAction
import com.opentasker.core.actions.UnfreezeAppAction
import com.opentasker.core.actions.LaunchAppAction
import com.opentasker.core.actions.NextAppAction
import com.opentasker.core.actions.PreviousAppAction
import com.opentasker.core.actions.LaunchIntentAction
import com.opentasker.core.actions.LocalePluginConditionQueryAction
import com.opentasker.core.actions.LocalePluginSettingAction
import com.opentasker.core.actions.ListFilesAction
import com.opentasker.core.actions.LockDeviceAction
import com.opentasker.core.actions.MakeLauncherTasksAction
import com.opentasker.core.actions.LogAction
import com.opentasker.core.actions.MobileDataAction
import com.opentasker.core.actions.MuteAction
import com.opentasker.core.actions.NextTrackAction
import com.opentasker.core.actions.NotifyAction
import com.opentasker.core.actions.NotifyCancelAction
import com.opentasker.core.actions.NotifyDismissAction
import com.opentasker.core.actions.OpenUrlAction
import com.opentasker.core.actions.PauseSoundAction
import com.opentasker.core.actions.PersistVariableAction
import com.opentasker.core.actions.PingAction
import com.opentasker.core.actions.PlaySoundAction
import com.opentasker.core.actions.PreviousTrackAction
import com.opentasker.core.actions.ReadFileAction
import com.opentasker.core.actions.RingerModeAction
import com.opentasker.core.actions.PowerOffAction
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
import com.opentasker.core.actions.TogglePlayPauseAction
import com.opentasker.core.actions.TaskerUnsupportedAction
import com.opentasker.core.actions.TermuxScriptAction
import com.opentasker.core.actions.TileStateAction
import com.opentasker.core.actions.TorchAction
import com.opentasker.core.actions.VibrateAction
import com.opentasker.core.actions.VolumeAction
import com.opentasker.core.actions.VolumeGetAction
import com.opentasker.core.actions.StateGetAction
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
import com.opentasker.core.actions.TakeScreenshotAction
import com.opentasker.core.actions.NotificationsPanelAction
import com.opentasker.core.actions.QuickSettingsPanelAction
import com.opentasker.core.actions.PowerDialogAction
import com.opentasker.core.actions.LockScreenAction
import com.opentasker.core.actions.LockdownAction
import com.opentasker.core.actions.PlaceCallAction
import com.opentasker.core.actions.AutoBrightnessAction
import com.opentasker.core.actions.OpenFileAction
import com.opentasker.core.actions.ToggleProfileAction
import com.opentasker.core.actions.GetSettingAction
import com.opentasker.core.actions.PutSettingAction
import com.opentasker.core.actions.InputDialogAction
import com.opentasker.core.actions.ListDialogAction
import com.opentasker.core.actions.TextDialogAction
import com.opentasker.core.actions.ShellRunAction
import com.opentasker.core.actions.LocationModeAction
import com.opentasker.core.actions.SetImeAction
import com.opentasker.core.actions.HideSceneAction
import com.opentasker.core.actions.RefreshWidgetsAction
import com.opentasker.core.actions.SetWidgetAction
import com.opentasker.core.actions.ShowSceneAction
import com.opentasker.core.contexts.ApplicationContextSourceImpl
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.EventContextSourceImpl
import com.opentasker.core.contexts.LocalePluginConditionContextSource
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
        NotifyDismissAction(),
        SetVariableAction(),
        PersistVariableAction(),
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
        VolumeGetAction(),
        StateGetAction(),
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
        FreezeAppAction(),
        UnfreezeAppAction(),
        MakeLauncherTasksAction(),
        GoHomeAction(),
        PreviousAppAction(),
        NextAppAction(),
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
        TogglePlayPauseAction(),
        NextTrackAction(),
        PreviousTrackAction(),
        MuteAction(),
        AudioRecordStartAction(),
        AudioRecordStopAction(),
        VibrateAction(),
        RebootAction(),
        PowerOffAction(),
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
        TakeScreenshotAction(),
        NotificationsPanelAction(),
        QuickSettingsPanelAction(),
        PowerDialogAction(),
        LockScreenAction(),
        LockdownAction(),
        PlaceCallAction(),
        AutoBrightnessAction(),
        OpenFileAction(),
        ToggleProfileAction(),
        GetSettingAction(),
        PutSettingAction(),
        InputDialogAction(),
        ListDialogAction(),
        TextDialogAction(),
        ShellRunAction(),
        LocationModeAction(),
        SetImeAction(),
        SetWidgetAction(),
        RefreshWidgetsAction(),
        ShowSceneAction(),
        HideSceneAction(),
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
        LocalePluginConditionContextSource(),
    ).forEach(ContextSourceRegistry::register)
}
