package com.opentasker.core.contexts

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class NfcTagWritePlan(
    val text: String,
    val estimatedBytes: Int,
)

data class NfcTagWriteResult(
    val success: Boolean,
    val message: String,
)

object NfcTagWritePlanner {
    private const val MAX_TEXT_CHARS = 120
    private const val DEFAULT_TEXT = "白い熊 自由作業盤 NFC trigger"

    fun planTextRecord(label: String): NfcTagWritePlan {
        val text = normalizeLabel(label).ifBlank { DEFAULT_TEXT }
        val languageBytes = "en".toByteArray(Charsets.US_ASCII).size
        val textBytes = text.toByteArray(Charsets.UTF_8).size
        return NfcTagWritePlan(
            text = text,
            estimatedBytes = 1 + languageBytes + textBytes,
        )
    }

    fun normalizeLabel(label: String): String =
        label
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TEXT_CHARS)
}

object NfcTagWriteSession {
    private val writeResults = MutableSharedFlow<NfcTagWriteResult>(extraBufferCapacity = 8)

    @Volatile
    private var pendingPlan: NfcTagWritePlan? = null

    val results: SharedFlow<NfcTagWriteResult> = writeResults.asSharedFlow()

    fun armTextRecord(label: String): NfcTagWriteResult {
        val plan = NfcTagWritePlanner.planTextRecord(label)
        pendingPlan = plan
        return publish(
            NfcTagWriteResult(
                success = true,
                message = "NFC write armed for a ${plan.estimatedBytes}-byte text record.",
            )
        )
    }

    fun writeFromIntent(intent: Intent): NfcTagWriteResult? {
        val plan = pendingPlan ?: return null
        val tag = intent.nfcTag()
            ?: return publish(NfcTagWriteResult(false, "NFC write failed: no tag was attached to the scan intent."))
        val result = runCatching {
            writeTextRecord(tag, plan)
        }.getOrElse { error ->
            NfcTagWriteResult(false, "NFC write failed: ${error.message ?: error::class.java.simpleName}")
        }
        pendingPlan = null
        return publish(result)
    }

    private fun writeTextRecord(tag: Tag, plan: NfcTagWritePlan): NfcTagWriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", plan.text)))
        val payloadSize = message.toByteArray().size
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) {
                    return NfcTagWriteResult(false, "NFC tag is read-only.")
                }
                if (ndef.maxSize < payloadSize) {
                    return NfcTagWriteResult(false, "NFC tag is too small for a $payloadSize-byte record.")
                }
                ndef.writeNdefMessage(message)
                return NfcTagWriteResult(true, "NFC tag written with 白い熊 自由作業盤 helper text.")
            } finally {
                runCatching { ndef.close() }
            }
        }

        val formatable = NdefFormatable.get(tag)
            ?: return NfcTagWriteResult(false, "NFC tag does not support NDEF writes.")
        formatable.connect()
        try {
            formatable.format(message)
            return NfcTagWriteResult(true, "NFC tag formatted and written with 白い熊 自由作業盤 helper text.")
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun publish(result: NfcTagWriteResult): NfcTagWriteResult {
        writeResults.tryEmit(result)
        return result
    }

    @Suppress("DEPRECATION")
    private fun Intent.nfcTag(): Tag? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
}
