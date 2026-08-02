package com.opentasker.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
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
        plaintext.close()

        val databaseFile = context.getDatabasePath(TEST_DATABASE)
        assertTrue(DatabaseSecurity.isPlaintext(databaseFile))

        val key = DatabaseSecurity.prepareEncryptedDatabase(context, TEST_DATABASE)
        assertFalse(DatabaseSecurity.isPlaintext(databaseFile))

        val encrypted = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DATABASE)
            .addMigrations(*DatabaseMigrations.getAllMigrations())
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .allowMainThreadQueries()
            .build()
        try {
            assertTrue(encrypted.profileDao().getAll().isEmpty())
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
