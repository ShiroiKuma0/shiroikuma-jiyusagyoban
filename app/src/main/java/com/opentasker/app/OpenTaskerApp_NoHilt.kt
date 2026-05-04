package com.opentasker.app

import android.app.Application
import androidx.room.Room
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseMigrations

// Test application class without Hilt to verify basic structure
class OpenTaskerApp_NoHilt : Application() {
    companion object {
        private var _db: AppDatabase? = null
        
        val db: AppDatabase
            get() {
                if (_db == null) {
                    throw IllegalStateException("Database not initialized.")
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
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
