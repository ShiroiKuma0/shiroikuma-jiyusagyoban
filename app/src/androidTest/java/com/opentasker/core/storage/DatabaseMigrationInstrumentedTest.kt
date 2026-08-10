package com.opentasker.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {
    @get:Rule
    val appDatabaseHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun appDatabaseMigratesFrom1To2AndPreservesProfileData() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO profiles (
                    id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson
                ) VALUES (
                    1, 'Morning focus', 1, 42, NULL, 15, '[]'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            2,
            true,
            DatabaseMigrations.MIGRATION_1_2,
        )

        migrated.query("SELECT name, enabled, enterTaskId, cooldownSec, automationMode FROM profiles WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Morning focus", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(42L, cursor.getLong(2))
                assertEquals(15, cursor.getInt(3))
                assertEquals("SINGLE", cursor.getString(4))
            }
    }

    @Test
    fun appDatabaseMigratesFrom3To4AndAddsNullableRunLogSourceColumns() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 3).apply {
            execSQL(
                """
                INSERT INTO run_logs (
                    id, taskId, taskName, timestamp, durationMs, success, message
                ) VALUES (
                    1, 7, 'Legacy run', 1000, 25, 1, 'Source: Profile: Old'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            4,
            true,
            DatabaseMigrations.MIGRATION_3_4,
        )

        // Legacy row is preserved and the new typed columns default to NULL.
        migrated.query("SELECT taskName, message, source, sourceLabel FROM run_logs WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy run", cursor.getString(0))
                assertEquals("Source: Profile: Old", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }

        // New rows can store typed source/sourceLabel values.
        migrated.execSQL(
            """
            INSERT INTO run_logs (
                id, taskId, taskName, timestamp, durationMs, success, message, source, sourceLabel
            ) VALUES (
                2, 8, 'Typed run', 2000, 30, 1, 'ok', 'profile', 'Night Mode'
            )
            """.trimIndent()
        )
        migrated.query("SELECT source, sourceLabel FROM run_logs WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("profile", cursor.getString(0))
            assertEquals("Night Mode", cursor.getString(1))
        }
    }

    @Test
    fun appDatabaseMigratesFrom2To3AndCreatesEditHistoryTable() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 2).apply {
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            3,
            true,
            DatabaseMigrations.MIGRATION_2_3,
        )

        migrated.execSQL(
            """
            INSERT INTO edit_history (
                id, entityType, entityId, previousJson, timestamp
            ) VALUES (
                1, 'profile', 42, '{"name":"old"}', 1000
            )
            """.trimIndent()
        )
        migrated.query("SELECT entityType, entityId, previousJson FROM edit_history WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("profile", cursor.getString(0))
                assertEquals(42L, cursor.getLong(1))
                assertEquals("{\"name\":\"old\"}", cursor.getString(2))
            }
    }

    @Test
    fun appDatabaseMigratesFrom4To5AndAddsNullableProfileGroup() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 4).apply {
            execSQL(
                """
                INSERT INTO profiles (
                    id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson, automationMode
                ) VALUES (
                    1, 'Bedtime', 1, 10, NULL, 0, '[]', 'SINGLE'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            5,
            true,
            DatabaseMigrations.MIGRATION_4_5,
        )

        migrated.query("SELECT name, automationMode, profileGroup FROM profiles WHERE id = 1")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Bedtime", cursor.getString(0))
                assertEquals("SINGLE", cursor.getString(1))
                assertTrue("profileGroup should default to NULL", cursor.isNull(2))
            }

        migrated.execSQL(
            """
            INSERT INTO profiles (
                id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson, automationMode, profileGroup
            ) VALUES (
                2, 'Work', 0, 11, NULL, 0, '[]', 'RESTART', 'Office')
            """.trimIndent()
        )
        migrated.query("SELECT profileGroup FROM profiles WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Office", cursor.getString(0))
        }
    }

    @Test
    fun appDatabaseMigratesFrom5To6AndFlagsOnlyLegacyMaskedVariables() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 5).apply {
            execSQL(
                "INSERT INTO variables (name, value, isGlobal) VALUES ('COUNT', '7', 1)",
            )
            execSQL(
                "INSERT INTO variables (name, value, isGlobal) VALUES ('API_TOKEN', 'legacy', 1)",
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            6,
            true,
            DatabaseMigrations.MIGRATION_5_6,
        )

        migrated.query("SELECT name, value, isSecret FROM variables WHERE name = 'COUNT'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("COUNT", cursor.getString(0))
            assertEquals("7", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        migrated.query("SELECT isSecret FROM variables WHERE name = 'API_TOKEN'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun appDatabaseMigratesFrom6To7WithExistingProfilesTrusted() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 6).apply {
            execSQL(
                """
                INSERT INTO profiles (
                    id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson,
                    automationMode, profileGroup
                ) VALUES (1, 'Existing profile', 1, 1, NULL, 0, '[]', 'SINGLE', NULL)
                """.trimIndent(),
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            7,
            true,
            DatabaseMigrations.MIGRATION_6_7,
        )

        migrated.query("SELECT requiresRiskAcknowledgement FROM profiles WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun appDatabaseMigratesFrom7To8WithIndexesCreated() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 7).apply {
            execSQL(
                """
                INSERT INTO run_logs (id, taskId, taskName, timestamp, durationMs, success, message)
                VALUES (1, 1, 'Indexed task', 1000, 5, 1, 'ok')
                """.trimIndent(),
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            8,
            true,
            DatabaseMigrations.MIGRATION_7_8,
        )

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN " +
                "('index_run_logs_timestamp', 'index_edit_history_entityType_entityId')",
        ).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) count++
            assertEquals(2, count)
        }
        migrated.query("SELECT taskName FROM run_logs WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Indexed task", cursor.getString(0))
        }
    }

    @Test
    fun appDatabaseMigratesFrom9To10WithRedoColumns() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 9).apply {
            execSQL(
                """
                INSERT INTO edit_history (id, entityType, entityId, previousJson, timestamp)
                VALUES (1, 'scene', 7, 'scene-a', 1000)
                """.trimIndent(),
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            10,
            true,
            DatabaseMigrations.MIGRATION_9_10,
        )

        migrated.query("SELECT previousJson, nextJson, isUndone FROM edit_history WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("scene-a", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
    }

    @Test
    fun appDatabaseMigratesFrom10To11WithHeldExecutionColumns() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 10).apply {
            execSQL(
                """
                INSERT INTO run_logs (id, taskId, taskName, timestamp, durationMs, success, message)
                VALUES (1, 7, 'Held task', 1000, 0, 0, 'Decision: Held')
                """.trimIndent(),
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            11,
            true,
            DatabaseMigrations.MIGRATION_10_11,
        )

        migrated.query(
            "SELECT executionId, replayOf, held, heldPayload, heldPolicy, starred FROM run_logs WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertEquals(0, cursor.getInt(5))
        }
    }

    @Test
    fun appDatabaseMigratesFrom14To15WithDurableExecutionJournal() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 14).apply {
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            15,
            true,
            DatabaseMigrations.MIGRATION_14_15,
        )

        migrated.execSQL(
            """
            INSERT INTO execution_journal (
                executionId, taskId, taskName, source, sourceLabel, profileId, replayOf,
                parentExecutionId, producer, startedAtMs, updatedAtMs, lastStepIndex,
                lastStepLabel, state, terminalReason, terminalAtMs
            ) VALUES (
                'migration-execution', 7, 'Migrated task', 'Profile: Night', 'Night', 42,
                'held-1', 'parent-1', 'profile', 1000, 1000, NULL, NULL, 'ACTIVE', NULL, NULL
            )
            """.trimIndent(),
        )
        migrated.query(
            "SELECT taskName, producer, state, runLogWritten FROM execution_journal " +
                "WHERE executionId = 'migration-execution'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Migrated task", cursor.getString(0))
            assertEquals("profile", cursor.getString(1))
            assertEquals("ACTIVE", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    @Test
    fun appDatabaseMigratesFullPathFrom1ToCurrent() {
        appDatabaseHelper.createDatabase(APP_DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO profiles (
                    id, name, enabled, enterTaskId, exitTaskId, cooldownSec, contextsJson
                ) VALUES (
                    1, 'Full path test', 1, 1, NULL, 0, '[]'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = appDatabaseHelper.runMigrationsAndValidate(
            APP_DATABASE_NAME,
            15,
            true,
            *DatabaseMigrations.getAllMigrations(),
        )

        migrated.query(
            "SELECT name, automationMode, profileGroup, requiresRiskAcknowledgement FROM profiles WHERE id = 1",
        )
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Full path test", cursor.getString(0))
                assertEquals("SINGLE", cursor.getString(1))
                assertTrue("profileGroup should be NULL after full migration", cursor.isNull(2))
                assertEquals(0, cursor.getInt(3))
            }
    }

    companion object {
        private const val APP_DATABASE_NAME = "app-migration-test.db"
    }
}
