package com.opentasker.core.huawei

import android.content.Context
import androidx.core.content.edit

/**
 * Connection settings and the band's bind credentials, in SharedPreferences.
 *
 * Separate from [com.opentasker.core.band.BandSettings] on purpose: both bands run in parallel while
 * their data is compared, and neither one's configuration should be able to disturb the other.
 *
 * **The credentials here are ours, not Huawei's.** [authIdSelf] is an identity we invent and
 * [authToken] is the result of a local HiChain bind. Nothing was issued by a server and nothing is
 * sent anywhere; losing them costs a re-pair, not an account.
 *
 * They are stored in the app's private preferences and must never appear in a bundle, an export, a
 * log line, or a commit.
 */
object HuaweiSettings {
    private const val PREFS = "huawei_band_settings"
    private const val KEY_ADDRESS = "address"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_AUTH_ID = "auth_id_self"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_VERSION = "auth_version"
    private const val KEY_DEVICE_SUPPORT_TYPE = "device_support_type"
    private const val KEY_OVERLAP_MIN = "overlap_minutes"
    private const val KEY_CHIZU_TOKEN = "chizu_token"
    private const val KEY_LOOKBACK_HOURS = "lookback_hours"
    private const val KEY_TIMEOUT_SEC = "timeout_sec"
    private const val KEY_BOUND_AT = "bound_at"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_BAND_LOCALE = "band_locale"

    /**
     * 白い熊's Band 11 Pro. A **public** address, unlike the Hume band's random one — so it survives
     * a factory reset, and the key derivation that depends on it stays stable.
     */
    const val DEFAULT_ADDRESS = "A4:AA:FE:34:29:0F"

    /** Re-request this much of what we already hold. Overlap is free: the dedupe key discards it. */
    const val DEFAULT_OVERLAP_MINUTES = 30

    const val DEFAULT_TIMEOUT_SEC = 180

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun address(context: Context): String =
        prefs(context).getString(KEY_ADDRESS, null)?.trim()?.ifEmpty { null } ?: DEFAULT_ADDRESS

    fun setAddress(context: Context, value: String) =
        prefs(context).edit { putString(KEY_ADDRESS, value.trim()) }

    /**
     * The band's own name, e.g. "HUAWEI Band 11 Pro-90F". Needed verbatim by SetUpDeviceStatus
     * during provisioning — the band expects to be told its own name.
     */
    fun deviceName(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_NAME, null)?.ifEmpty { null }

    fun setDeviceName(context: Context, value: String) =
        prefs(context).edit { putString(KEY_DEVICE_NAME, value) }

    /** 16 lowercase hex characters, minted once and reused so the band keeps recognising us. */
    fun authIdSelf(context: Context): String? =
        prefs(context).getString(KEY_AUTH_ID, null)?.ifEmpty { null }

    fun authToken(context: Context): ByteArray? =
        prefs(context).getString(KEY_AUTH_TOKEN, null)
            ?.ifEmpty { null }
            ?.let { runCatching { HuaweiCrypto.hex(it) }.getOrNull() }

    fun authVersion(context: Context): Int = prefs(context).getInt(KEY_AUTH_VERSION, 1)

    fun deviceSupportType(context: Context): Int =
        prefs(context).getInt(KEY_DEVICE_SUPPORT_TYPE, 4)

    fun boundAt(context: Context): Long = prefs(context).getLong(KEY_BOUND_AT, 0L)

    /** True once a bind has completed, so routine syncs can skip straight to the auth pass. */
    fun isBound(context: Context): Boolean =
        authIdSelf(context) != null && authToken(context) != null

    fun saveBind(
        context: Context,
        authIdSelf: String,
        authToken: ByteArray,
        authVersion: Int,
        deviceSupportType: Int,
        boundAt: Long,
    ) = prefs(context).edit {
        putString(KEY_AUTH_ID, authIdSelf)
        putString(KEY_AUTH_TOKEN, HuaweiCrypto.upperHex(authToken))
        putInt(KEY_AUTH_VERSION, authVersion)
        putInt(KEY_DEVICE_SUPPORT_TYPE, deviceSupportType)
        putLong(KEY_BOUND_AT, boundAt)
    }

    /**
     * Forget the bind. Needed when the band has been factory-reset or handed to another companion —
     * the stored token is then meaningless and reusing it just fails the auth pass.
     */
    fun clearBind(context: Context) = prefs(context).edit {
        remove(KEY_AUTH_ID)
        remove(KEY_AUTH_TOKEN)
        remove(KEY_BOUND_AT)
    }

    /**
     * The 健康（Huawei） window's display language, held SEPARATELY from the Hume band's.
     *
     * Two windows, two settings tasks, two of everything else — one shared key would mean flipping
     * the language in one window silently flipping the other. It is a stored preference rather than
     * an argument passed at launch because the system can resume the window long after the task that
     * opened it has finished, and it has to come back up in the language it was left in.
     */
    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, null)?.ifEmpty { null } ?: "en-US"

    fun setLanguage(context: Context, value: String) =
        prefs(context).edit { putString(KEY_LANGUAGE, value) }

    /**
     * The language shown ON THE BAND — a different thing from [language], which is the language of
     * OUR window, and the two are set independently on purpose. 白い熊 runs the report in English
     * and the band in Japanese.
     *
     * Null means "never chosen here", and that is load-bearing: the band's own first-run picker is
     * the only other way this gets set, so asserting a locale we were never given would overwrite a
     * choice made on the device.
     *
     * This is a RECORD of the last language sent, not an instruction. Pairing deliberately does not
     * re-assert it (see HuaweiSyncRunner's configure call): the language changes only when a task
     * says so. Any companion that touches the band can still push its own locale over it — that is
     * how this band ended up in English — and the answer is to flip it back by hand.
     */
    fun bandLocale(context: Context): String? =
        prefs(context).getString(KEY_BAND_LOCALE, null)?.ifEmpty { null }

    fun setBandLocale(context: Context, value: String) =
        prefs(context).edit { putString(KEY_BAND_LOCALE, value) }

    fun overlapMinutes(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAP_MIN, DEFAULT_OVERLAP_MINUTES)

    fun setOverlapMinutes(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_OVERLAP_MIN, value.coerceIn(0, 24 * 60)) }

    /**
     * How far back every routine sync looks. See [HuaweiSyncArgs.DEFAULT_LOOKBACK_HOURS] for why a
     * floor exists at all; the short version is that without one a hole never heals.
     */
    /** 白い熊 地図's automation token, for handing it a walk. Blank until 白い熊 pastes it. */
    fun chizuToken(context: Context): String? =
        prefs(context).getString(KEY_CHIZU_TOKEN, null)?.ifEmpty { null }

    fun setChizuToken(context: Context, value: String) =
        prefs(context).edit { putString(KEY_CHIZU_TOKEN, value) }

    fun lookbackHours(context: Context): Int =
        prefs(context).getInt(KEY_LOOKBACK_HOURS, HuaweiSyncArgs.DEFAULT_LOOKBACK_HOURS)

    fun setLookbackHours(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_LOOKBACK_HOURS, value.coerceIn(1, 14 * 24)) }

    fun timeoutSec(context: Context): Int =
        prefs(context).getInt(KEY_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC)

    fun setTimeoutSec(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_TIMEOUT_SEC, value.coerceIn(10, 1800)) }
}
