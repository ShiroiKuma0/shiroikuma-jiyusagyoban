package com.sysadmindoc.ruledeck.core.transfer

import android.content.Context
import android.content.Intent
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The gate of the adb bridge ([WorkspaceTransferReceiver]): a master switch, and commands signed by
 * the PC (白い熊, 2026-11-24).
 *
 * ## Why
 *
 * The bridge is exported and used to check nothing but a protocol number, so any app on the phone
 * could import a task and run it — and since the engine can run commands as root and in Termux,
 * that was a step from "installed app" to "root on a Slave" or "the ssh key in Termux on the
 * Master". Nobody but the PC ever calls the bridge, so it can demand proof that the caller is the PC.
 *
 * ## How
 *
 * - [enabled], default **true**: off, the bridge answers nothing at all.
 * - The PC holds an ECDSA P-256 private key; the phone holds only the matching PUBLIC key. A public
 *   key forges nothing, so there is no secret on the phone for a task, a backup or another app to
 *   read. (An app with root is not stopped by this or by anything else on the phone: it can replace
 *   the key or write the database directly. The gate against root apps is the root manager's list.)
 * - A key is never installed from outside. It is OFFERED over the bridge, lands as the *waiting* key,
 *   and counts only once 白い熊 approves its fingerprint in the settings — which also switches
 *   [requireSignature] on.
 * - A signed command covers: this phone's [bridgeId] (a command for one phone is void on another),
 *   the action, and every extra — among them a counter that must exceed the last one accepted
 *   (a watched command cannot be sent again) and, for a command that reads a file, the file's
 *   SHA-256 (the file cannot be swapped under a valid signature; `/sdcard/tmp` is writable by others).
 *
 * P-256 and not Ed25519: Android has Ed25519 only from API 33, and EMUI under-reports its API level.
 *
 * ## Device-local
 *
 * The prefs file is not in `SettingsBackup.PREF_FILES`: neither the switch nor the key travels in an
 * export, so restoring a backup cannot open the bridge or plant a key. Every write is `commit()`,
 * for the reason given in [AutomationAuth.setEnabled] — these gates fail open or closed on a lost write.
 */
object BridgeAuth {

    private const val PREFS_FILE = "jiyusagyoban_bridge"
    private const val KEY_ENABLED = "bridge_enabled"
    private const val KEY_REQUIRE_SIGNATURE = "bridge_require_signature"
    private const val KEY_PUBLIC_KEY = "bridge_public_key"
    private const val KEY_WAITING_KEY = "bridge_waiting_key"
    private const val KEY_BRIDGE_ID = "bridge_id"
    private const val KEY_LAST_COUNTER = "bridge_last_counter"

    const val EXTRA_PREFIX = "shiroikuma.jiyusagyoban.extra."
    /** Base64 of the DER ECDSA signature over [canonical]. Never part of the signed text. */
    const val EXTRA_SIGNATURE = EXTRA_PREFIX + "SIG"
    /** Decimal counter (the PC sends its clock in milliseconds); must exceed the last accepted one. */
    const val EXTRA_COUNTER = EXTRA_PREFIX + "COUNTER"
    /** Lower-case hex SHA-256 of the file a command reads; compulsory for such a command when signed. */
    const val EXTRA_SHA256 = EXTRA_PREFIX + "SHA256"
    /** Base64 of the X.509 (SubjectPublicKeyInfo) DER public key being offered. */
    const val EXTRA_KEY = EXTRA_PREFIX + "KEY"

    const val CANONICAL_HEADER = "jsb-bridge-1"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).commit()
    }

    /** True only while an approved key exists: without one there is nothing to check against. */
    fun requireSignature(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_SIGNATURE, false) && publicKey(context) != null

    fun setRequireSignature(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_SIGNATURE, value && publicKey(context) != null).commit()
    }

    /** The approved key (Base64 DER), or null. */
    fun publicKey(context: Context): String? =
        prefs(context).getString(KEY_PUBLIC_KEY, null)?.takeIf { it.isNotEmpty() }

    /** The key offered over the bridge and not yet approved (Base64 DER), or null. */
    fun waitingKey(context: Context): String? =
        prefs(context).getString(KEY_WAITING_KEY, null)?.takeIf { it.isNotEmpty() }

    /** This install's name inside a signed command; random, made on first read. */
    fun bridgeId(context: Context): String =
        prefs(context).getString(KEY_BRIDGE_ID, null)?.takeIf { it.isNotEmpty() } ?: run {
            val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs(context).edit().putString(KEY_BRIDGE_ID, id).commit()
            id
        }

    /**
     * Files [keyBase64] as the waiting key and returns its fingerprint. It replaces an earlier
     * waiting key and never touches the approved one — approval happens on the phone only.
     */
    fun offerKey(context: Context, keyBase64: String): String {
        val clean = keyBase64.trim()
        val fingerprint = fingerprint(clean)
        parseKey(clean)
        prefs(context).edit().putString(KEY_WAITING_KEY, clean).commit()
        return fingerprint
    }

    /** The waiting key becomes THE key, and from now on every command must be signed with it. */
    fun approveWaitingKey(context: Context): Boolean {
        val waiting = waitingKey(context) ?: return false
        prefs(context).edit()
            .putString(KEY_PUBLIC_KEY, waiting)
            .remove(KEY_WAITING_KEY)
            .putBoolean(KEY_REQUIRE_SIGNATURE, true)
            .commit()
        return true
    }

    fun discardWaitingKey(context: Context) {
        prefs(context).edit().remove(KEY_WAITING_KEY).commit()
    }

    /** Forgets the approved key; signatures stop being asked for, because none could be checked. */
    fun removeKey(context: Context) {
        prefs(context).edit().remove(KEY_PUBLIC_KEY).putBoolean(KEY_REQUIRE_SIGNATURE, false).commit()
    }

    /** SHA-256 of the DER key, first 16 hex digits in groups of four — what 白い熊 compares by eye. */
    fun fingerprint(keyBase64: String): String {
        val der = try {
            Base64.getDecoder().decode(keyBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("the key is not Base64")
        }
        return MessageDigest.getInstance("SHA-256").digest(der)
            .take(8).joinToString("") { "%02x".format(it) }
            .chunked(4).joinToString(" ")
    }

    private fun parseKey(keyBase64: String) = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(keyBase64.trim())))
    } catch (e: Exception) {
        throw IllegalArgumentException("not an EC public key (X.509 DER, Base64): ${e.message}")
    }

    /**
     * The text that is signed. One line each: the header, the phone's id, the action, then every
     * extra as `name=value` in name order. `%`, CR and LF inside a value are percent-escaped, so no
     * value can pose as a second line. The PC builds the same text (`scripts/bridge.py`).
     */
    fun canonical(bridgeId: String, action: String, extras: Map<String, String>): String = buildString {
        append(CANONICAL_HEADER).append('\n')
        append(bridgeId).append('\n')
        append(action).append('\n')
        extras.toSortedMap().forEach { (name, value) ->
            append(name).append('=').append(escape(value)).append('\n')
        }
    }

    private fun escape(value: String): String =
        value.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")

    fun verify(keyBase64: String, message: String, signatureBase64: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(parseKey(keyBase64))
            update(message.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signatureBase64.trim()))
        }
    }.getOrDefault(false)

    /** Every extra of ours except the signature itself, as text — exactly what the PC signed. */
    fun signedExtras(intent: Intent): Map<String, String> {
        val bundle = intent.extras ?: return emptyMap()
        return bundle.keySet()
            .filter { it.startsWith(EXTRA_PREFIX) && it != EXTRA_SIGNATURE }
            .associateWith { @Suppress("DEPRECATION") bundle.get(it)?.toString().orEmpty() }
    }

    /**
     * The whole gate. Null to proceed, else the `ERROR:` text to answer with.
     *
     * A signature sent to a phone that does not ask for one is ignored, not refused — the PC signs
     * every command, also for a phone whose key is not approved yet.
     */
    @Synchronized
    fun refuse(context: Context, intent: Intent): String? {
        if (!enabled(context)) return "ERROR:control over adb is switched off on this phone (Settings → Export / Import)"
        if (!requireSignature(context)) return null
        val key = publicKey(context) ?: return "ERROR:no approved key"
        val signature = intent.getStringExtra(EXTRA_SIGNATURE)
        if (signature.isNullOrBlank()) return "ERROR:this phone accepts signed commands only — use scripts/bridge.py"
        val extras = signedExtras(intent)
        val counter = extras[EXTRA_COUNTER]?.toLongOrNull()
            ?: return "ERROR:signed command without a counter"
        val last = prefs(context).getLong(KEY_LAST_COUNTER, 0L)
        if (counter <= last) return "ERROR:stale counter (a repeated or out-of-order command) — send it again"
        val message = canonical(bridgeId(context), intent.action.orEmpty(), extras)
        if (!verify(key, message, signature)) return "ERROR:bad signature"
        prefs(context).edit().putLong(KEY_LAST_COUNTER, counter).commit()
        return null
    }

    /** What the PC needs to know before it signs; holds nothing secret, so it is answered unsigned. */
    fun info(context: Context): String = buildString {
        append("id=").append(bridgeId(context))
        append(" enabled=").append(enabled(context))
        append(" signed=").append(requireSignature(context))
        append(" key=").append(publicKey(context)?.let { fingerprint(it).replace(" ", "") } ?: "none")
        append(" waiting=").append(waitingKey(context)?.let { fingerprint(it).replace(" ", "") } ?: "none")
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
