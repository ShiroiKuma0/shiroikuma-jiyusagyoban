package com.opentasker.core.actions

import android.content.ContextWrapper
import com.opentasker.ProductionSources
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi/cellular restriction on HTTP Request, against a fake capabilities reader.
 *
 * The point of the setting is that a restricted request never leaves the device over the wrong
 * connection, so the tests that matter check the request was not sent at all, not just that the
 * action reported a failure.
 */
class HttpNetworkConstraintTest {

    private val wifi = ActiveTransport(connected = true, wifi = true, unmetered = true, internet = true)
    private val meteredWifi = ActiveTransport(connected = true, wifi = true, unmetered = false, internet = true)
    private val cellular = ActiveTransport(connected = true, cellular = true, unmetered = false, internet = true)

    /** A router with no WAN, a captive portal, or a plain device-to-device LAN. */
    private val lanOnlyWifi = ActiveTransport(connected = true, wifi = true, unmetered = true, internet = false)

    @Test
    fun `an unset or unrecognised constraint means no constraint`() {
        listOf(null, "", "   ", "ethernet", "WIFI ").forEach { raw ->
            // "WIFI " has a trailing space but parses, because parse trims and lowercases.
            val expected = if (raw?.trim()?.lowercase() == "wifi") {
                HttpNetworkConstraint.WIFI
            } else {
                HttpNetworkConstraint.ANY
            }
            assertEquals(raw.toString(), expected, HttpNetworkConstraint.parse(raw))
        }
    }

    @Test
    fun `any connection never denies, whatever the transport`() {
        listOf(wifi, meteredWifi, cellular, ActiveTransport.NONE, null).forEach { transport ->
            assertNull(
                transport.toString(),
                httpNetworkDenial(HttpNetworkConstraint.ANY, transport),
            )
        }
    }

    @Test
    fun `each constraint admits only its own transport`() {
        assertNull(httpNetworkDenial(HttpNetworkConstraint.WIFI, wifi))
        assertNull(httpNetworkDenial(HttpNetworkConstraint.WIFI, meteredWifi))
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.WIFI, cellular), "cellular")

        assertNull(httpNetworkDenial(HttpNetworkConstraint.CELLULAR, cellular))
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.CELLULAR, wifi), "Wi-Fi")

        assertNull(httpNetworkDenial(HttpNetworkConstraint.UNMETERED, wifi))
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.UNMETERED, meteredWifi), "metered Wi-Fi")
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.UNMETERED, cellular), "cellular")
    }

    /**
     * A Wi-Fi network with no route to the internet is still Wi-Fi.
     *
     * Reaching a printer or a home server on the local network is exactly what the action's
     * `allow_http` private-LAN rule exists for, so treating "no internet" as "offline" would make
     * the constraint refuse the requests it was added to protect.
     */
    @Test
    fun `a wifi-only request is allowed on a network with no internet access`() {
        assertNull(httpNetworkDenial(HttpNetworkConstraint.WIFI, lanOnlyWifi))
        assertNull(httpNetworkDenial(HttpNetworkConstraint.ANY, lanOnlyWifi))
        assertNull(httpNetworkDenial(HttpNetworkConstraint.UNMETERED, lanOnlyWifi))
        // Still the wrong transport, and the message says why rather than claiming it is offline.
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.CELLULAR, lanOnlyWifi), "no internet access")
    }

    /**
     * The policy test above cannot see the reader, and the reader is where this went wrong.
     *
     * `readActiveTransport` needs a real ConnectivityManager, so the mapping is pinned at the
     * source instead: `connected` must not be derived from NET_CAPABILITY_INTERNET, which is what
     * made a LAN-only network report as offline.
     */
    @Test
    fun `the reader does not decide connectivity from internet reachability`() {
        val body = ProductionSources.block(
            "com/opentasker/core/actions/NetworkActions.kt",
            "fun readActiveTransport(",
            "\n}",
        )

        assertTrue("the reader must report an active network as connected", "connected = true," in body)
        assertTrue(
            "internet reachability belongs on its own field, not on connected",
            "internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)" in body,
        )
        assertFalse(
            "connected must not be derived from NET_CAPABILITY_INTERNET",
            "connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)" in body,
        )
    }

    @Test
    fun `a restricted request fails closed when the transport cannot be read`() {
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.WIFI, null), "could not be identified")
    }

    @Test
    fun `a restricted request says so plainly when the device is offline`() {
        assertDenied(httpNetworkDenial(HttpNetworkConstraint.WIFI, ActiveTransport.NONE), "offline")
    }

    @Test
    fun `a wifi-only request on cellular sends nothing at all`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("should never be reached").build())

            val result = runAction(
                args = mapOf(
                    "url" to server.url("/restricted").toString(),
                    "allow_http" to "true",
                    "network" to "wifi",
                ),
                transport = cellular,
            )

            assertDenied(result, "limited to wifi")
            assertEquals("the request must not have been sent", 0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an unrestricted request is not touched by the check`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())

            val result = runAction(
                args = mapOf("url" to server.url("/open").toString(), "allow_http" to "true"),
                // Even an unreadable transport must not block an unrestricted request.
                transport = null,
            )

            assertEquals(ActionResult.Success, result)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    private fun assertDenied(result: ActionResult?, fragment: String) {
        val failure = result as? ActionResult.Failure
        assertTrue("expected a failure naming '$fragment', got $result", failure != null)
        assertTrue(failure!!.message, fragment in failure.message)
    }

    private fun runAction(args: Map<String, String>, transport: ActiveTransport?): ActionResult {
        val filesDir = Files.createTempDirectory("opentasker-network-constraint").toFile()
        return try {
            runBlocking {
                HttpRequestAction(transportReader = { transport }).run(context(filesDir), args)
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private fun context(filesDir: File): ActionContext = ActionContext(
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesDir
        },
        VariableStore(),
        logger = {},
    )
}
