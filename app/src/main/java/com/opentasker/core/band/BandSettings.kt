package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * Connection settings, in SharedPreferences.
 *
 * These are defaults. The 「健康」 project's `01` task is where 白い熊 actually edits any of it — the
 * band.sync Action takes the same values as arguments, so a Profile can override them per run without
 * touching anything stored.
 */
object BandSettings {
    private const val PREFS = "band_settings"
    private const val KEY_ADDRESS = "address"
    private const val KEY_BACKUP_DIR = "backup_dir"
    private const val KEY_STREAMS = "streams"
    private const val KEY_OVERLAP_MIN = "overlap_minutes"
    private const val KEY_TIMEOUT_SEC = "timeout_sec"
    private const val KEY_LANGUAGE = "language"

    /**
     * 白い熊's band. A static random address, so it survives reboots — but it WOULD change if the
     * band were factory-reset, which is why it is a setting rather than a constant.
     */
    const val DEFAULT_ADDRESS = "D5:A7:06:DC:A1:3A"

    /** Re-request this much of what we already have. Overlap is free: the dedupe key discards it. */
    const val DEFAULT_OVERLAP_MINUTES = 30

    const val DEFAULT_TIMEOUT_SEC = 180

    /** The backup root CLAUDE.md designates for this app, with the band's own subdirectory. */
    val DEFAULT_BACKUP_DIR: String =
        "/sdcard/〇/[979] バックアップ/[979][60792] 白い熊 自由作業盤/band"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun address(context: Context): String =
        prefs(context).getString(KEY_ADDRESS, null)?.trim()?.ifEmpty { null } ?: DEFAULT_ADDRESS

    fun setAddress(context: Context, value: String) =
        prefs(context).edit { putString(KEY_ADDRESS, value.trim()) }

    fun backupDir(context: Context): File =
        File(prefs(context).getString(KEY_BACKUP_DIR, null)?.trim()?.ifEmpty { null } ?: DEFAULT_BACKUP_DIR)

    fun setBackupDir(context: Context, value: String) =
        prefs(context).edit { putString(KEY_BACKUP_DIR, value.trim()) }

    /** Blank means every stream. A comma list narrows it, by [BandStream.key]. */
    fun streams(context: Context): List<BandStream> {
        val raw = prefs(context).getString(KEY_STREAMS, null)?.trim().orEmpty()
        return parseStreams(raw)
    }

    fun setStreams(context: Context, value: String) =
        prefs(context).edit { putString(KEY_STREAMS, value.trim()) }

    fun overlapMinutes(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAP_MIN, DEFAULT_OVERLAP_MINUTES)

    fun setOverlapMinutes(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_OVERLAP_MIN, value.coerceIn(0, 24 * 60)) }

    /**
     * Which language 「健康」 displays in — an IETF tag, `en-US` or `ja-JP`.
     *
     * Persisted rather than passed per launch because the window can be resumed by the system long
     * after the task that opened it finished, and it must come back up in the same language.
     * `健康の設定 -- [727][01]` writes it through the `band.charts` action's `lang` argument.
     */
    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, null)?.trim()?.ifEmpty { null } ?: DEFAULT_LANGUAGE

    fun setLanguage(context: Context, value: String) =
        prefs(context).edit { putString(KEY_LANGUAGE, value.trim()) }

    /** 白い熊 asked for these tables in English (2026-08-06). */
    const val DEFAULT_LANGUAGE = "en-US"

    fun timeoutSec(context: Context): Int =
        prefs(context).getInt(KEY_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC)

    fun setTimeoutSec(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_TIMEOUT_SEC, value.coerceIn(15, 600)) }

    /**
     * Blank or unrecognised → every stream, in the sync order.
     *
     * Unrecognised rather than rejected on purpose: a typo in the `01` task should not stop a sync
     * dead, and asking for all twelve is never wrong — the five dead slots cost one round trip each.
     */
    fun parseStreams(raw: String): List<BandStream> {
        val wanted = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return BandStream.SYNC_ORDER
        val picked = BandStream.SYNC_ORDER.filter { it.key in wanted }
        return picked.ifEmpty { BandStream.SYNC_ORDER }
    }
}
