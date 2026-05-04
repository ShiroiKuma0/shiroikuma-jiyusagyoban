package com.opentasker.app

import android.app.Application
import androidx.room.Room
import com.opentasker.core.actions.*
import com.opentasker.core.contexts.*
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.storage.AppDatabase

class OpenTaskerApp : Application() {
    companion object {
        lateinit var db: AppDatabase
            private set
    }

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Room database
        db = Room.databaseBuilder(this, AppDatabase::class.java, "opentasker.db").build()
        
        // Register all built-in actions and their metadata
        registerActions()
        registerActionMetadata()
        
        // Register all context sources
        registerContextSources()
    }

    private fun registerActions() {
        ActionRegistry.register(NotifyAction())
        ActionRegistry.register(SetVariableAction())
        ActionRegistry.register(SayAction())
        ActionRegistry.register(WaitAction())
        ActionRegistry.register(LaunchIntentAction())
        
        ActionRegistry.register(WiFiToggleAction())
        ActionRegistry.register(BluetoothToggleAction())
        ActionRegistry.register(BrightnessAction())
        ActionRegistry.register(VolumeAction())
        ActionRegistry.register(AirplaneModeAction())
        ActionRegistry.register(MobileDataAction())
        ActionRegistry.register(ScreenTimeoutAction())
        
        ActionRegistry.register(LaunchAppAction())
        ActionRegistry.register(KillAppAction())
        ActionRegistry.register(GoHomeAction())
        ActionRegistry.register(OpenUrlAction())
        ActionRegistry.register(SendSmsAction())
        ActionRegistry.register(ScreenshotAction())
        
        ActionRegistry.register(ReadFileAction())
        ActionRegistry.register(WriteFileAction())
        ActionRegistry.register(AppendFileAction())
        ActionRegistry.register(DeleteFileAction())
        ActionRegistry.register(ListFilesAction())
        
        ActionRegistry.register(HttpGetAction())
        ActionRegistry.register(HttpPostAction())
        ActionRegistry.register(PingAction())
        ActionRegistry.register(DownloadAction())
        
        ActionRegistry.register(PlaySoundAction())
        ActionRegistry.register(StopSoundAction())
        ActionRegistry.register(PauseSoundAction())
        ActionRegistry.register(NextTrackAction())
        ActionRegistry.register(PreviousTrackAction())
        ActionRegistry.register(MuteAction())
        
        ActionRegistry.register(VibrateAction())
        ActionRegistry.register(RebootAction())
        ActionRegistry.register(LockDeviceAction())
        ActionRegistry.register(ScreenOffAction())
        ActionRegistry.register(WakeAction())
        ActionRegistry.register(LogAction())
    }

    private fun registerContextSources() {
        ContextSourceRegistry.register(ApplicationContextSourceImpl())
        ContextSourceRegistry.register(TimeContextSourceImpl())
        ContextSourceRegistry.register(DayContextSource())
        ContextSourceRegistry.register(LocationContextSource())
        ContextSourceRegistry.register(StateContextSourceImpl())
        ContextSourceRegistry.register(EventContextSourceImpl())
    }
}
