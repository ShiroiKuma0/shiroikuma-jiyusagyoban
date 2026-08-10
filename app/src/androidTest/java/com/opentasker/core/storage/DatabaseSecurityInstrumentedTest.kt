package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.Profile
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSecurityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        context.deleteDatabase(TEST_DATABASE)
        context.getSharedPreferences("database_security", 0).edit().clear().commit()
    }

    @Test
    fun plaintextDatabaseMigratesBeforeEncryptedRoomOpenAndRejectsWrongKey() = runBlocking {
        context.deleteDatabase(TEST_DATABASE)
        val plaintext = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .allowMainThreadQueries()
            .build()
        // Room opens lazily, so without a query the file is never created and the plaintext
        // assertion below passes over a database that does not exist.
        plaintext.profileDao().insert(Profile(name = "Legacy profile", enterTaskId = 1).toEntity())
        plaintext.close()

        val databaseFile = context.getDatabasePath(TEST_DATABASE)
        assertTrue(DatabaseSecurity.isPlaintext(databaseFile))

        val key = DatabaseSecurity.prepareEncryptedDatabase(context, TEST_DATABASE)
        assertFalse(DatabaseSecurity.isPlaintext(databaseFile))

        val encrypted = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(*DatabaseMigrations.getManualMigrations())
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .allowMainThreadQueries()
            .build()
        try {
            // The migration must carry the user's data across, not just produce a valid file.
            assertEquals(listOf("Legacy profile"), encrypted.profileDao().getAll().map { it.name })
        } finally {
            encrypted.close()
        }

        val wrongKey = ByteArray(key.size) { 7 }
        var rejected = false
        runCatching {
            val wrongDatabase = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
                .openHelperFactory(SupportOpenHelperFactory(wrongKey))
                .allowMainThreadQueries()
                .build()
            try {
                wrongDatabase.profileDao().getAll()
            } finally {
                wrongDatabase.close()
            }
        }.onFailure { rejected = true }
        assertTrue("wrong SQLCipher key must fail closed", rejected)
    }

    companion object {
        private const val TEST_DATABASE = "database-security-test.db"
    }
}
