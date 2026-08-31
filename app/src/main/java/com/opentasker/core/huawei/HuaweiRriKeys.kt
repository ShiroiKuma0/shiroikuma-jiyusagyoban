package com.opentasker.core.huawei

/**
 * Storage keys for the RR-interval windows.
 *
 * Only the two fields established against Huawei Health's own displayed values get names. The rest
 * keep their field number, exactly as unrecognised feature bits do elsewhere in this package: a
 * numbered key is honest about not knowing, and a guessed name would be believed by every chart and
 * every reader downstream. Renaming later is a migration; un-believing a wrong name is not.
 */
object HuaweiRriKeys {

    /** Field 1 — intervals the band accepted in the window. */
    const val COUNT = "rri_count"

    /** Field 6 — mean RR interval in milliseconds, in 20 ms steps. */
    const val MEAN_MS = "rri_mean_ms"

    fun metricFor(field: Int): String = when (field) {
        1 -> COUNT
        6 -> MEAN_MS
        else -> "rri_f$field"
    }
}
