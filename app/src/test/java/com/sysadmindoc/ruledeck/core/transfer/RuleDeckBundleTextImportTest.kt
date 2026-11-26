package com.sysadmindoc.ruledeck.core.transfer

import com.sysadmindoc.ruledeck.core.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleDeckBundleTextImportTest {
    @Test
    fun validClipboardTextUsesTheBundleCodec() {
        val bundle = RuleDeckBundle(
            appVersion = "0.2.79",
            exportedAtEpochMs = 1L,
            projects = listOf(Project(id = 1L, name = "Default")),
        )

        val decoded = RuleDeckBundleTextImport.decode(
            "\uFEFF  ${RuleDeckBundleCodec.encode(bundle)}  ",
        )

        assertEquals(bundle, decoded)
    }

    @Test
    fun malformedClipboardTextIsRejectedBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleDeckBundleTextImport.decode("not a bundle")
        }
    }

    @Test
    fun oversizedClipboardTextIsRejectedBeforeDecode() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleDeckBundleTextImport.decode("{" + "x".repeat(RULEDECK_BUNDLE_TEXT_MAX_BYTES) + "}")
        }
    }
}
