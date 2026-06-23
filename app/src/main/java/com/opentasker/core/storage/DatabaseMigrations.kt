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

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Per-item UI metadata (notes + group membership) and foldable groups — shared across all tabs.
            // CREATE statements match Room's exported v11 schema exactly (so the runtime identity hash matches).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_meta` (`tab` TEXT NOT NULL, `itemId` INTEGER NOT NULL, " +
                    "`groupId` INTEGER, `note` TEXT NOT NULL, `noteExpanded` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, PRIMARY KEY(`tab`, `itemId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`projectId` INTEGER, `tab` TEXT NOT NULL, `name` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `expanded` INTEGER NOT NULL, `noteExpanded` INTEGER NOT NULL)"
            )
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Generalise item_meta's per-item key from Long itemId to String itemKey (covers name-keyed
            // tabs like widgets). Recreate + copy, casting the old numeric ids to text. createSql matches
            // Room's exported v12 schema exactly.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_meta_new` (`tab` TEXT NOT NULL, `itemKey` TEXT NOT NULL, " +
                    "`groupId` INTEGER, `note` TEXT NOT NULL, `noteExpanded` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, PRIMARY KEY(`tab`, `itemKey`))"
            )
            db.execSQL(
                "INSERT INTO `item_meta_new` (`tab`, `itemKey`, `groupId`, `note`, `noteExpanded`, `position`) " +
                    "SELECT `tab`, CAST(`itemId` AS TEXT), `groupId`, `note`, `noteExpanded`, `position` FROM `item_meta`"
            )
            db.execSQL("DROP TABLE `item_meta`")
            db.execSQL("ALTER TABLE `item_meta_new` RENAME TO `item_meta`")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Nested groups: a group may point at an enclosing parent group (null = top level).
            db.execSQL("ALTER TABLE item_groups ADD COLUMN parentGroupId INTEGER")
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Profiles now link their enter/exit task by NAME too (resolved first, the id is the fallback),
            // so re-importing a task — which re-ids it — no longer orphans the profile ("Missing task #N").
            db.execSQL("ALTER TABLE profiles ADD COLUMN enterTaskName TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE profiles ADD COLUMN exitTaskName TEXT NOT NULL DEFAULT ''")
            // Backfill from the currently-linked task ids so existing (valid) links become name-bound.
            db.execSQL("UPDATE profiles SET enterTaskName = COALESCE((SELECT name FROM tasks WHERE tasks.id = profiles.enterTaskId), '')")
            db.execSQL("UPDATE profiles SET exitTaskName = COALESCE((SELECT name FROM tasks WHERE tasks.id = profiles.exitTaskId), '')")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Per-task custom icon: absolute path to a saved PNG, used as the home-screen shortcut icon
            // (and shown next to the task in-app). null = use 自由作業盤's launcher icon.
            db.execSQL("ALTER TABLE tasks ADD COLUMN iconPath TEXT")
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
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
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
