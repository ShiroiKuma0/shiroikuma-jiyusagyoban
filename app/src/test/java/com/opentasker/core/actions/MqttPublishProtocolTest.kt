package com.opentasker.core.actions

import com.opentasker.core.engine.ActionResult
import com.opentasker.core.actions.MqttWireCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class MqttPublishProtocolTest {
    @Test
    fun parsesTlsDefaultsAndBoundedPublishFields() {
        val config = MqttPublishProtocol.parse(
            mapOf("host" to "mqtt.example.test", "topic" to "opentasker/event", "payload" to "hello"),
        ).getOrThrow()

        assertEquals(8883, config.port)
        assertTrue(config.tls)
        assertEquals(0, config.qos)
        assertFalse(config.retain)
        assertEquals("hello", config.payload.toString(Charsets.UTF_8))
    }

    @Test
    fun rejectsWildcardsInvalidQosAndPasswordWithoutUsername() {
        assertTrue(MqttPublishProtocol.parse(mapOf("host" to "broker", "topic" to "home/#")).isFailure)
        assertTrue(MqttPublishProtocol.parse(mapOf("host" to "broker", "topic" to "home", "qos" to "2")).isFailure)
        assertTrue(
            MqttPublishProtocol.parse(
                mapOf("host" to "broker", "topic" to "home", "password" to "secret"),
            ).isFailure,
        )
    }

    @Test
    fun cleartextDefaultsToThePrivatePortAndPrivateHostPolicyIsDeterministic() {
        val config = MqttPublishProtocol.parse(
            mapOf("host" to "broker", "topic" to "home", "tls" to "false"),
        ).getOrThrow()
        assertEquals(1883, config.port)
        assertTrue(
            MqttNetworkPolicy.isPrivateOrLocalHost("broker") {
                arrayOf(InetAddress.getByName("192.168.1.10"))
            },
        )
        assertFalse(
            MqttNetworkPolicy.isPrivateOrLocalHost("broker") {
                arrayOf(InetAddress.getByName("203.0.113.10"))
            },
        )
    }

    /**
     * A host resolving to both a private and a public record must not pass the cleartext gate: the
     * socket would otherwise re-resolve the name and could send the CONNECT - username and password
     * included - to the public address. Mirrors the HTTP action's private-only DNS policy.
     */
    @Test
    fun cleartextRequiresEveryResolvedAddressToBePrivate() {
        val mixed = { _: String ->
            arrayOf(InetAddress.getByName("192.168.1.10"), InetAddress.getByName("203.0.113.10"))
        }

        assertFalse(MqttNetworkPolicy.isPrivateOrLocalHost("broker", mixed))
        assertEquals(null, MqttNetworkPolicy.resolvePrivateOnly("broker", mixed))
        assertEquals(null, MqttNetworkPolicy.resolvePrivateOnly("broker") { emptyArray() })
        assertEquals(
            InetAddress.getByName("10.0.0.5"),
            MqttNetworkPolicy.resolvePrivateOnly("broker") { arrayOf(InetAddress.getByName("10.0.0.5")) },
        )
    }

    @Test
    fun wirePacketsCarryConnectPublishQosAndRetainFlags() {
        val connect = MqttWireCodec.connectPacket("ot-fixture", "user", "pass")
        val publish = MqttWireCodec.publishPacket(
            topic = "opentasker/event",
            payload = "hello".toByteArray(),
            qos = 1,
            retain = true,
            packetId = 7,
        )

        assertEquals(0x10.toByte(), connect[0])
        assertEquals(0x33.toByte(), publish[0])
        assertTrue(connect.toString(Charsets.ISO_8859_1).contains("MQTT"))
        assertTrue(publish.toList().contains(7.toByte()))
    }

    @Test
    fun outboundFieldsAreMarkedSensitiveInTheSharedRedactionRegistry() {
        registerActionMetadata()
        assertTrue(ActionArgumentSensitivity.isSensitive("mqtt.publish", "payload"))
        assertTrue(ActionArgumentSensitivity.isSensitive("mqtt.publish", "password"))
        assertEquals(ActionArgumentSensitivity.REDACTED, ActionArgumentSensitivity.maskValue("mqtt.publish", "payload", "secret"))
        assertTrue(HomeAssistantWebhookProtocol.isTransientFailure(ActionResult.Failure("HTTP 503")))
    }
}
