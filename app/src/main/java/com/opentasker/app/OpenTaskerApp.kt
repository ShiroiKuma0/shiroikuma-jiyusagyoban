package com.opentasker.app

import android.app.Application
import androidx.room.Room
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseMigrations
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
                // Add migrations for schema evolution
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                // For dev/testing: fallback to destructive only as last resort
                // Remove for production after schema stabilizes
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
