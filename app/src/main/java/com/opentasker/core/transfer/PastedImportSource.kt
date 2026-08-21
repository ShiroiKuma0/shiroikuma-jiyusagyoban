package com.opentasker.core.transfer

import java.nio.charset.StandardCharsets

/** What a pasted import body turned out to be. */
enum class PastedImportKind { OPEN_TASKER_JSON, TASKER_XML }

/**
 * Decides what the paste dialog was given.
 *
 * The dialog was JSON-only while the document picker already accepted Tasker XML, so a user
 * migrating from Tasker could import a file but not a copied task. The sniff is deliberately
 * cheap and structural: it decides which decoder runs, and that decoder still applies its own
 * budgets, sanitizer and validation before anything reaches Room.
 */
object PastedImportSource {
    const val TASKER_XML_TEXT_MAX_BYTES: Int = 4 * 1024 * 1024

    private const val BYTE_ORDER_MARK = "\uFEFF"

    fun normalize(rawText: String): String = rawText.removePrefix(BYTE_ORDER_MARK).trim()

    fun classify(rawText: String): PastedImportKind {
        val text = normalize(rawText)
        return if (text.startsWith("<")) PastedImportKind.TASKER_XML else PastedImportKind.OPEN_TASKER_JSON
    }

    /**
     * Tasker XML pasted as text gets the same ceiling as a Tasker XML file. Checked before the
     * parser so an oversize paste is refused without building a document.
     */
    fun requireTaskerXmlWithinBudget(rawText: String, maxBytes: Int = TASKER_XML_TEXT_MAX_BYTES): String {
        val text = normalize(rawText)
        require(text.isNotEmpty()) { "Tasker XML text is empty." }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
            "Tasker XML text is larger than ${maxBytes / (1024 * 1024)} MB."
        }
        return text
    }
}
