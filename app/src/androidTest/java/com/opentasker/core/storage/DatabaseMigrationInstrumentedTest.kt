package com.opentasker.core.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.automation.data.AutomationDatabase
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

    @get:Rule
    val automationDatabaseHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AutomationDatabase::class.java,
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
    fun automationDatabaseVersion1SchemaValidates() {
        automationDatabaseHelper.createDatabase(AUTOMATION_DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO automation_rules (
                    id, name, description, enabled, profileId, ruleJson, executionMode, createdAt, updatedAt
                ) VALUES (
                    'rule-1', 'WiFi arrival', 'Enable focus mode', 1, 'profile-1', '{}', 'SINGLE', 100, 200
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO execution_logs (
                    id, ruleId, ruleName, triggerId, triggerType, timestamp, status, message, executionTimeMs, actionResultsJson
                ) VALUES (
                    'log-1', 'rule-1', 'WiFi arrival', 'trigger-1', 'wifi', 300, 'SUCCESS', 'Completed', 20, '[]'
                )
                """.trimIndent()
            )
            close()
        }

        val validated = automationDatabaseHelper.runMigrationsAndValidate(
            AUTOMATION_DATABASE_NAME,
            1,
            true,
        )

        assertTableCount(validated, "automation_rules", 1)
        assertTableCount(validated, "execution_logs", 1)
    }

    private fun assertTableCount(db: SupportSQLiteDatabase, tableName: String, expectedCount: Int) {
        db.query("SELECT COUNT(*) FROM $tableName").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedCount, cursor.getInt(0))
        }
    }

    companion object {
        private const val APP_DATABASE_NAME = "app-migration-test.db"
        private const val AUTOMATION_DATABASE_NAME = "automation-migration-test.db"
    }
}
