package com.opentasker.core.transfer

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The external-automation gate: a master switch, and a token that is now OPTIONAL.
 *
 * ## What changed, and why (白い熊, 2026-09-04)
 *
 * The token used to be compulsory and the master switch shipped OFF, so a sister app was
 * unreachable until 白い熊 turned it on and pasted a 48-character secret into the caller. That is
 * friction where it is not wanted, and — more to the point — **a pasted secret cannot survive a
 * clean phone**, which is exactly the case the whole automation family now exists to serve:
 * 応用管理 restoring apps AND their data onto a wiped device, with nothing yet configured.
 *
 * So: [enabled] defaults to **true**, and [requireToken] is a new, separate switch defaulting to
 * **false**. The token still exists, still regenerates, still never leaves the phone — it is simply
 * opt-in now.
 *
 * ## Idempotent about the token
 *
 * A caller that sends a token to an app not asking for one is **served, not refused**. Tokens are
 * pasted into task arguments and workspace variables that outlive the setting they were pasted for;
 * refusing them would turn "白い熊 turned a switch off" into "half the batch mysteriously fails".
 *
 * ## Device-local by design
 *
 * The prefs file is NOT in `SettingsBackup.PREF_FILES`, so no automation setting — least of all the
 * token — travels in an export ZIP or reaches another phone.
 */
object AutomationAuth {

    private const val PREFS_FILE = "jiyusagyoban_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * Whether this app answers automation at all. **Default true** since 2026-09-04.
     *
     * Kept as a switch rather than removed: it is the only way to close one app off entirely, and a
     * feature that can be turned on but never off is a feature 白い熊 cannot retreat from.
     */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    /**
     * **`commit()`, never `apply()` — this gate fails OPEN.**
     *
     * v2 flipped this key's default from false to **true**, so a write that never reaches disk does
     * not fall back to "off": it falls back to **ON**. And 応用管理 force-stops an app the instant it
     * replies to an import, with `Process.killProcess` — a `SIGKILL`, which leaves an in-flight
     * `apply()` nowhere to land. Turning an app off is the one action 白い熊 has for shutting a
     * sister app out, and it is the action most likely to be running near a force-stop; losing it
     * silently reopens the door. Three tiny, infrequent writes: synchronous is the right trade for
     * every one of them. (`shiroikuma-jisho`.)
     */
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).commit()
    }

    /** Whether a caller must present [token]. **Default false** — the token is opt-in now. */
    fun requireToken(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setRequireToken(context: Context, value: Boolean) {
        // commit(): a lost write here means the door stops asking for the token 白い熊 just
        // switched on. See setEnabled.
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, value).commit()
    }

    /** The shared secret; generated on first read so the settings row always shows a value. */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
            ?: regenerateToken(context)

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        // commit(): the worst of the three to lose, because 白い熊 may already have pasted this
        // value into a caller — and nothing surfaces it. The caller simply starts failing
        // "bad token". See setEnabled.
        prefs(context).edit().putString(KEY_TOKEN, token).commit()
        return token
    }

    /**
     * True when the caller's token matches the stored secret (constant-time).
     *
     * Kept separate from [enabled] so a caller can report "disabled" and "bad token" as distinct
     * failures — they debug differently, and every app in the family reports them distinctly.
     */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /**
     * The whole gate, in the one place every entry point should ask.
     *
     * Returns null to proceed, or the exact `ERROR:` string to answer with. Written as one function
     * so no receiver, provider or service can implement the two checks in a subtly different order —
     * which is how "disabled" and "bad token" would drift apart across forty-two apps.
     *
     * **A token supplied to an app that does not require one is IGNORED, never an error.**
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }
}
