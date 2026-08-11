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
            db.execSQL(
                """
                CREATE TABLE `profiles_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `enterTaskId` INTEGER NOT NULL,
                    `exitTaskId` INTEGER,
                    `cooldownSec` INTEGER NOT NULL,
                    `contextsJson` TEXT NOT NULL,
                    `automationMode` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `profiles_new` (
                    `id`, `name`, `enabled`, `enterTaskId`, `exitTaskId`, `cooldownSec`,
                    `contextsJson`, `automationMode`
                )
                SELECT
                    `id`, `name`, `enabled`, `enterTaskId`, `exitTaskId`, `cooldownSec`,
                    `contextsJson`, 'SINGLE'
                FROM `profiles`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `profiles`")
            db.execSQL("ALTER TABLE `profiles_new` RENAME TO `profiles`")
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

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Per-task flag: running this task queues a "freeze" bubble for the app it launches.
            db.execSQL("ALTER TABLE tasks ADD COLUMN freezeBubble INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Enforce name uniqueness at the DB level: (projectId, name) unique for tasks/profiles/scenes, name
    // unique for projects (mirrors the editors' UI check). SQLite treats NULL projectId (Unfiled) as
    // distinct, so only filed rows are constrained here — the UI covers Unfiled. Self-heals first: any
    // pre-existing collision is renamed "<name> (<id>)" so the unique index can never fail to build.
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (table in listOf("tasks", "profiles", "scenes")) {
                db.execSQL(
                    "UPDATE $table SET name = name || ' (' || id || ')' WHERE projectId IS NOT NULL AND " +
                        "id NOT IN (SELECT MIN(id) FROM $table WHERE projectId IS NOT NULL GROUP BY projectId, name)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_projectId_name` ON `$table` (`projectId`, `name`)")
            }
            db.execSQL(
                "UPDATE projects SET name = name || ' (' || id || ')' WHERE " +
                    "id NOT IN (SELECT MIN(id) FROM projects GROUP BY name)"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_projects_name` ON `projects` (`name`)")
        }
    }

    // Upstream's v5→6 (variables.isSecret), renumbered onto the fork chain (identical column add on
    // the fork's re-keyed (projectId, name) table). Upstream's name-based masking backfill is
    // deliberately NOT applied: it flags any name containing "key"/"auth"/…, which would mark (and
    // then irreversibly encrypt) working fork globals like the 物理鍵 `Pkey_*` family. In this fork
    // a variable becomes secret only by explicit choice in the Variables vault.
    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE variables ADD COLUMN isSecret INTEGER NOT NULL DEFAULT 0")
        }
    }

    // Upstream's v6→7 (imported-profile first-enable risk gating), renumbered onto the fork chain.
    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE profiles ADD COLUMN requiresRiskAcknowledgement INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    // Upstream's v7→8 (run_logs/edit_history indexes), renumbered onto the fork chain.
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Index names must match Room's generated names for the @Entity indices.
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_run_logs_timestamp` ON `run_logs` (`timestamp`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_edit_history_entityType_entityId` " +
                    "ON `edit_history` (`entityType`, `entityId`)",
            )
        }
    }

    /**
     * Band health history: four tables for the Hume Band V2 sync.
     *
     * Every statement below is Room's OWN generated SQL, lifted verbatim out of
     * app/schemas/…/21.json with the literal table name substituted for ${TABLE_NAME}. Hand-writing
     * it is how you get an identity-hash mismatch at runtime — the column order, the NOT NULL flags
     * and the index name all have to match what Room expects byte for byte.
     */
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `band_samples` (`metric` TEXT NOT NULL, " +
                    "`localTs` INTEGER NOT NULL, `epochMs` INTEGER NOT NULL, `value` REAL NOT NULL, " +
                    "`syncId` INTEGER NOT NULL, PRIMARY KEY(`metric`, `localTs`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_band_samples_metric_epochMs` " +
                    "ON `band_samples` (`metric`, `epochMs`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `band_daily` (`localDate` INTEGER NOT NULL, " +
                    "`steps` INTEGER NOT NULL, `distanceM` REAL NOT NULL, `calories` REAL NOT NULL, " +
                    "`rawExercise` INTEGER NOT NULL, `rawTail` TEXT NOT NULL, `syncId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`localDate`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `band_sleep` (`startLocalTs` INTEGER NOT NULL, " +
                    "`minutes` INTEGER NOT NULL, `stages` TEXT NOT NULL, `syncId` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`startLocalTs`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `band_syncs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `ok` INTEGER NOT NULL, " +
                    "`address` TEXT NOT NULL, `firmware` TEXT, `battery` INTEGER, `mtu` INTEGER, " +
                    "`requestedFrom` INTEGER NOT NULL, `source` TEXT NOT NULL, `statsJson` TEXT NOT NULL, " +
                    "`message` TEXT NOT NULL)",
            )
        }
    }

    /**
     * Upstream v10, renumbered onto the end of the fork chain: durable undo/redo for task, profile,
     * and scene edits. Upstream's own 8→9 (the projects table and projectId columns) is NOT repeated
     * here — the fork already introduced projects in its own chain, so only the edit_history columns
     * are genuinely new.
     */
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `edit_history` ADD COLUMN `nextJson` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `edit_history` ADD COLUMN `isUndone` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Upstream v10→v15, renumbered onto the end of the fork chain as 22→27.
     *
     * The fork chain already occupies 1..22, and upstream's own 10..15 mean entirely different
     * things, so the pairs cannot be merged by number — only appended. Upstream declares its
     * 10→11 … 13→14 as generated AutoMigrations; the fork has never used AutoMigration (its
     * @Database declares none), so each is written out as explicit SQL here instead.
     */
    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE run_logs ADD COLUMN executionId TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN replayOf TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN held INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN heldPayload TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN heldPolicy TEXT")
            db.execSQL("ALTER TABLE run_logs ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE profiles ADD COLUMN gracePeriodSec INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE profiles ADD COLUMN lifetime TEXT NOT NULL DEFAULT 'NEVER'")
            db.execSQL("ALTER TABLE profiles ADD COLUMN expiresAtMs INTEGER")
            db.execSQL("ALTER TABLE profiles ADD COLUMN lifetimeConsumed INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // DEFAULT NULL is not decoration: the entity declares @ColumnInfo(defaultValue = "NULL"),
            // so Room's schema validation compares the column's stored default against the string
            // "NULL" and rejects the whole upgrade if the column was added without one. Upstream
            // never had to write this out because it declares these as generated AutoMigrations; the
            // fork registers every migration by hand, so the DDL has to say it.
            db.execSQL("ALTER TABLE profiles ADD COLUMN maxActiveExecutions INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE profiles ADD COLUMN burstLimit INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE profiles ADD COLUMN overflowPolicy TEXT NOT NULL DEFAULT 'LOG'")
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE profiles ADD COLUMN fallbackTaskId INTEGER DEFAULT NULL")
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
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
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
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
 *   - projects: id, name, color (nullable), sortOrder, description
 *   - profiles/tasks/scenes: add nullable projectId (NULL = Unfiled)
 *
 * Versions 6..17: fork chain (manual sort position, variables re-key to (projectId, name),
 *   scene panel styling/border/presentation, task icons, freeze bubbles, name uniqueness, …)
 *   — see the migrations above for the authoritative SQL.
 *
 * Version 18:
 *   - variables: adds isSecret; secret rows store authenticated Keystore ciphertext in value
 *     (upstream v6, renumbered)
 *
 * Version 19:
 *   - profiles: adds requiresRiskAcknowledgement for imported-profile first-enable gating
 *     (upstream v7, renumbered)
 *
 * Version 20:
 *   - run_logs: adds index on timestamp (reactive recent query + retention pruning)
 *   - edit_history: adds composite index on (entityType, entityId)
 *     (upstream v8, renumbered)
 *
 * Version 21 (current):
 *   - band_samples / band_daily / band_sleep / band_syncs: the Hume Band V2 health history.
 *     band_samples is keyed on (metric, localTs) where localTs is the BAND's own wall clock as
 *     yyyyMMddHHmmss — never epoch millis, so a re-sync in another timezone or across a DST
 *     fall-back hour cannot double a row. band_syncs is deliberately never pruned: its value is
 *     the multi-day series that measures the band's ring-buffer depth.
 *
 * Version 22:
 *   - edit_history: adds nextJson and isUndone so per-entity stacks support redo
 *     (upstream v10, renumbered)
 *
 * Version 23:
 *   - run_logs: adds executionId, replayOf, held, heldPayload, heldPolicy, starred — the
 *     admission-rejected HELD rows and their linked manual replay (upstream v11, renumbered)
 *
 * Version 24:
 *   - profiles: adds priority, gracePeriodSec, lifetime, expiresAtMs, lifetimeConsumed —
 *     priority arbitration and never/date/once lifetimes (upstream v12, renumbered)
 *
 * Version 25:
 *   - profiles: adds maxActiveExecutions, burstLimit, overflowPolicy — per-profile execution
 *     admission limits and overflow behaviour (upstream v13, renumbered)
 *
 * Version 26:
 *   - profiles: adds fallbackTaskId, the task run when an execution fails unhandled
 *     (upstream v14, renumbered)
 *
 * Version 27 (current):
 *   - execution_journal: admitted executions persisted with source/lineage and last known step,
 *     so a process death reconciles into one Interrupted run-log outcome (upstream v15,
 *     renumbered)
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
