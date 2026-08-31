package com.opentasker.core.share.relay

import java.security.MessageDigest

/**
 * Deterministic relay identity derived from the target package. Hash-based so the segment is always a
 * valid Java package part (leading letter, no keywords, no illegal chars) regardless of the target's
 * name, and stable so regenerating a target's relay reinstalls over the same package.
 */
object RelayPackaging {
    const val PREFIX = "shiroikuma.jiyusagyoban.share.s_"

    /** `shiroikuma.jiyusagyoban.share.s_<first 8 hex of sha256(targetPkg)>`. */
    fun relayPackage(targetPackage: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(targetPackage.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(8)
        for (i in 0 until 4) hex.append("%02x".format(digest[i].toInt() and 0xFF))
        return PREFIX + hex
    }

    /** True if [pkg] is one of our generated relay packages. */
    fun isRelayPackage(pkg: String): Boolean = pkg.startsWith(PREFIX)
}
