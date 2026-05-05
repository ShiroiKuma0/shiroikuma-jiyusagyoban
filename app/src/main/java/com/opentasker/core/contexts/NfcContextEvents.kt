package com.opentasker.core.contexts

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

object NfcContextEvents {
    private val tags = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<ContextEvent> = tags.asSharedFlow()

    fun publishFromIntent(intent: Intent): Boolean {
        val event = buildEventFromIntent(intent) ?: return false
        return tags.tryEmit(event)
    }

    fun buildEvent(
        tagId: ByteArray,
        techList: List<String> = emptyList(),
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", "nfc")
            put("tagId", tagIdHex(tagId))
            if (techList.isNotEmpty()) {
                put("techList", techList.sorted().joinToString(","))
            }
        },
    )

    fun tagIdHex(tagId: ByteArray): String =
        tagId.joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    fun normalizeTagId(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase(Locale.US)

    private fun buildEventFromIntent(intent: Intent): ContextEvent? {
        if (intent.action !in NFC_ACTIONS) return null
        val tag = intent.nfcTag() ?: return null
        val id = tag.id?.takeIf { it.isNotEmpty() } ?: return null
        return buildEvent(id, tag.techList?.toList().orEmpty())
    }

    @Suppress("DEPRECATION")
    private fun Intent.nfcTag(): Tag? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private val NFC_ACTIONS = setOf(
        NfcAdapter.ACTION_TAG_DISCOVERED,
        NfcAdapter.ACTION_TECH_DISCOVERED,
        NfcAdapter.ACTION_NDEF_DISCOVERED,
    )
}
