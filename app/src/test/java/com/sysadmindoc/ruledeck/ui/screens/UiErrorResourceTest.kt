package com.sysadmindoc.ruledeck.ui.screens

import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.storage.CorruptStoredRecordException
import com.sysadmindoc.ruledeck.core.storage.StorageDecodeIssue
import com.sysadmindoc.ruledeck.core.storage.StorageRecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class UiErrorResourceTest {
    @Test
    fun unknownFailuresUseTheCallerFallbackWithoutExposingRawText() {
        assertEquals(
            R.string.ui_error_backup,
            uiErrorResource(IllegalStateException("SQLite disk path /private/details"), R.string.ui_error_backup),
        )
    }

    @Test
    fun corruptStorageFailuresUseTheRecoveryMessageResource() {
        val issue = StorageDecodeIssue(
            recordType = StorageRecordType.PROFILE,
            recordId = 7L,
            recordName = "Morning",
            fieldName = "contextsJson",
            message = "malformed payload",
        )

        assertEquals(
            R.string.ui_error_corrupt_record,
            uiErrorResource(CorruptStoredRecordException(issue), R.string.ui_error_generic),
        )
        assertEquals(
            R.string.ui_error_corrupt_record,
            uiErrorResource(CorruptRecordOverwriteException(issue), R.string.ui_error_generic),
        )
    }
}
