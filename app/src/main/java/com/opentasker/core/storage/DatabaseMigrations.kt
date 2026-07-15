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
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `edit_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `entityType` TEXT NOT NULL,
                    `entityId` INTEGER NOT NULL,
                    `previousJson` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Typed run-log trigger source columns (nullable; legacy rows keep NULL).
            db.execSQL("ALTER TABLE run_logs ADD COLUMN source TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN sourceLabel TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN profileGroup TEXT")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE variables ADD COLUMN isSecret INTEGER NOT NULL DEFAULT 0")
            // Preserve the previous name-based masking policy exactly, but mark only rows that
            // existed during this schema migration. VariableRepository encrypts these flagged
            // plaintext values immediately after Room opens; new v6 rows use explicit UI state.
            db.execSQL(
                """
                UPDATE variables SET isSecret = 1
                WHERE lower(name) LIKE '%password%'
                   OR lower(name) LIKE '%token%'
                   OR lower(name) LIKE '%secret%'
                   OR lower(name) LIKE '%key%'
                   OR lower(name) LIKE '%credential%'
                   OR lower(name) LIKE '%auth%'
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE profiles ADD COLUMN requiresRiskAcknowledgement INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    fun getAllMigrations(): Array<Migration> {
        return arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
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
 * Version 2:
 *   - profiles: adds automationMode (SINGLE, RESTART, QUEUED, PARALLEL)
 *
 * Version 3:
 *   - edit_history: id, entityType, entityId, previousJson, timestamp
 *
 * Version 4:
 *   - run_logs: adds nullable source (typed trigger key) and sourceLabel (human label)
 *
 * Version 5:
 *   - profiles: adds nullable profileGroup for folder/tag organization
 *
 * Version 6:
 *   - variables: adds isSecret; secret rows store authenticated Keystore ciphertext in value
 *
 * Version 7 (current):
 *   - profiles: adds requiresRiskAcknowledgement for imported-profile first-enable gating
 *
 * To add a migration:
 * 1. Increment database version in @Database annotation
 * 2. Add new MIGRATION_X_Y class here
 * 3. Update getAllMigrations() to include it
 * 4. Update schema documentation above
 * 5. Update Room's @Database(exportSchema=true) to export new schema
 */
