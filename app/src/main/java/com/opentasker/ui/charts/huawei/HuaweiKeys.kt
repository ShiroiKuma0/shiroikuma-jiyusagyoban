package com.opentasker.ui.charts.huawei

/**
 * Chart keys for the Huawei band's metrics.
 *
 * **The prefix exists because the two bands' storage names collide.** `HuaweiSyncEngine` stores heart
 * rate as `"hr"` and blood oxygen as `"spo2"` — the same strings `BandMetric` uses for the Hume band.
 * Handed to `ChartStyle.colorFor` unprefixed, both devices resolve to the same colour, silently. For
 * a red-green-deficient reader comparing two bands that is not a cosmetic problem.
 *
 * **Storage keys stay bare.** `huawei_samples.metric` remains `"hr"`; the table is already namespaced
 * by being a different table, and re-prefixing every row would be a data migration for a display
 * concern. [qualify] and [storageKey] bridge the two, in one place, with a round-trip test.
 *
 * **The prefix is permanent.** It is not scaffolding to be stripped once the Huawei band becomes the
 * primary one: it records which wrist a series came from, which stays true afterwards. Stripping it
 * would rename across the spec table, `colorFor`, the settings enum and any `metric=` deep link
 * already saved in a workspace task, for no benefit.
 */
object HuaweiKeys {

    const val PREFIX = "hw:"

    const val HEART_RATE = "hw:hr"
    const val SPO2 = "hw:spo2"
    const val STEPS = "hw:steps"
    const val RESTING_HR = "hw:resting_hr"
    const val CALORIES = "hw:calories"
    const val DISTANCE = "hw:distance"

    /** A storage metric name as the charts key it. */
    fun qualify(storageKey: String): String =
        if (storageKey.startsWith(PREFIX)) storageKey else PREFIX + storageKey

    /** The inverse: a chart key back to the name the table stores. */
    fun storageKey(chartKey: String): String = chartKey.removePrefix(PREFIX)

    /** True for any key belonging to the Huawei band, including its undecoded fields. */
    fun isHuawei(key: String): Boolean = key.startsWith(PREFIX)
}
