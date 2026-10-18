package com.opentasker.core.huawei

import android.content.Context
import org.json.JSONObject
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
 *
 * ## Per device (2026-10-17, for the Watch D3)
 *
 * Everything that belongs to ONE device — its name, the bind, its locale, its face names, when its
 * file channel was last used, what it said it supports — is kept per Bluetooth address, so a second
 * device can be paired without destroying the first one's bind. Every such function takes a `device`
 * address, defaulting to the ACTIVE one ([address]). The Band 11 Pro's values stay where they always
 * were, in the original prefs file ([BAND_ADDRESS]); any other device gets its own file. The
 * window/sync preferences (language, overlap, lookback, timeout, the 地図 token) are not per device.
 */
object HuaweiSettings {
    private const val PREFS = "huawei_band_settings"
    private const val KEY_ADDRESS = "address"
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_AUTH_ID = "auth_id_self"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_VERSION = "auth_version"
    private const val KEY_DEVICE_SUPPORT_TYPE = "device_support_type"
    private const val KEY_OVERLAP_MIN = "overlap_minutes"
    private const val KEY_CHIZU_TOKEN = "chizu_token"
    private const val KEY_LAST_FILE_PULL = "last_file_pull_ms"
    private const val KEY_DICTIONARY_SINCE = "dictionary_since_ms"
    private const val KEY_BEAT_METRICS_VERSION = "beat_metrics_version"
    private const val KEY_LOOKBACK_HOURS = "lookback_hours"
    private const val KEY_TIMEOUT_SEC = "timeout_sec"
    private const val KEY_BOUND_AT = "bound_at"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_BAND_LOCALE = "band_locale"
    private const val KEY_FACE_NAMES = "face_names"

    private const val KEY_CAPABILITIES = "capabilities"

    /**
     * 白い熊's Band 11 Pro. A **public** address, unlike the Hume band's random one — so it survives
     * a factory reset, and the key derivation that depends on it stays stable. Its settings live in
     * the original prefs file; it is the active device until another one is chosen with [setAddress].
     */
    const val BAND_ADDRESS = "A4:AA:FE:34:29:0F"

    /** The active device when none has been chosen — the Band, the only device before 2026-10-17. */
    const val DEFAULT_ADDRESS = BAND_ADDRESS

    /** Re-request this much of what we already hold. Overlap is free: the dedupe key discards it. */
    const val DEFAULT_OVERLAP_MINUTES = 30

    const val DEFAULT_TIMEOUT_SEC = 180

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The prefs that hold [device]'s own settings: the original file for the Band, its own otherwise. */
    private fun devicePrefs(context: Context, device: String) =
        if (device.trim().equals(BAND_ADDRESS, ignoreCase = true)) {
            prefs(context)
        } else {
            context.applicationContext.getSharedPreferences(devicePrefsName(device), Context.MODE_PRIVATE)
        }

    /** `huawei_device_A1B2C3D4E5F6` — the address, upper-case, colons dropped. */
    internal fun devicePrefsName(device: String): String =
        "huawei_device_" + device.trim().uppercase().filter { it.isLetterOrDigit() }

    /** The ACTIVE device: the one every task without an explicit `address` talks to. */
    fun address(context: Context): String =
        prefs(context).getString(KEY_ADDRESS, null)?.trim()?.ifEmpty { null } ?: DEFAULT_ADDRESS

    /**
     * 白い熊's Huawei account ID, the one Health sends in `0x1A/0x05` and `0x1A/0x01`. Private: it
     * lives only here and in 「健康の設定」 (`%Huawei_AccountId`), which hands it over through the
     * `account` argument of the pair and sync actions — never in the code, the repo or a bundle.
     * Null until one has been handed over; the account steps are then skipped.
     */
    fun accountId(context: Context): String? =
        prefs(context).getString(KEY_ACCOUNT_ID, null)?.trim()?.ifEmpty { null }

    fun setAccountId(context: Context, value: String) =
        prefs(context).edit { putString(KEY_ACCOUNT_ID, value.trim()) }

    /** Make [value] the active device. The previous device's bind and settings are kept. */
    fun setAddress(context: Context, value: String) =
        prefs(context).edit { putString(KEY_ADDRESS, value.trim().uppercase()) }

    /**
     * The band's own name, e.g. "HUAWEI Band 11 Pro-90F". Needed verbatim by SetUpDeviceStatus
     * during provisioning — the band expects to be told its own name.
     */
    fun deviceName(context: Context, device: String = address(context)): String? =
        devicePrefs(context, device).getString(KEY_DEVICE_NAME, null)?.ifEmpty { null }

    fun setDeviceName(context: Context, value: String, device: String = address(context)) =
        devicePrefs(context, device).edit { putString(KEY_DEVICE_NAME, value) }

    /** 16 lowercase hex characters, minted once and reused so the band keeps recognising us. */
    fun authIdSelf(context: Context, device: String = address(context)): String? =
        devicePrefs(context, device).getString(KEY_AUTH_ID, null)?.ifEmpty { null }

    fun authToken(context: Context, device: String = address(context)): ByteArray? =
        devicePrefs(context, device).getString(KEY_AUTH_TOKEN, null)
            ?.ifEmpty { null }
            ?.let { runCatching { HuaweiCrypto.hex(it) }.getOrNull() }

    fun authVersion(context: Context, device: String = address(context)): Int = devicePrefs(context, device).getInt(KEY_AUTH_VERSION, 1)

    // ── names for the faces this library did not install ──────────────────────────────────────
    //
    // The band lists nine of 白い熊's faces as bare ten-digit ids because Huawei Health put them
    // there, and the picker that asks which face may go could only offer those numbers. Deleting
    // the wrong one loses a stock face for good — the library has no copy to reinstall from. So a
    // name can be attached to an id here, learned by cycling faces ON the band and reading back
    // which one it says is showing.
    //
    // Device-local on purpose, like the task icons: these ids belong to one band, and a name for a
    // face that is not in the library means nothing on another phone.

    /** Every id that has been named, to the name given. */
    fun faceNames(context: Context, device: String = address(context)): Map<String, String> =
        devicePrefs(context, device).getString(KEY_FACE_NAMES, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { obj ->
                obj.keys().asSequence().mapNotNull { k ->
                    obj.optString(k).takeIf { it.isNotBlank() }?.let { k to it }
                }.toMap()
            }
            ?: emptyMap()

    /** Name an id, or forget it when [name] is blank. */
    fun setFaceName(context: Context, assetId: String, name: String, device: String = address(context)) {
        val next = faceNames(context, device).toMutableMap()
        if (name.isBlank()) next.remove(assetId) else next[assetId] = name.trim()
        devicePrefs(context, device).edit { putString(KEY_FACE_NAMES, JSONObject(next as Map<*, *>).toString()) }
    }

    fun deviceSupportType(context: Context, device: String = address(context)): Int =
        devicePrefs(context, device).getInt(KEY_DEVICE_SUPPORT_TYPE, 4)

    fun boundAt(context: Context, device: String = address(context)): Long = devicePrefs(context, device).getLong(KEY_BOUND_AT, 0L)

    /** True once a bind has completed, so routine syncs can skip straight to the auth pass. */
    fun isBound(context: Context, device: String = address(context)): Boolean =
        authIdSelf(context, device) != null && authToken(context, device) != null

    fun saveBind(
        context: Context,
        authIdSelf: String,
        authToken: ByteArray,
        authVersion: Int,
        deviceSupportType: Int,
        boundAt: Long,
        device: String = address(context),
    ) = devicePrefs(context, device).edit {
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
    fun clearBind(context: Context, device: String = address(context)) = devicePrefs(context, device).edit {
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
    fun bandLocale(context: Context, device: String = address(context)): String? =
        devicePrefs(context, device).getString(KEY_BAND_LOCALE, null)?.ifEmpty { null }

    fun setBandLocale(context: Context, value: String, device: String = address(context)) =
        devicePrefs(context, device).edit { putString(KEY_BAND_LOCALE, value) }

    // ── what the device says it supports ──────────────────────────────────────────────────────
    //
    // The replies to SupportedServices (0x01/0x02), SupportedCommands (0x01/0x03) and
    // ExpandCapability (0x01/0x37), as the device gave them on its last connect, as hex. Until
    // 2026-10-17 they were asked for and thrown away; kept now so a sync step can be skipped on a
    // device that never offered it, instead of sending the Band's command list to a watch.

    /** The last capability snapshot of [device], or null if none was ever recorded. */
    fun capabilities(context: Context, device: String = address(context)): HuaweiCapabilities? =
        devicePrefs(context, device).getString(KEY_CAPABILITIES, null)?.let { HuaweiCapabilities.fromJson(it) }

    fun saveCapabilities(context: Context, caps: HuaweiCapabilities, device: String = address(context)) =
        devicePrefs(context, device).edit { putString(KEY_CAPABILITIES, caps.toJson()) }

    fun overlapMinutes(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAP_MIN, DEFAULT_OVERLAP_MINUTES)

    fun setOverlapMinutes(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_OVERLAP_MIN, value.coerceIn(0, 24 * 60)) }

    /**
     * How far back every routine sync looks. See [HuaweiSyncArgs.DEFAULT_LOOKBACK_HOURS] for why a
     * floor exists at all; the short version is that without one a hole never heals.
     */
    /** 白い熊 地図's automation token, for handing it a walk. Blank until 白い熊 pastes it. */
    /**
     * When the `0x2C` file channel was last used, or 0.
     *
     * The channel degrades under repeated use: measured 2026-08-24, the same file gave 25, 25, 50,
     * 54 and 54 blocks across a morning and then **8** on a pull a few minutes after two large ones.
     * Nothing here can make the band serve more, so the only lever is not asking too often — and the
     * only way to not ask too often is to remember when we last asked.
     */
    fun lastFilePull(context: Context, device: String = address(context)): Long = devicePrefs(context, device).getLong(KEY_LAST_FILE_PULL, 0L)

    /**
     * Which `HuaweiBeatMetrics` version produced the stored `beat_` samples.
     *
     * 0 for a database written before the idea existed, which is correct: everything in it predates
     * version 1 as far as this check is concerned, and a recompute over it is harmless.
     */
    fun beatMetricsVersion(context: Context): Int =
        prefs(context).getInt(KEY_BEAT_METRICS_VERSION, 0)

    fun setBeatMetricsVersion(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_BEAT_METRICS_VERSION, value) }

    fun markFilePull(context: Context, atMs: Long = System.currentTimeMillis(), device: String = address(context)) =
        devicePrefs(context, device).edit { putLong(KEY_LAST_FILE_PULL, atMs) }

    /**
     * Where the next data-dictionary fetch starts (ms), per device — the newest record already held,
     * or 0 when nothing has been fetched yet. Its own mark rather than the sample window, because a
     * routine sync's window is minutes long and a blood-pressure reading from this morning would
     * otherwise never be asked for.
     */
    fun dictionarySince(context: Context, device: String = address(context)): Long =
        devicePrefs(context, device).getLong(KEY_DICTIONARY_SINCE, 0L)

    fun markDictionary(context: Context, sinceMs: Long, device: String = address(context)) =
        devicePrefs(context, device).edit { putLong(KEY_DICTIONARY_SINCE, sinceMs) }

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
