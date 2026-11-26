package com.sysadmindoc.ruledeck.ui.screens

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.sysadmindoc.ruledeck.R
import java.io.ByteArrayOutputStream

/** Raw bytes, so a password-protected MacroDroid export can be told apart from text. */
internal fun readBoundedMacroDroidBytes(context: Context, uri: Uri): ByteArray {
    return readBoundedDocumentBytes(
        context = context,
        uri = uri,
        maxBytes = MACRODROID_IMPORT_MAX_BYTES,
        labelRes = R.string.import_document_macrodroid,
    )
}

internal fun readBoundedRuleDeckBundle(context: Context, uri: Uri): String {
    return readBoundedDocumentBytes(
        context = context,
        uri = uri,
        maxBytes = RULEDECK_BUNDLE_IMPORT_MAX_BYTES,
        labelRes = R.string.import_document_ruledeck_bundle,
    ).toString(Charsets.UTF_8)
}

internal fun readBoundedDocumentBytes(
    context: Context,
    uri: Uri,
    maxBytes: Int,
    @StringRes labelRes: Int,
): ByteArray {
    val label = context.getString(labelRes)
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw UiRejection(R.string.ui_error_document_unreadable, listOf(label))
    ByteArrayOutputStream().use { output ->
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > maxBytes) {
                    throw UiRejection(
                        R.string.ui_error_document_too_large,
                        listOf(label, maxBytes / (1024 * 1024)),
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
