package com.sysadmindoc.ruledeck.core.transfer

import java.nio.charset.StandardCharsets

const val RULEDECK_BUNDLE_TEXT_MAX_BYTES = 8 * 1024 * 1024

/** Decodes clipboard/QR text through the same bounded bundle codec as file imports. */
object RuleDeckBundleTextImport {
    fun decode(rawText: String): RuleDeckBundle {
        return RuleDeckBundleCodec.decode(normalize(rawText))
    }

    internal fun normalize(rawText: String): String {
        val text = rawText.removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "RuleDeck bundle text is empty." }
        require(text.length <= RULEDECK_BUNDLE_TEXT_MAX_BYTES) {
            "RuleDeck bundle text is larger than 8 MB."
        }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= RULEDECK_BUNDLE_TEXT_MAX_BYTES) {
            "RuleDeck bundle text is larger than 8 MB."
        }
        return text
    }
}
