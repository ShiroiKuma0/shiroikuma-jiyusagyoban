package com.opentasker.core.huawei

import android.content.Context
import com.opentasker.core.actions.HuaweiGnssAction
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The files the sync answers the watch's satellite asks with — see [HuaweiGnssServer].
 *
 * Both come from the same store the 衛星 tasks use (`user_files/gnss`), so a set built by 衛星 生成
 * is what the sync hands over, and a broadcast file the sync downloads is what those tasks find.
 */
object HuaweiGnssSupply {

    /** Huawei's open endpoint for the broadcast ephemeris — the same GET 衛星送信 makes, no account. */
    const val BROADCAST_URL = "https://geo-dre.platform.dbankcloud.com/higeo/v1/gnssinfo?type=0x0004"
    const val BROADCAST_NAME = "HW_AGNSS_RTCM_33"

    /**
     * How old a kept broadcast file may be when the download fails. GPS broadcast ephemeris is fitted
     * over four hours; older than that, declining is better than handing the watch a file it will
     * mark as current.
     */
    private const val BROADCAST_FALLBACK_MS = 4 * 3_600_000L

    /** A predicted set with less than this left is not handed over: it would need replacing within hours. */
    private const val PREDICTED_MIN_HOURS = 6L

    fun storeDir(context: Context) = File(File(context.filesDir, "user_files"), "gnss")

    /** What [broadcast] or [predicted] found, with a line for the sync summary. */
    data class Files(val files: Map<String, ByteArray>, val note: String, val untilMs: Long = 0L)

    /**
     * The broadcast file, downloaded now — it is a few kilobytes and a second's work, and fresh is
     * the whole point (Health's was minutes old). The download replaces the stored copy; when it
     * fails, the stored copy is used while it is under four hours old.
     */
    fun broadcast(context: Context, client: OkHttpClient = defaultClient()): Files? {
        val dir = storeDir(context)
        val kept = File(dir, BROADCAST_NAME)
        val fresh = runCatching {
            client.newCall(Request.Builder().url(BROADCAST_URL).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body?.bytes() ?: error("empty")
            }
        }
        fresh.getOrNull()?.let { raw ->
            val bytes = gunzip(raw)
            if (looksLikeRtcm(bytes)) {
                runCatching { dir.mkdirs(); kept.writeBytes(raw) }
                return Files(mapOf(BROADCAST_NAME to bytes), "broadcast downloaded")
            }
        }
        val why = fresh.exceptionOrNull()?.message ?: "not RTCM"
        if (kept.isFile && System.currentTimeMillis() - kept.lastModified() < BROADCAST_FALLBACK_MS) {
            val bytes = gunzip(kept.readBytes())
            if (looksLikeRtcm(bytes)) {
                val min = (System.currentTimeMillis() - kept.lastModified()) / 60_000
                return Files(mapOf(BROADCAST_NAME to bytes), "broadcast kept, $min min old (download: $why)")
            }
        }
        return null
    }

    /**
     * The predicted set in the store, when it is still worth having: at least one constellation live
     * for [PREDICTED_MIN_HOURS] more. A dead set is never handed over — the watch takes it, marks its
     * data current and stops asking (the Band's 19-minute fix of 2026-09-06).
     */
    fun predicted(context: Context): Files? {
        val dir = storeDir(context)
        val files = LinkedHashMap<String, ByteArray>()
        for (name in HuaweiGnssAction.DEFAULT_FILES) {
            if (!name.startsWith(HuaweiGnssAction.PREDICTED_PREFIX)) continue
            val f = File(dir, name)
            if (f.isFile) files[name] = gunzip(f.readBytes())
        }
        if (files.isEmpty()) return null
        val nowGps = System.currentTimeMillis() / 1000 - HuaweiGnssAction.GPS_UNIX_EPOCH + HuaweiGnssAction.GPS_LEAP_SECONDS
        var windowEnd = 0L
        for ((name, bytes) in files) {
            if (name == HuaweiGnssAction.PREDICTED_STATIC || name in HuaweiGnssAction.ALWAYS_STALE) continue
            val last = HuaweiGnssAction.coveredUntil(name, HuaweiGnssAction.lastBlockSeconds(bytes) ?: continue)
            if (last > nowGps && (windowEnd == 0L || last < windowEnd)) windowEnd = last
        }
        if (windowEnd == 0L || (windowEnd - nowGps) / 3600 < PREDICTED_MIN_HOURS) return null
        val untilMs = HuaweiGnssAction.unixOf(windowEnd)
        return Files(files, "forecast to ${HuaweiGnssAction.stamp(untilMs)} UTC", untilMs)
    }

    /**
     * Write what the sync served into the same records the 衛星 tasks keep: a line in
     * `served-log.txt` for each round, and — when the forecast was taken — `band-holds.txt` and the
     * expiry reminder.
     */
    fun record(context: Context, rounds: List<HuaweiGnssServer.Round>, predictedUntilMs: Long, log: (String) -> Unit) {
        val dir = storeDir(context)
        for (r in rounds) {
            val kind = r.want.name.lowercase()
            HuaweiGnssAction.recordServed(
                dir,
                when {
                    r.offered.isEmpty() -> "SYNC · the watch asked for $kind · declined"
                    r.served.isEmpty() -> "SYNC · the watch asked for $kind · TOOK NOTHING · offered ${r.offered.size}" +
                        (if (r.note.isEmpty()) "" else " · ${r.note}")
                    else -> "SYNC · took ${r.served.size} file(s), ${r.bytes} B · offered ${r.offered.size}" +
                        (if (r.want == HuaweiGnssServer.Want.PREDICTED && predictedUntilMs > 0)
                            " · the watch now holds a forecast to ${HuaweiGnssAction.stamp(predictedUntilMs)} UTC" else "")
                },
                log,
            )
            if (r.want == HuaweiGnssServer.Want.PREDICTED && r.served.isNotEmpty() && predictedUntilMs > 0) {
                HuaweiGnssAction.writeBandHolds(dir, predictedUntilMs, log)
                GnssForecastReminder.arm(context, predictedUntilMs, System.currentTimeMillis())
                GnssRebuildSchedule.forecastTaken(context)
            }
        }
    }

    /** RTCM 3 frames start with the preamble 0xD3. */
    private fun looksLikeRtcm(b: ByteArray) = b.size in 64..65_536 && b[0] == 0xD3.toByte()

    private fun gunzip(raw: ByteArray): ByteArray {
        if (raw.size < 2 || raw[0] != 0x1F.toByte() || raw[1] != 0x8B.toByte()) return raw
        return runCatching { java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } }.getOrElse { raw }
    }

    private fun defaultClient() = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}
