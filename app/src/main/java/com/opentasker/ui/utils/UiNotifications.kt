package com.opentasker.ui.utils

import android.content.Context
import android.widget.Toast
import com.opentasker.app.R

/**
 * Utility for showing user feedback via Toast messages.
 */
object UiNotifications {

    internal fun successMessage(message: String, format: (Int, String) -> String): String =
        format(R.string.success_prefix, message)

    internal fun errorMessage(message: String, format: (Int, String) -> String): String =
        format(R.string.error_prefix, message)

    internal fun infoMessage(message: String): String = message
    
    fun showSuccess(context: Context, message: String) {
        Toast.makeText(
            context,
            successMessage(message) { resourceId, value -> context.getString(resourceId, value) },
            Toast.LENGTH_SHORT,
        ).show()
    }
    
    fun showError(context: Context, message: String) {
        Toast.makeText(
            context,
            errorMessage(message) { resourceId, value -> context.getString(resourceId, value) },
            Toast.LENGTH_LONG,
        ).show()
    }
    
    fun showInfo(context: Context, message: String) {
        Toast.makeText(context, infoMessage(message), Toast.LENGTH_SHORT).show()
    }
}
