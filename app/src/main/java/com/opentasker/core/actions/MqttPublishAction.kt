package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MqttPublishConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val topic: String,
    val payload: ByteArray,
    val qos: Int,
    val retain: Boolean,
    val username: String?,
    val password: String?,
    val timeoutSeconds: Int,
)

object MqttPublishProtocol {
    const val DEFAULT_PLAINTEXT_PORT = 1883
    const val DEFAULT_TLS_PORT = 8883
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    const val MAX_TOPIC_BYTES = 256

    fun parse(args: Map<String, String>): Result<MqttPublishConfig> = runCatching {
        val host = args["host"]?.trim().orEmpty()
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' }) {
            "MQTT host must be a hostname or IP address"
        }
        val tls = args["tls"]?.let { parseBoolean(it, "tls") } ?: true
        val port = args["port"]?.let { raw ->
            raw.toIntOrNull()?.also { require(it in 1..65_535) { "MQTT port must be between 1 and 65535" } }
                ?: error("MQTT port must be an integer")
        } ?: if (tls) DEFAULT_TLS_PORT else DEFAULT_PLAINTEXT_PORT
        val topic = args["topic"]?.trim().orEmpty()
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        require(topic.isNotBlank() && topicBytes.size <= MAX_TOPIC_BYTES && '#' !in topic && '+' !in topic) {
            "MQTT publish topic must be non-blank, at most 256 bytes, and contain no wildcards"
        }
        val payload = args["payload"].orEmpty().toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "MQTT payload exceeds ${MAX_PAYLOAD_BYTES / 1024} KB"
        }
        val qos = args["qos"]?.let { raw ->
            raw.toIntOrNull()?.also { require(it in 0..1) { "MQTT QoS must be 0 or 1" } }
                ?: error("MQTT QoS must be an integer")
        } ?: 0
        val retain = args["retain"]?.let { parseBoolean(it, "retain") } ?: false
        val username = args["username"]?.trim()?.takeIf(String::isNotBlank)
        val password = args["password"]?.takeIf(String::isNotEmpty)
        require(password == null || username != null) { "MQTT password requires a username" }
        val timeoutSeconds = args["timeout_sec"]?.let { raw ->
            raw.toIntOrNull()?.also { require(it in 1..30) { "MQTT timeout must be between 1 and 30 seconds" } }
                ?: error("MQTT timeout must be an integer")
        } ?: 15

        MqttPublishConfig(host, port, tls, topic, payload, qos, retain, username, password, timeoutSeconds)
    }

    private fun parseBoolean(raw: String, name: String): Boolean = when (raw.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> error("MQTT $name must be a boolean")
    }
}

object MqttNetworkPolicy {
    fun isPrivateOrLocalHost(
        host: String,
        resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ): Boolean = runCatching {
        resolver(host).any(::isPrivateOrLocalAddress)
    }.getOrDefault(false)

    private fun isPrivateOrLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is java.net.Inet6Address) {
            val firstByte = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
            return firstByte and 0xfe == 0xfc
        }
        return false
    }
}

fun interface MqttPublishTransport {
    suspend fun publish(config: MqttPublishConfig)
}

class SocketMqttPublishTransport : MqttPublishTransport {
    override suspend fun publish(config: MqttPublishConfig) = withContext(Dispatchers.IO) {
        val socket = if (config.tls) {
            SSLSocketFactory.getDefault().createSocket()
        } else {
            Socket()
        }
        socket.use { connection ->
            connection.connect(InetSocketAddress(config.host, config.port), config.timeoutSeconds * 1_000)
            connection.soTimeout = config.timeoutSeconds * 1_000
            (connection as? SSLSocket)?.startHandshake()
            val clientId = "ot-${UUID.randomUUID().toString().replace("-", "").take(16)}"
            connection.outputStream.use { output ->
                output.write(
                    MqttWireCodec.connectPacket(
                        clientId = clientId,
                        username = config.username,
                        password = config.password,
                    ),
                )
                output.flush()
                val connAckCode = MqttWireCodec.readConnAck(connection.inputStream)
                require(connAckCode == 0) { "MQTT broker rejected CONNECT with code $connAckCode" }
                val packetId = 1
                output.write(
                    MqttWireCodec.publishPacket(
                        topic = config.topic,
                        payload = config.payload,
                        qos = config.qos,
                        retain = config.retain,
                        packetId = packetId,
                    ),
                )
                output.flush()
                if (config.qos == 1) MqttWireCodec.readPubAck(connection.inputStream, packetId)
                output.write(MqttWireCodec.disconnectPacket())
                output.flush()
            }
        }
    }
}

object MqttWireCodec {
    fun connectPacket(clientId: String, username: String?, password: String?): ByteArray {
        val flags = 0x02 or
            (if (username != null) 0x80 else 0x00) or
            (if (password != null) 0x40 else 0x00)
        val variableHeader = byteArrayOf(0, 4) + "MQTT".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, flags.toByte(), 0, 60)
        val payload = utf8(clientId) + (username?.let(::utf8) ?: byteArrayOf()) + (password?.let(::utf8) ?: byteArrayOf())
        return packet(0x10, variableHeader + payload)
    }

    fun publishPacket(topic: String, payload: ByteArray, qos: Int, retain: Boolean, packetId: Int): ByteArray {
        require(qos in 0..1) { "MQTT QoS must be 0 or 1" }
        val variableHeader = utf8(topic) + if (qos == 1) byteArrayOf((packetId ushr 8).toByte(), packetId.toByte()) else byteArrayOf()
        val flags = 0x30 or (qos shl 1) or if (retain) 0x01 else 0
        return packet(flags, variableHeader + payload)
    }

    fun disconnectPacket(): ByteArray = byteArrayOf(0xE0.toByte(), 0)

    fun readConnAck(input: InputStream): Int {
        val packet = readPacket(input)
        require(packet.first == 2 && packet.second.size >= 2) { "MQTT broker returned an invalid CONNACK" }
        return packet.second[1].toInt() and 0xff
    }

    fun readPubAck(input: InputStream, expectedPacketId: Int) {
        val packet = readPacket(input)
        require(packet.first == 4 && packet.second.size >= 2) { "MQTT broker returned an invalid PUBACK" }
        val packetId = ((packet.second[0].toInt() and 0xff) shl 8) or (packet.second[1].toInt() and 0xff)
        require(packetId == expectedPacketId) { "MQTT PUBACK packet ID did not match the publish" }
    }

    private fun packet(typeAndFlags: Int, payload: ByteArray): ByteArray =
        byteArrayOf(typeAndFlags.toByte()) + encodeRemainingLength(payload.size) + payload

    private fun utf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_535) { "MQTT UTF-8 field is too long" }
        return byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    private fun encodeRemainingLength(value: Int): ByteArray {
        require(value >= 0)
        var remaining = value
        val bytes = ArrayList<Byte>(4)
        do {
            var encoded = remaining % 128
            remaining /= 128
            if (remaining > 0) encoded = encoded or 128
            bytes += encoded.toByte()
        } while (remaining > 0)
        return bytes.toByteArray()
    }

    private fun readPacket(input: InputStream): Pair<Int, ByteArray> {
        val first = input.read().takeIf { it >= 0 } ?: throw EOFException("MQTT packet ended unexpectedly")
        var multiplier = 1
        var remaining = 0
        do {
            val encoded = input.read().takeIf { it >= 0 } ?: throw EOFException("MQTT remaining length ended unexpectedly")
            remaining += (encoded and 127) * multiplier
            multiplier *= 128
            require(multiplier <= 128 * 128 * 128 * 128) { "MQTT remaining length is invalid" }
        } while ((encoded and 128) != 0)
        val payload = ByteArray(remaining)
        var offset = 0
        while (offset < payload.size) {
            val count = input.read(payload, offset, payload.size - offset)
            if (count < 0) throw EOFException("MQTT packet payload ended unexpectedly")
            offset += count
        }
        return (first ushr 4) to payload
    }
}

class MqttPublishAction(
    private val transport: MqttPublishTransport = SocketMqttPublishTransport(),
) : DeclaredAction(ActionCatalog.require(ID)) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val config = MqttPublishProtocol.parse(args).getOrElse { error ->
            return ActionResult.Failure(error.message ?: "Invalid MQTT publish")
        }
        val localHost = MqttNetworkPolicy.isPrivateOrLocalHost(config.host)
        if (!config.tls && !localHost) {
            return ActionResult.Failure("MQTT without TLS is limited to private or local hosts")
        }
        if (localHost) {
            checkLocalNetworkPermission(ctx)?.let { return it }
        }
        return runCatching {
            transport.publish(config)
            ctx.logger("MQTT publish succeeded for ${config.host}:${config.port} qos=${config.qos} retain=${config.retain}")
            ActionResult.Success
        }.getOrElse { error ->
            ActionResult.Failure("MQTT publish failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    companion object {
        const val ID = "mqtt.publish"
    }
}
