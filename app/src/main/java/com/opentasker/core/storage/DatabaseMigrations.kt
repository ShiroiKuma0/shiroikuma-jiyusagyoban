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
            // Projects: a top-level grouping for profiles/tasks/scenes. New `projects` table plus a
            // nullable `projectId` on each groupable table (legacy rows keep NULL = Unfiled).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `projects` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`color` INTEGER, " +
                    "`sortOrder` INTEGER NOT NULL, " +
                    "`description` TEXT NOT NULL)"
            )
            db.execSQL("ALTER TABLE profiles ADD COLUMN projectId INTEGER")
            db.execSQL("ALTER TABLE tasks ADD COLUMN projectId INTEGER")
            db.execSQL("ALTER TABLE scenes ADD COLUMN projectId INTEGER")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Manual sort order per tab: a `position` column on each groupable table. Seed it from
            // the row id so the existing (insertion) order is preserved as the initial manual order.
            for (table in listOf("profiles", "tasks", "scenes")) {
                db.execSQL("ALTER TABLE $table ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE $table SET position = id")
            }
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Project-scoped, persistent variables. Re-key the `variables` table from (name) to
            // (projectId, name) and drop `isGlobal`. Existing rows become super-globals (projectId 0).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `variables_new` (" +
                    "`projectId` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, " +
                    "PRIMARY KEY(`projectId`, `name`))"
            )
            db.execSQL("INSERT OR REPLACE INTO `variables_new` (`projectId`, `name`, `value`) SELECT 0, `name`, `value` FROM `variables`")
            db.execSQL("DROP TABLE `variables`")
            db.execSQL("ALTER TABLE `variables_new` RENAME TO `variables`")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Scene panel styling: optional background colour, corner radius and modal scrim darkness.
            db.execSQL("ALTER TABLE scenes ADD COLUMN bgColor TEXT")
            db.execSQL("ALTER TABLE scenes ADD COLUMN cornerRadiusDp INTEGER NOT NULL DEFAULT 16")
            db.execSQL("ALTER TABLE scenes ADD COLUMN scrimAlpha INTEGER NOT NULL DEFAULT 55")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Scene panel border (colour + thickness).
            db.execSQL("ALTER TABLE scenes ADD COLUMN borderColor TEXT")
            db.execSQL("ALTER TABLE scenes ADD COLUMN borderWidth INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Per-scene default presentation (used by scene.show when the matching arg is omitted).
            db.execSQL("ALTER TABLE scenes ADD COLUMN defaultPosition TEXT NOT NULL DEFAULT 'center'")
            db.execSQL("ALTER TABLE scenes ADD COLUMN defaultModal INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE scenes ADD COLUMN defaultDismissOnOutside INTEGER NOT NULL DEFAULT 1")
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
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
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
 * Version 5 (current):
 *   - projects: id, name, color (nullable), sortOrder, description
 *   - profiles/tasks/scenes: add nullable projectId (NULL = Unfiled)
 *
 * To add a migration:
 * 1. Increment database version in @Database annotation
 * 2. Add new MIGRATION_X_Y class here
 * 3. Update getAllMigrations() to include it
 * 4. Update schema documentation above
 * 5. Update Room's @Database(exportSchema=true) to export new schema
 */
