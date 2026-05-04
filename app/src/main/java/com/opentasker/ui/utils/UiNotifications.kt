package com.opentasker.ui.utils

import android.content.Context
import android.widget.Toast
import com.opentasker.app.R

/**
 * Utility for showing user feedback via Toast messages.
 */
object UiNotifications {
    
    fun showSuccess(context: Context, message: String) {
        Toast.makeText(context, context.getString(R.string.success_prefix, message), Toast.LENGTH_SHORT).show()
    }
    
    fun showError(context: Context, message: String) {
        Toast.makeText(context, context.getString(R.string.error_prefix, message), Toast.LENGTH_LONG).show()
    }
    
    fun showInfo(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
