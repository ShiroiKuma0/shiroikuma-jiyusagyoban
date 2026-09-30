package com.opentasker.ui.screens

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Date and time for the screen, in the device's own field order and its 12- or 24-hour clock.
 *
 * The Inspector, the run log and Diagnostics used a fixed `yyyy-MM-dd HH:mm:ss`, so a phone set
 * to US English and a 12-hour clock still saw ISO dates on a 24-hour clock (A-363). Export
 * filenames deliberately keep their own fixed `Locale.US` pattern (see databaseBackupExportName):
 * a filename has to sort in order and stay stable, which is the opposite of what a reader wants.
 */
internal fun displayDateTimeFormat(context: Context, withSeconds: Boolean = true): SimpleDateFormat {
    val locale = Locale.getDefault()
    val clock = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val skeleton = "yMMMd" + clock + if (withSeconds) "s" else ""
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}
