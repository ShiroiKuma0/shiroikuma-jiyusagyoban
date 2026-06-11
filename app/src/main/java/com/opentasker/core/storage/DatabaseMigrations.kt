package com.opentasker.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database schema migrations for OpenTasker.
 * Add new migrations here as the schema evolves.
 */
object DatabaseMigrations {
    
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN automationMode TEXT NOT NULL DEFAULT 'SINGLE'")
        }
    }
    
    /**
     * Get all configured migrations in order.
     */
    fun getAllMigrations(): Array<Migration> {
        return arrayOf(
            MIGRATION_1_2
            // Add more migrations here as needed: MIGRATION_2_3, MIGRATION_3_4, etc.
        )
    }
}

/**
 * Documentation for future schema changes:
 * 
 * Version 1:
 *   - profiles: id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson
 *   - tasks: id, name, priority, collisionMode, actionsJson
 *   - scenes: id, name, widthDp, heightDp, elementsJson
 *   - variables: name (pk), value, isGlobal
 *   - run_logs: id, taskId, taskName, timestamp, durationMs, success, message
 * 
 * Version 2 (current):
 *   - profiles: adds automationMode (SINGLE, RESTART, QUEUED, PARALLEL)
 * 
 * To add a migration:
 * 1. Increment database version in @Database annotation
 * 2. Add new MIGRATION_X_Y class here
 * 3. Update getAllMigrations() to include it
 * 4. Update schema documentation above
 * 5. Update Room's @Database(exportSchema=true) to export new schema
 */
