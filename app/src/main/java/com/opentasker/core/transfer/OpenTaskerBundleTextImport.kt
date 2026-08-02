package com.opentasker.core.transfer

import java.nio.charset.StandardCharsets

const val OPEN_TASKER_BUNDLE_TEXT_MAX_BYTES = 8 * 1024 * 1024

/** Decodes clipboard/QR text through the same bounded bundle codec as file imports. */
object OpenTaskerBundleTextImport {
    fun decode(rawText: String): OpenTaskerBundle {
        return OpenTaskerBundleCodec.decode(normalize(rawText))
    }

    internal fun normalize(rawText: String): String {
        val text = rawText.removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "OpenTasker bundle text is empty." }
        require(text.length <= OPEN_TASKER_BUNDLE_TEXT_MAX_BYTES) {
            "OpenTasker bundle text is larger than 8 MB."
        }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= OPEN_TASKER_BUNDLE_TEXT_MAX_BYTES) {
            "OpenTasker bundle text is larger than 8 MB."
        }
        return text
    }
}
