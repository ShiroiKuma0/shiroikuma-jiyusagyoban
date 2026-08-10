package com.opentasker.core.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database schema migrations for OpenTasker.
 * Add new migrations here as the schema evolves.
 */
object DatabaseMigrations {
    /**
     * Seeds the workspace every entity defaults to.
     *
     * Shared with the fresh-install callback: a new database is created by Room's generated
     * `createAllTables` and never runs a migration, so seeding this only from MIGRATION_8_9 left
     * fresh installs with no project row at all. `projects.id` is AUTOINCREMENT, so the first
     * project the user created then took id 1 - silently becoming the undeletable Default that
     * owns every existing task and profile.
     */
    const val SEED_DEFAULT_PROJECT =
        "INSERT OR IGNORE INTO `projects` (`id`, `name`, `position`) VALUES (1, 'Default', 0)"


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

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Index names must match Room's generated names for the @Entity indices.
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_logs_timestamp` ON `run_logs` (`timestamp`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_edit_history_entityType_entityId` " +
                    "ON `edit_history` (`entityType`, `entityId`)",
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `projects` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `position` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_projects_name` ON `projects` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_projects_position` ON `projects` (`position`)")
            db.execSQL(SEED_DEFAULT_PROJECT)

            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `projectId` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_projectId` ON `tasks` (`projectId`)")
            db.execSQL("ALTER TABLE `profiles` ADD COLUMN `projectId` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_projectId` ON `profiles` (`projectId`)")
            db.execSQL("ALTER TABLE `scenes` ADD COLUMN `projectId` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_scenes_projectId` ON `scenes` (`projectId`)")

            db.execSQL(
                """
                CREATE TABLE `variables_new` (
                    `name` TEXT NOT NULL,
                    `value` TEXT NOT NULL,
                    `isGlobal` INTEGER NOT NULL,
                    `isSecret` INTEGER NOT NULL,
                    `projectId` INTEGER NOT NULL,
                    PRIMARY KEY(`projectId`, `name`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `variables_new` (`name`, `value`, `isGlobal`, `isSecret`, `projectId`)
                SELECT `name`, `value`, `isGlobal`, `isSecret`, 1 FROM `variables`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `variables`")
            db.execSQL("ALTER TABLE `variables_new` RENAME TO `variables`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_variables_name` ON `variables` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_variables_projectId` ON `variables` (`projectId`)")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE edit_history ADD COLUMN nextJson TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE edit_history ADD COLUMN isUndone INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE run_logs ADD COLUMN executionId TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN replayOf TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN held INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN heldPayload TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN heldPolicy TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE profiles ADD COLUMN gracePeriodSec INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE profiles ADD COLUMN lifetime TEXT NOT NULL DEFAULT 'NEVER'")
            db.execSQL("ALTER TABLE profiles ADD COLUMN expiresAtMs INTEGER")
            db.execSQL("ALTER TABLE profiles ADD COLUMN lifetimeConsumed INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN maxActiveExecutions INTEGER")
            db.execSQL("ALTER TABLE profiles ADD COLUMN burstLimit INTEGER")
            db.execSQL("ALTER TABLE profiles ADD COLUMN overflowPolicy TEXT NOT NULL DEFAULT 'LOG'")
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN fallbackTaskId INTEGER")
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `execution_journal` (
                    `executionId` TEXT NOT NULL,
                    `taskId` INTEGER NOT NULL,
                    `taskName` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `sourceLabel` TEXT,
                    `profileId` INTEGER,
                    `replayOf` TEXT,
                    `parentExecutionId` TEXT,
                    `producer` TEXT NOT NULL,
                    `startedAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    `lastStepIndex` INTEGER,
                    `lastStepLabel` TEXT,
                    `state` TEXT NOT NULL,
                    `terminalReason` TEXT,
                    `terminalAtMs` INTEGER,
                    `runLogWritten` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`executionId`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_execution_journal_state` ON `execution_journal` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_execution_journal_updatedAtMs` ON `execution_journal` (`updatedAtMs`)")
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

    /**
     * Migrations that are not represented by AppDatabase.autoMigrations.
     *
     * Keep the explicit migrations available above for schema-history tests; runtime builders
     * must not register both a manual and generated migration for the same version pair.
     */
    fun getManualMigrations(): Array<Migration> {
        return arrayOf(
            MIGRATION_1_2,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_8_9,
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
 * Version 5:
 *   - profiles: adds nullable profileGroup for folder/tag organization
 *
 * Version 6:
 *   - variables: adds isSecret; secret rows store authenticated Keystore ciphertext in value
 *
 * Version 7:
 *   - profiles: adds requiresRiskAcknowledgement for imported-profile first-enable gating
 *
 * Version 8:
 *   - run_logs: adds index on timestamp (reactive recent query + retention pruning)
 *   - edit_history: adds composite index on (entityType, entityId)
 *
 * Version 9:
 *   - projects: adds the default workspace project and ordering
 *   - profiles, tasks, scenes: adds projectId (legacy rows backfill to Default)
 *   - variables: migrates to a composite (projectId, name) key for project-scoped values
 *
 * Version 10:
 *   - edit_history: adds nextJson and isUndone so five-entry per-entity stacks support redo
 *
 * Version 11:
 *   - run_logs: adds execution identity, replay links, bounded held-trigger payload/policy, and
 *     a user star that is exempt from retention pruning
 *
 * Version 12:
 *   - profiles: adds priority, symmetric grace period, lifetime/expiry policy, and persisted
 *     one-shot consumption state
 *
 * Version 13:
 *   - profiles: adds optional active/burst admission overrides and LOG/SILENT overflow policy
 *
 * Version 14:
 *   - profiles: adds an optional fallback task id
 *
 * Version 15:
 *   - execution_journal: persists admitted execution identity, source/lineage, last known step,
 *     terminal state, and whether the corresponding run-log row was written
 *
 * To add a migration:
 * 1. Increment database version in @Database annotation
 * 2. Add new MIGRATION_X_Y class here
 * 3. Update getAllMigrations() to include it
 * 4. Update schema documentation above
 * 5. Update Room's @Database(exportSchema=true) to export new schema
 */
