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

    companion object {
        private const val APP_DATABASE_NAME = "app-migration-test.db"
    }
}
