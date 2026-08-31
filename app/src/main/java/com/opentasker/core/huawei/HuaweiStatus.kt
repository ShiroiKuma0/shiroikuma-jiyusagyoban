package com.opentasker.core.huawei

/**
 * What the sync history says about the band, as pure arithmetic over a list of rows.
 *
 * The Room read lives in the runner and the sums live here, so the part that can be wrong is the
 * part that is unit-tested.
 *
 * ## Two figures the Hume band has that this one deliberately does NOT
 *
 * **No `headroom`.** The Hume band models per-stream eviction from a measured floor that advances
 * as the band overwrites itself. Nothing equivalent has been measured here. [observedDepthSec] is
 * the deepest this band has ever actually answered from — a **floor**, not a capacity — and the UI
 * must word it as "at least N h observed". Calling it headroom would turn a lower bound into a
 * promise, which is exactly the mistake the Hume notes warn against.
 *
 * **No `pressurePct`.** That figure divides by a measured depth. With no measured depth there is no
 * pressure to report, and returning a number would be fabricating one. The Huawei auto-sync warns
 * on age instead, until the depth probe has a week of rows behind it.
 */
data class HuaweiStatus(
    /** Epoch millis of the last sync that finished successfully, or null if none ever has. */
    val lastSuccessAtMillis: Long?,
    val syncCount: Int,
    val okCount: Int,
    val batteryPct: Int?,
    /** When [batteryPct] was read. A battery figure without its age is not a reading. */
    val batteryAtMillis: Long?,
    val firmware: String?,
    val oldestSampleSeconds: Long?,
    val newestSampleSeconds: Long?,
    /**
     * The largest span the band has ever actually answered from, in seconds: for each successful
     * sync, its requested end minus the oldest sample it really returned. Null until measured —
     * **never 0**, because "not measured" and "measured as nothing" are different claims.
     */
    val observedDepthSec: Long?,
    val lastRecordCount: Int,
    val lastRecordsFetched: Int,
    /** Records the band refused or dropped on the last sync. Expected to be 0; anything else matters. */
    val lastMissingCount: Int,
) {
    fun ageHours(nowMillis: Long): Double? =
        lastSuccessAtMillis?.let { (nowMillis - it) / 3_600_000.0 }

    fun batteryAgeHours(nowMillis: Long): Double? =
        batteryAtMillis?.let { (nowMillis - it) / 3_600_000.0 }

    val observedDepthHours: Double? get() = observedDepthSec?.let { it / 3_600.0 }

    /** How much data we hold, which grows past the band's own buffer as syncs accumulate. */
    val heldSpanSeconds: Long?
        get() = if (oldestSampleSeconds != null && newestSampleSeconds != null) {
            newestSampleSeconds - oldestSampleSeconds
        } else {
            null
        }

    companion object {
        /** One `huawei_syncs` row, projected so this file needs no Room. */
        data class Row(
            /** Epoch millis. */
            val startedAt: Long,
            /** Epoch millis; 0 while a sync is still in flight or was lost mid-run. */
            val finishedAt: Long,
            val ok: Boolean,
            val firmware: String?,
            val battery: Int?,
            /** Epoch seconds. */
            val requestedTo: Long,
            /** Epoch seconds of the oldest sample this sync really returned; null if it returned none. */
            val oldestReturnedSeconds: Long?,
            val recordCount: Int,
            val recordsFetched: Int,
        )

        val EMPTY = HuaweiStatus(
            lastSuccessAtMillis = null, syncCount = 0, okCount = 0,
            batteryPct = null, batteryAtMillis = null, firmware = null,
            oldestSampleSeconds = null, newestSampleSeconds = null, observedDepthSec = null,
            lastRecordCount = 0, lastRecordsFetched = 0, lastMissingCount = 0,
        )

        /**
         * @param rows newest first, as `HuaweiSyncDao.recent` returns them.
         */
        fun from(
            rows: List<Row>,
            oldestSampleSeconds: Long?,
            newestSampleSeconds: Long?,
        ): HuaweiStatus {
            if (rows.isEmpty()) {
                return EMPTY.copy(
                    oldestSampleSeconds = oldestSampleSeconds,
                    newestSampleSeconds = newestSampleSeconds,
                )
            }
            val newest = rows.first()
            // A sync that connected far enough to read the battery still knows the battery, even if
            // the history fetch then failed — so this scans every row, not only the successful ones.
            val withBattery = rows.firstOrNull { it.battery != null }
            val depth = rows
                .filter { it.ok && it.oldestReturnedSeconds != null }
                .maxOfOrNull { it.requestedTo - it.oldestReturnedSeconds!! }
                ?.takeIf { it > 0 }
            return HuaweiStatus(
                // Both conditions, together: a row that is ok but never finished was lost
                // mid-run, and skipping past it to the last sync that really completed is the
                // whole point of recording finishedAt separately.
                lastSuccessAtMillis = rows.firstOrNull { it.ok && it.finishedAt != 0L }?.finishedAt,
                syncCount = rows.size,
                okCount = rows.count { it.ok },
                batteryPct = withBattery?.battery,
                batteryAtMillis = withBattery?.startedAt,
                firmware = rows.firstOrNull { it.firmware != null }?.firmware,
                oldestSampleSeconds = oldestSampleSeconds,
                newestSampleSeconds = newestSampleSeconds,
                observedDepthSec = depth,
                lastRecordCount = newest.recordCount,
                lastRecordsFetched = newest.recordsFetched,
                lastMissingCount = (newest.recordCount - newest.recordsFetched).coerceAtLeast(0),
            )
        }
    }
}
