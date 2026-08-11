package com.opentasker.core.actions

import com.opentasker.core.engine.ActionResult
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

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
    fun tlsParametersRequireHostnameVerificationAndSendSni() {
        val parameters = SSLParameters().apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName("broker.example.test"))
        }

        assertEquals("HTTPS", parameters.endpointIdentificationAlgorithm)
        assertEquals("broker.example.test", (parameters.serverNames.single() as SNIHostName).asciiName)
    }

    @Test
    fun tlsPolicyKeepsHostnameVerificationForPinnedAddressesWithoutIpSni() {
        val socket = javax.net.ssl.SSLContext.getDefault().socketFactory.createSocket() as javax.net.ssl.SSLSocket
        socket.use {
            MqttTlsPolicy.configure(it, "broker.example.test")
            assertEquals("HTTPS", it.sslParameters.endpointIdentificationAlgorithm)
            assertEquals("broker.example.test", (it.sslParameters.serverNames.single() as SNIHostName).asciiName)

            MqttTlsPolicy.configure(it, "192.0.2.10")
            assertEquals("HTTPS", it.sslParameters.endpointIdentificationAlgorithm)
            assertTrue(it.sslParameters.serverNames.isNullOrEmpty())
        }
    }

    @Test
    fun tlsPublishRejectsAReachableCertificateForAnotherHostname() {
        Security.addProvider(BouncyCastleProvider())
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val certificate = wrongHostnameCertificate(keyPair)
        val password = "changeit".toCharArray()
        val serverKeys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, password)
            setKeyEntry("server", keyPair.private, password, arrayOf(certificate))
        }
        val serverKeyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverKeys, password)
        }
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(serverKeyManagers.keyManagers, null, SecureRandom())
        }
        val server = (serverContext.serverSocketFactory.createServerSocket(0) as javax.net.ssl.SSLServerSocket).apply {
            soTimeout = 5_000
        }
        val serverThread = thread(isDaemon = true) {
            runCatching {
                (server.accept() as SSLSocket).use { it.startHandshake() }
            }
        }

        try {
            val trustedCertificate = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setCertificateEntry("server", certificate)
            }
            val clientTrustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trustedCertificate)
            }
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, clientTrustManagers.trustManagers, SecureRandom())
            }
            val config = MqttPublishConfig(
                host = "expected.example.test",
                port = server.localPort,
                tls = true,
                topic = "opentasker/event",
                payload = byteArrayOf(),
                qos = 0,
                retain = false,
                username = null,
                password = null,
                timeoutSeconds = 3,
            )

            val failure = runCatching {
                runBlocking {
                    SocketMqttPublishTransport(clientContext.socketFactory as javax.net.ssl.SSLSocketFactory)
                        .publish(config, InetAddress.getLoopbackAddress())
                }
            }.exceptionOrNull()

            assertNotNull("A certificate for the wrong hostname must fail the TLS handshake", failure)
            if (failure != null) {
                assertTrue(generateSequence(failure) { it.cause }.any { it is SSLHandshakeException })
            }
        } finally {
            server.close()
            serverThread.join(5_000)
        }
    }

    private fun wrongHostnameCertificate(keyPair: KeyPair): java.security.cert.X509Certificate {
        val subject = X500Name("CN=wrong.example.test")
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.time),
            Date(now.time - 60_000),
            Date(now.time + 300_000),
            subject,
            keyPair.public,
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, "wrong.example.test")),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(keyPair.private)
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer))
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
