package com.opentasker.core.transfer

import com.opentasker.core.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenTaskerBundleTextImportTest {
    @Test
    fun validClipboardTextUsesTheBundleCodec() {
        val bundle = OpenTaskerBundle(
            appVersion = "0.2.79",
            exportedAtEpochMs = 1L,
            projects = listOf(Project(id = 1L, name = "Default")),
        )

        val decoded = OpenTaskerBundleTextImport.decode(
            "\uFEFF  ${OpenTaskerBundleCodec.encode(bundle)}  ",
        )

        assertEquals(bundle, decoded)
    }

    @Test
    fun malformedClipboardTextIsRejectedBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenTaskerBundleTextImport.decode("not a bundle")
        }
    }

    @Test
    fun oversizedClipboardTextIsRejectedBeforeDecode() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenTaskerBundleTextImport.decode("{" + "x".repeat(OPEN_TASKER_BUNDLE_TEXT_MAX_BYTES) + "}")
        }
    }
}
