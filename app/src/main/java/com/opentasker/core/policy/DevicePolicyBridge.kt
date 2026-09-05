package com.opentasker.core.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.opentasker.core.logging.AppLogger

/**
 * The powers 白い熊 雫 (`shiroikuma.shizuku`) hands this app as a **device-policy delegate**.
 *
 * 雫 is the Device Owner on 白い熊's phone and calls `setDelegatedScopes` for us once, from its
 * "Device policy powers" switch. From then on every call here is the **public SDK** with a `null`
 * admin — no hidden API, no binder relay — and it keeps working while 雫 itself is stopped, because
 * `system_server` persists the delegation.
 *
 * **Why this exists at all.** A `pm disable-user` freeze and a shell-side `pm suspend` are both
 * reversible by any shell. A suspension written through the delegation is filed under the *owner's*
 * admin (`suspendingPackage=android`) and only the owner or a `DELEGATION_PACKAGE_ACCESS` holder can
 * lift it: `enforceCanSetPackagesSuspendedAsUser` lets a caller name only packages of its own uid,
 * root is exempt and shell is not. That is the strongest freeze this app can apply.
 *
 * **A lock outlives the delegation.** Revoking the scope stops future calls and releases nothing
 * already set; uninstalling this app releases nothing either. That is what a hard lock means, and it
 * is why [AppFreeze.thaw] clears every slot unconditionally rather than trusting any bookkeeping of
 * our own. 雫's `clear_all_locks` on its policy provider is the way back of last resort.
 *
 * Only the suspension scope is used here. The set 雫 grants also carries permission-fixing and
 * uninstall-blocking; this app deliberately exposes neither.
 */
object DevicePolicyBridge {

    /** Delegation did not exist before O; suspension as a delegate did not work before P. */
    private val SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Scopes move only when 白い熊 flips the switch in 雫, so a short cache keeps a per-action check
     * from costing a binder call every time. Deliberately short: a grant — or a revocation — must be
     * noticed within seconds, without needing a restart to explain itself.
     */
    private const val SCOPE_TTL_MS = 5_000L

    @Volatile private var scopes: List<String> = emptyList()
    @Volatile private var scopesAt = 0L

    /** Drop the cached scopes — call after anything that could have changed them. */
    fun invalidate() {
        scopesAt = 0L
    }

    private fun dpm(context: Context): DevicePolicyManager? = runCatching {
        context.applicationContext.getSystemService(DevicePolicyManager::class.java)
    }.getOrNull()

    /**
     * The scopes the platform says we hold.
     *
     * Asked of the platform, never assumed from our own state: a delegation revoked in 雫 must stop
     * us at once, and `getDelegatedScopes` is the only honest source. On the Mate XT `dumpsys
     * device_policy` does not print the delegation map at all, so this is also the only readable one.
     */
    fun scopes(context: Context): List<String> {
        if (!SUPPORTED) return emptyList()
        val now = SystemClock.elapsedRealtime()
        if (now - scopesAt < SCOPE_TTL_MS) return scopes
        val got = runCatching {
            dpm(context)?.getDelegatedScopes(null, context.applicationContext.packageName).orEmpty()
        }.getOrElse {
            // Not a delegate, or the platform refuses to answer. Either way we hold nothing, which
            // is the safe reading.
            emptyList()
        }
        scopes = got
        scopesAt = now
        return got
    }

    /** Whether the platform will let us suspend a package under the owner's admin. */
    fun canSuspend(context: Context): Boolean =
        SUPPORTED && DevicePolicyManager.DELEGATION_PACKAGE_ACCESS in scopes(context)

    /** Whether [pkg] currently carries a suspension we could see. */
    fun isSuspended(context: Context, pkg: String): Boolean = runCatching {
        dpm(context)?.isPackageSuspended(null, pkg) == true
    }.getOrDefault(false)

    /**
     * Suspend or release [pkg].
     *
     * `setPackagesSuspended` returns the packages it could **not** change — some are policy-exempt on
     * every device — so a non-empty return is a failure, not a success with a note. The result is
     * re-read afterwards rather than inferred: never record a state a write did not achieve.
     */
    fun setSuspended(context: Context, pkg: String, suspended: Boolean): Boolean {
        if (!canSuspend(context)) return false
        return runCatching {
            val failed = dpm(context)?.setPackagesSuspended(null, arrayOf(pkg), suspended)
            if (failed != null && failed.isNotEmpty()) {
                AppLogger.error(TAG, "Device policy refused to suspend=$suspended $pkg")
                return false
            }
            isSuspended(context, pkg) == suspended
        }.getOrElse { error ->
            // EMUI's logcat keeps only E/ and F/, so this has to be an error to be findable at all.
            AppLogger.error(TAG, "Suspending $pkg refused", error)
            false
        }
    }

    private const val TAG = "DevicePolicyBridge"
}
