package com.opentasker.core.actions

import com.opentasker.core.engine.ActionResult
import java.net.InetAddress
import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the cleartext-LAN address classification that gates `allow_http` requests. Uses literal
 * IPs so no DNS lookup happens.
 */
class PrivateAddressPolicyTest {
    private fun isPrivate(literal: String) =
        isPrivateOrLocalAddress(InetAddress.getByName(literal))

    @Test
    fun ipv4LoopbackAndPrivateRangesAreLocal() {
        assertTrue(isPrivate("127.0.0.1"))
        assertTrue(isPrivate("10.0.0.5"))
        assertTrue(isPrivate("172.16.0.1"))
        assertTrue(isPrivate("172.31.255.255"))
        assertTrue(isPrivate("192.168.1.10"))
        assertTrue(isPrivate("169.254.1.1")) // link-local
    }

    @Test
    fun ipv6LoopbackLinkLocalAndUlaAreLocal() {
        assertTrue(isPrivate("::1"))
        assertTrue(isPrivate("fe80::1"))   // link-local
        assertTrue(isPrivate("fc00::1"))   // ULA
        assertTrue(isPrivate("fd12:3456:789a::1")) // ULA
    }

    @Test
    fun publicAddressesAreNotLocal() {
        assertFalse(isPrivate("8.8.8.8"))
        assertFalse(isPrivate("1.1.1.1"))
        assertFalse(isPrivate("172.32.0.1")) // just outside 172.16/12
        assertFalse(isPrivate("2001:4860:4860::8888"))
        assertFalse(isPrivate("2606:4700:4700::1111"))
    }

    @Test
    fun urlTargetsLocalNetworkFollowsClassification() {
        assertTrue(urlTargetsLocalNetwork(URL("http://192.168.1.5/status")))
        assertTrue(urlTargetsLocalNetwork(URL("https://[fd00::1]/api")))
        assertFalse(urlTargetsLocalNetwork(URL("http://8.8.8.8/")))
        assertFalse(urlTargetsLocalNetwork(URL("ftp://192.168.1.5/")))
    }

    @Test
    fun localNetworkPermissionIsNotEnforcedBelowApi37() {
        assertNull(localNetworkPermissionDenial(sdkInt = 36, granted = false))
        assertNull(localNetworkPermissionDenial(sdkInt = 34, granted = false))
    }

    @Test
    fun localNetworkPermissionGrantedProceedsOnApi37Plus() {
        assertNull(localNetworkPermissionDenial(sdkInt = 37, granted = true))
    }

    @Test
    fun localNetworkPermissionDeniedOrRevokedFailsClosedOnApi37Plus() {
        val denial = localNetworkPermissionDenial(sdkInt = 37, granted = false)
        assertTrue(denial is ActionResult.Failure)
        assertTrue((denial as ActionResult.Failure).message.contains("ACCESS_LOCAL_NETWORK"))
    }
}
