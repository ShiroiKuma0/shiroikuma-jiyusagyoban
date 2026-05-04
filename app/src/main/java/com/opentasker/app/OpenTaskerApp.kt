package com.opentasker.app

import android.app.Application
import androidx.room.Room
import com.opentasker.core.storage.AppDatabase
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenTaskerApp : Application() {
    companion object {
        private var _db: AppDatabase? = null
        
        val db: AppDatabase
            get() {
                if (_db == null) {
                    // This should only happen if accessed before onCreate completes
                    throw IllegalStateException("Database not initialized. Ensure OpenTaskerApp.onCreate() has completed.")
                }
                return _db!!
            }
    }

    override fun onCreate() {
        super.onCreate()
        
        if (_db == null) {
            _db = Room.databaseBuilder(
                this,
                AppDatabase::class.java,
                "opentasker.db"
            )
                .fallbackToDestructiveMigration() // For development; remove in production
                .build()
        }
    }
}
