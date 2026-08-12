package com.opentasker.core.storage

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationSnapshotSettingsInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val preferences by lazy {
        context.getSharedPreferences("configuration_snapshots", android.content.Context.MODE_PRIVATE)
    }

    @Before
    @After
    fun clearSettings() {
        preferences.edit().clear().commit()
    }

    @Test
    fun destinationPolicyStatusAndWrappedPassphraseSurviveReload() {
        val settings = ConfigurationSnapshotSettings(context)
        val passphrase = "correct horse battery staple".toCharArray()
        settings.save(
            ConfigurationSnapshotPolicy(
                enabled = true,
                maxSnapshots = 10,
                maxAgeDays = 30,
                destinationTreeUri = "content://provider/tree/snapshots",
            ),
        )
        settings.saveRecoveryPassphrase(passphrase)
        settings.recordSuccess(atMs = 123_456L, snapshotCount = 4, storageBytes = 9_876L)

        assertEquals("content://provider/tree/snapshots", settings.load().destinationTreeUri)
        assertEquals("correct horse battery staple", settings.loadRecoveryPassphrase().concatToString())
        assertFalse(
            preferences.getString("recovery_passphrase", "").orEmpty().contains("correct horse"),
        )
        assertEquals(
            ConfigurationSnapshotStatus(lastSuccessAtMs = 123_456L, snapshotCount = 4, storageBytes = 9_876L),
            settings.loadStatus(),
        )
    }

    @Test
    fun clearingTheCachedPassphraseMakesTheMissingCredentialExplicit() {
        val settings = ConfigurationSnapshotSettings(context)
        settings.saveRecoveryPassphrase("temporary recovery phrase".toCharArray())
        settings.clearRecoveryPassphrase()

        assertNull(preferences.getString("recovery_passphrase", null))
        try {
            settings.loadRecoveryPassphrase()
            fail("Expected a missing snapshot passphrase failure")
        } catch (error: IllegalStateException) {
            assertEquals("Snapshot recovery passphrase is not configured", error.message)
        }
    }

    @Test
    fun missingPersistedTreeGrantHasAUserActionableFailure() {
        val failure = try {
            ConfigurationSnapshotArchiveStore(context).requirePersistedAccess(
                Uri.parse("content://com.opentasker.missing/tree/snapshots"),
            )
            fail("Expected an unavailable snapshot destination")
            null
        } catch (error: SnapshotDestinationUnavailableException) {
            error
        }

        assertEquals(ConfigurationSnapshotArchiveStore.DESTINATION_UNAVAILABLE_MESSAGE, failure?.message)
    }
}
