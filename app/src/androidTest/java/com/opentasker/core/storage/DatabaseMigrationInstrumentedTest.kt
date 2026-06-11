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

    companion object {
        private const val APP_DATABASE_NAME = "app-migration-test.db"
    }
}
