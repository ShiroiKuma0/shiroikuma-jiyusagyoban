package com.opentasker.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database schema migrations for OpenTasker.
 * Add new migrations here as the schema evolves.
 */
object DatabaseMigrations {
    
    /**
     * Migration from v1 to v2 (placeholder for future use).
     * Currently defines the base schema; future migrations should be added here.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Example: Add a new column to profiles table
            // db.execSQL("ALTER TABLE profiles ADD COLUMN newColumn TEXT DEFAULT ''")
            
            // For now, no schema changes required
            // This is where you would add schema evolution
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
 * Version 1 (current):
 *   - profiles: id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson
 *   - tasks: id, name, priority, collisionMode, actionsJson
 *   - scenes: id, name, widthDp, heightDp, elementsJson
 *   - variables: name (pk), value, isGlobal
 *   - run_log: id, taskId, taskName, startedAt, durationMs, resultsJson, success
 * 
 * Future version 2:
 *   - Potential additions: automation_rules, execution_logs, etc.
 * 
 * To add a migration:
 * 1. Increment database version in @Database annotation
 * 2. Add new MIGRATION_X_Y class here
 * 3. Update getAllMigrations() to include it
 * 4. Update schema documentation above
 * 5. Update Room's @Database(exportSchema=true) to export new schema
 */
