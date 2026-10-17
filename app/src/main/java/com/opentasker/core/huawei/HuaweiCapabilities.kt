package com.opentasker.core.huawei

import org.json.JSONObject

/**
 * What a Huawei device SAYS it supports, decoded from its own replies.
 *
 * Three questions, asked on every connect since the Band was first paired, and until 2026-10-17
 * answered into the void:
 *  * **SupportedServices** (`0x01/0x02`): tag 1 of the request lists service ids; tag 2 of the reply
 *    is one byte per id asked, in the same order, non-zero = supported.
 *  * **SupportedCommands** (`0x01/0x03`): tag 0x81 wraps pairs of (tag 2 = service id, tag 3 = the
 *    command ids asked); the reply's tag 0x81 wraps (tag 2 = service id, tag 4 = one flag byte per
 *    command asked).
 *  * **ExpandCapability** (`0x01/0x37`): tag 1 of the reply is a bitmap (37 bytes on the Band).
 *
 * The device answers only what it is asked, so [services] / [commands] cover the ids that were asked
 * and nothing else; an id never asked is UNKNOWN, which is why the lookups return `null` rather than
 * `false` for it. The Band answered the Health list in [HuaweiCommands.SUPPORTED_SERVICES]; the full
 * probe asks every id there is (`huawei.probe full=true`).
 */
data class HuaweiCapabilities(
    /** Service id → supported, for every id that was asked. */
    val services: Map<Int, Boolean> = emptyMap(),
    /** Service id → (command id → supported), for every pair that was asked. */
    val commands: Map<Int, Map<Int, Boolean>> = emptyMap(),
    /** The raw ExpandCapability bitmap, or null if the device did not answer. */
    val expand: ByteArray? = null,
    val recordedAt: Long = 0L,
) {
    /** True / false as the device answered, null when the id was never asked. */
    fun supportsService(service: Int): Boolean? = services[service]

    /** True / false as the device answered, null when the pair was never asked. */
    fun supportsCommand(service: Int, command: Int): Boolean? = commands[service]?.get(command)

    /**
     * Bit [bit] of the ExpandCapability bitmap, counted from the least significant bit of byte 0,
     * or null when there is no bitmap or it is too short. The bit order is how Huawei's other
     * companions read it; it has not yet been confirmed against a capture of our own.
     */
    fun expandBit(bit: Int): Boolean? {
        val map = expand ?: return null
        val index = bit / 8
        if (bit < 0 || index >= map.size) return null
        return (map[index].toInt() shr (bit % 8)) and 1 == 1
    }

    /** Every supported service, ascending. */
    fun supportedServices(): List<Int> = services.filterValues { it }.keys.sorted()

    /** Combine two partial snapshots (e.g. two command censuses asked in batches); [other] wins. */
    fun merge(other: HuaweiCapabilities): HuaweiCapabilities = HuaweiCapabilities(
        services = services + other.services,
        commands = (commands.keys + other.commands.keys).associateWith { svc ->
            (commands[svc] ?: emptyMap()) + (other.commands[svc] ?: emptyMap())
        },
        expand = other.expand ?: expand,
        recordedAt = maxOf(recordedAt, other.recordedAt),
    )

    fun toJson(): String = JSONObject().apply {
        put("services", JSONObject().apply { services.forEach { (k, v) -> put(k.toString(), v) } })
        put(
            "commands",
            JSONObject().apply {
                commands.forEach { (svc, cmds) ->
                    put(svc.toString(), JSONObject().apply { cmds.forEach { (c, v) -> put(c.toString(), v) } })
                }
            },
        )
        expand?.let { put("expand", HuaweiCrypto.upperHex(it)) }
        put("recordedAt", recordedAt)
    }.toString()

    override fun equals(other: Any?): Boolean =
        other is HuaweiCapabilities && services == other.services && commands == other.commands &&
            (expand?.contentEquals(other.expand) ?: (other.expand == null)) && recordedAt == other.recordedAt

    override fun hashCode(): Int =
        ((services.hashCode() * 31 + commands.hashCode()) * 31 + (expand?.contentHashCode() ?: 0)) * 31 +
            recordedAt.hashCode()

    companion object {
        fun fromJson(json: String): HuaweiCapabilities? = runCatching {
            val o = JSONObject(json)
            fun flags(obj: JSONObject?): Map<Int, Boolean> =
                obj?.keys()?.asSequence()?.associate { it.toInt() to obj.getBoolean(it) } ?: emptyMap()
            val cmds = o.optJSONObject("commands")
            HuaweiCapabilities(
                services = flags(o.optJSONObject("services")),
                commands = cmds?.keys()?.asSequence()?.associate { it.toInt() to flags(cmds.getJSONObject(it)) }
                    ?: emptyMap(),
                expand = o.optString("expand").ifEmpty { null }?.let { HuaweiCrypto.hex(it) },
                recordedAt = o.optLong("recordedAt"),
            )
        }.getOrNull()

        /** The SupportedServices reply, given the ids that were asked in that order. */
        fun parseServices(asked: ByteArray, reply: List<HuaweiProtocol.Tlv>): Map<Int, Boolean> {
            val flags = reply.firstOrNull { it.tag == 2 }?.value ?: return emptyMap()
            return asked.indices.filter { it < flags.size }
                .associate { (asked[it].toInt() and 0xFF) to (flags[it].toInt() != 0) }
        }

        /**
         * The SupportedCommands reply, given the inner request (the value of tag 0x81). Command ids
         * are paired with flags by position, per service, exactly as they were asked.
         */
        fun parseCommands(askedInner: ByteArray, reply: List<HuaweiProtocol.Tlv>): Map<Int, Map<Int, Boolean>> {
            val inner = reply.firstOrNull { it.tag == 0x81 }?.value ?: return emptyMap()
            val asked = pairs(HuaweiProtocol.parseTlvs(askedInner), 3)
            val answered = pairs(HuaweiProtocol.parseTlvs(inner), 4)
            return asked.mapNotNull { (svc, cmds) ->
                val flags = answered[svc] ?: return@mapNotNull null
                svc to cmds.indices.filter { it < flags.size }
                    .associate { (cmds[it].toInt() and 0xFF) to (flags[it].toInt() != 0) }
            }.toMap()
        }

        /** The inner request asking [commandsByService]; wrap it in tag 0x81 to send it. */
        fun commandsRequestInner(commandsByService: Map<Int, List<Int>>): ByteArray =
            commandsByService.entries.fold(ByteArray(0)) { acc, (svc, cmds) ->
                acc + HuaweiProtocol.tlv(2, byteArrayOf(svc.toByte())) +
                    HuaweiProtocol.tlv(3, ByteArray(cmds.size) { cmds[it].toByte() })
            }

        /** The ExpandCapability bitmap: tag 1 of the reply. */
        fun parseExpand(reply: List<HuaweiProtocol.Tlv>): ByteArray? =
            reply.firstOrNull { it.tag == 1 }?.value?.takeIf { it.isNotEmpty() }

        private fun pairs(tlvs: List<HuaweiProtocol.Tlv>, valueTag: Int): Map<Int, ByteArray> {
            val out = LinkedHashMap<Int, ByteArray>()
            var current: Int? = null
            for (t in tlvs) {
                when (t.tag) {
                    2 -> current = t.value.firstOrNull()?.toInt()?.and(0xFF)
                    valueTag -> current?.let { out[it] = t.value }
                }
            }
            return out
        }
    }
}
