package com.opentasker.core.automation

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2 automation gate, and the three ways it could quietly become weaker than what it replaced.
 *
 * Source-scanned rather than exercised: the gate's inputs are `getCallingPackage()` and a signing
 * certificate, neither of which exists in a JVM test. What CAN be checked here is that the
 * decisions survive — and each of these has a specific, already-made mistake behind it.
 */
class AutomationContractTest {

    private val auth = ProductionSources.read("com/opentasker/core/transfer/AutomationAuth.kt")
    private val callers = ProductionSources.read("com/opentasker/core/automation/AutomationCallers.kt")
    private val provider = ProductionSources.read("com/opentasker/core/automation/AutomationProvider.kt")

    /** 白い熊's whole point: a clean phone has no token, so automation cannot need one. */
    @Test
    fun `automation is on by default and the token is not`() {
        assertTrue(
            "the master switch must default true",
            auth.contains("getBoolean(KEY_ENABLED, true)"),
        )
        assertTrue(
            "the token requirement must default false",
            auth.contains("getBoolean(KEY_REQUIRE_TOKEN, false)"),
        )
    }

    /**
     * A token sent to an app that does not want one is SERVED, not refused.
     *
     * Tokens live in task arguments and workspace variables that outlive the setting they were
     * pasted for. Refusing them would turn "白い熊 turned a switch off" into "half the batch
     * mysteriously fails", which is the opposite of what the switch is for.
     */
    @Test
    fun `an unwanted token is ignored rather than refused`() {
        assertTrue(
            "the token is only checked when it is required",
            auth.contains("requireToken(context) && !isTokenValid(context, candidate)"),
        )
    }

    /**
     * The exact-name check, and why a prefix is not acceptable here.
     *
     * `getCallingPackage()` is worth something only because a name cannot be taken while the real
     * package is installed. A `shiroikuma.*` prefix has no such protection — any sideloaded app can
     * call itself `shiroikuma.evil` — and since the caller supplies the descriptor an export is
     * written into, a prefix check would hand it every sister app's data in turn. That is strictly
     * weaker than the token being removed (応用管理 caught this in review, 2026-09-04).
     */
    @Test
    fun `callers are matched by exact name, never by prefix`() {
        assertTrue("the callers are named", callers.contains("\"shiroikuma.oyokanri\""))
        assertTrue("both of them", callers.contains("\"shiroikuma.jiyusagyoban\""))
        val code = callers
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^\\s*//.*$"), "")
        assertTrue(
            "a prefix test must never appear in the code",
            !code.contains("startsWith(\"shiroikuma"),
        )
    }

    /**
     * The certificate pin, which is what closes the real gap.
     *
     * The gap is not restore-specific: whichever caller package is ABSENT from the device is a name
     * anyone can take — and the clean-phone case this contract exists for is precisely a device
     * where not everything is installed yet, so the moment the assumption is weakest is the moment
     * it is most needed (白い熊 chose to pin, 2026-09-04).
     */
    @Test
    fun `the caller's signing certificate is pinned`() {
        assertTrue("signatures are read", callers.contains("GET_SIGNING_CERTIFICATES"))
        assertTrue("and compared", callers.contains("MessageDigest.isEqual"))
        val pins = Regex("\"[0-9a-f]{64}\"").findAll(callers).count()
        assertEquals("one pin per named caller", 2, pins)
    }

    /**
     * The uid cross-check.
     *
     * `getCallingPackage()` reflects the caller's DECLARED attribution, and packages sharing a uid
     * are not distinguished by it. The uid is the kernel's answer and cannot be borrowed.
     */
    @Test
    fun `the declared package is confirmed against the calling uid`() {
        assertTrue(callers.contains("getPackagesForUid(Binder.getCallingUid())"))
        assertTrue(callers.contains("name !in real"))
    }

    /**
     * Import has no broadcast form, and must never grow one.
     *
     * An import overwrites an app's data. The export receiver is `exported="true"` with no
     * permission — deliberately, since the token was the gate — so an ungated import action there
     * would let any app on the phone wipe any sister app (応用管理, 2026-09-04).
     */
    @Test
    fun `import exists only behind the verified door`() {
        val receiver = ProductionSources.read("com/opentasker/core/transfer/StateExportReceiver.kt")
        assertTrue("no import action on the receiver", !receiver.contains("IMPORT_STATE"))
        assertTrue("import is a provider method", provider.contains("METHOD_IMPORT"))
    }

    /** Identity is checked before anything else, including before the app's own switches. */
    @Test
    fun `the caller is verified before the request is even looked at`() {
        val verify = provider.indexOf("AutomationCallers.verify")
        val method = provider.indexOf("return when (method)")
        assertTrue("verification must precede dispatch", verify in 1 until method)
    }
}
