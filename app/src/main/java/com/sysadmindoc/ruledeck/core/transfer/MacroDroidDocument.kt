package com.sysadmindoc.ruledeck.core.transfer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** What a picked MacroDroid export turned out to hold. */
internal sealed interface MacroDroidDocument {
    data class Text(val text: String) : MacroDroidDocument

    /**
     * Binary that isn't text, in whole 16-byte blocks: a password-protected `.emdr`. Those can't be
     * read here, so the user is asked to export again with the password left blank.
     */
    data object PasswordProtected : MacroDroidDocument
    data object Unrecognized : MacroDroidDocument

    companion object {
        private const val ENCRYPTED_BLOCK_BYTES = 16

        /**
         * Random bytes stop being valid UTF-8 within a few characters, so a file that fails to
         * decode and comes in whole blocks is a protected export. Anything else counts only when it
         * opens like a JSON object, which every MacroDroid export does; text in some other encoding
         * still gets through that way.
         */
        fun classify(bytes: ByteArray): MacroDroidDocument {
            val strict = decodeStrictUtf8(bytes)
            if (strict == null && bytes.isNotEmpty() && bytes.size % ENCRYPTED_BLOCK_BYTES == 0) {
                return PasswordProtected
            }
            val text = strict ?: bytes.toString(Charsets.UTF_8)
            val opensLikeJson = text.trimStart { it == BYTE_ORDER_MARK_CHAR || it.isWhitespace() }.startsWith("{")
            return if (opensLikeJson) Text(text) else Unrecognized
        }
    }
}

private val BYTE_ORDER_MARK_CHAR = Char(0xFEFF)

private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}
