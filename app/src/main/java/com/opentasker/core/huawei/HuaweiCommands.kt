package com.opentasker.core.huawei

import com.opentasker.core.huawei.HuaweiProtocol.tlv

/**
 * Service and command ids, and the request payloads that take a factory-reset Band 11 Pro to a
 * working watch face.
 *
 * Android-free and therefore JVM-testable, like [HuaweiProtocol]. The payload constants are what a
 * real Huawei Health pairing sent to *this* band on firmware 6.0.0.125, recovered by decrypting a
 * Bluetooth capture — see the `huawei-band-firstrun-recipe` memory. They are settings values, not
 * code, and nothing here derives from AGPL Gadgetbridge.
 *
 * **No Huawei account is used.** [ACCOUNT_ID] is an opaque identifier the band wants present;
 * presenting it involves no network call of any kind.
 */
object HuaweiCommands {

    // ---- services -------------------------------------------------------
    const val SVC_DEVICE_CONFIG = 0x01
    const val SVC_FITNESS = 0x07
    const val SVC_LOCALE = 0x0C
    const val SVC_ACCOUNT = 0x1A
    const val SVC_RRI = 0x19            // per-beat RR intervals — the reason this band exists
    const val SVC_FILE_UPLOAD = 0x28
    const val SVC_DATA_SYNC = 0x37

    // ---- DeviceConfig (0x01) commands -----------------------------------
    const val CMD_LINK_PARAMS = 0x01
    const val CMD_SUPPORTED_SERVICES = 0x02
    const val CMD_SUPPORTED_COMMANDS = 0x03
    const val CMD_DATE_FORMAT = 0x04
    const val CMD_SET_TIME = 0x05
    const val CMD_PRODUCT_INFO = 0x07
    const val CMD_BATTERY = 0x08
    const val CMD_PHONE_INFO = 0x10     // device-initiated: the band asks US
    const val CMD_DEVICE_STATUS = 0x16
    const val CMD_HICHAIN = 0x28
    const val CMD_PIN_CODE = 0x2C
    const val CMD_ACCEPT_AGREEMENTS = 0x30
    const val CMD_SETTING_RELATED = 0x31
    const val CMD_TIME_ZONE_ID = 0x32
    const val CMD_SECURITY_NEGOTIATION = 0x33
    const val CMD_CONNECT_STATUS = 0x35
    const val CMD_EXPAND_CAPABILITY = 0x37
    const val CMD_PERMISSION_CHECK = 0x38   // device-initiated
    const val CMD_WEAR_STATUS = 0x3D        // device-initiated NOTIFICATION — never answer it
    const val CMD_SETUP_DEVICE_STATUS = 0x3E
    const val CMD_REVERSE_CAPABILITIES = 0x3F

    // ---- AccountRelated (0x1A) ------------------------------------------
    const val ACC_SEND_ACCOUNT = 0x01
    const val ACC_UNKNOWN_03 = 0x03     // device-initiated; answer OK
    const val ACC_UNKNOWN_04 = 0x04
    const val ACC_EXTENDED_ACCOUNT = 0x05
    const val ACC_COUNTRY_CODE = 0x0A   // fire-and-forget: the band never replies
    const val ACC_UNKNOWN_06 = 0x06     // device-initiated; answer OK

    // ---- FitnessData (0x07) ---------------------------------------------
    const val FIT_USER_INFO = 0x02
    const val FIT_STEP_COUNT = 0x0A
    const val FIT_STEP_RECORD = 0x0B
    const val FIT_SLEEP_COUNT = 0x0C
    const val FIT_SLEEP_RECORD = 0x0D

    /**
     * Commands the band never answers. Waiting on one burns the few seconds it gives us before
     * abandoning its own pairing flow — a timeout on these is CORRECT, not a fault.
     */
    val FIRE_AND_FORGET: Set<Pair<Int, Int>> = setOf(
        SVC_ACCOUNT to ACC_COUNTRY_CODE,
        SVC_DEVICE_CONFIG to CMD_SETUP_DEVICE_STATUS,
    )

    /** Opaque identifier the band expects. Not a credential; nothing contacts Huawei. */
    const val ACCOUNT_ID = "30420000025794403"

    /** Sent as tag 6 of SecurityNegotiation. Health sends this literal string. */
    const val PHONE_IDENTIFY = "phoneIdentify"

    /** `authMode` for a band reporting `deviceSupportType` 1, 3 or 4 (HiChain3). */
    fun authModeFor(deviceSupportType: Int): Int =
        if (deviceSupportType in intArrayOf(1, 3, 4)) 4 else 0

    // ---- captured payloads ----------------------------------------------

    /** ProductInfo asks for exactly these tags, not a blind range. */
    val PRODUCT_INFO_TAGS = intArrayOf(1, 2, 7, 9, 10, 17, 18, 22, 26, 29, 30, 31, 32, 33, 34, 35)

    val SUPPORTED_SERVICES: ByteArray = HuaweiCrypto.hex(
        "02030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20" +
            "2223242526272A2B2D2E3032333435",
    )

    val SUPPORTED_COMMANDS: ByteArray = HuaweiCrypto.hex(
        "020101031E040708090A0D0E10111213141B1A1D21222324292A2B322E31303536372F0201020306" +
            "010405060708020104030101020107031B0103050708090A0E1013161517181B1C1D1E2122232425" +
            "2829061F02010803030102030201090306010B0C0D0E0F02010A030301090A02010B030201030201" +
            "0C03010102010F03090103050607080A0B0C0201160303010307020117030A010406070B0C101215" +
            "17020118030601020405060902011903020104" +
            "02011A03050103070506" +
            "02011B0304010F191A020120030601020304090A020125030302040E0201270302010E02012B0301" +
            "1202012E030301020302013003010102013203010102013403010102013503020304",
    )

    /** Six empty tags. */
    val SETTING_RELATED: ByteArray = HuaweiCrypto.hex("010002000300040005000600")

    /**
     * Agreements acceptance. TWO blocks — `user_license_agreement` and
     * `device_information_management`. A hand-built three-block version does NOT work.
     */
    val ACCEPT_AGREEMENTS: ByteArray = HuaweiCrypto.hex(
        "81811482480316757365725F6C6963656E73655F61677265656D656E74040101051C323032353037" +
            "31362D32303235303731362D32303235303731362D30060D3137383733343636333734303482" +
            "48031D6465766963655F696E666F726D6174696F6E5F6D616E6167656D656E74040101051532" +
            "303233303530382D32303233303530382D302D30060D31373837333436363337343034",
    )

    /** SEVEN bytes. Gadgetbridge's six-byte `FD F7 33 FA 29 37` is not what this band takes. */
    val REVERSE_CAPABILITIES: ByteArray = HuaweiCrypto.hex("0107FDF773FA29BF3B")

    // ---- request builders ------------------------------------------------

    fun linkParams(): ByteArray = tlv(1) + tlv(2) + tlv(3) + tlv(4)

    fun deviceStatus(): ByteArray = tlv(1)

    /**
     * SecurityNegotiation. [deviceId] must be **16 lowercase hex characters** — Health uses that
     * shape, and it is carried into the HiChain token maths.
     */
    fun securityNegotiation(authMode: Int, deviceId: String, phoneModel: String): ByteArray =
        tlv(1, byteArrayOf(authMode.toByte())) +
            tlv(2, byteArrayOf(1)) +
            tlv(5, deviceId) +
            tlv(3, byteArrayOf(1)) +
            tlv(4, byteArrayOf(0)) +
            tlv(6, PHONE_IDENTIFY) +
            tlv(7, phoneModel)

    fun pinCode(): ByteArray = tlv(1)

    fun productInfo(): ByteArray =
        PRODUCT_INFO_TAGS.fold(ByteArray(0)) { acc, t -> acc + tlv(t) }

    /**
     * SetTime. [zoneHours] is offset-encoded: negative offsets are sent as `128 + |hours|`.
     */
    fun setTime(epochSeconds: Long, zoneHours: Int, zoneMinutes: Int): ByteArray =
        tlv(1, HuaweiProtocol.intBytes(epochSeconds.toInt(), 4)) +
            tlv(2, byteArrayOf(zoneHours.toByte(), zoneMinutes.toByte()))

    fun supportedServices(): ByteArray = tlv(1, SUPPORTED_SERVICES)

    fun supportedCommands(): ByteArray = tlv(0x81, SUPPORTED_COMMANDS)

    fun expandCapability(): ByteArray = tlv(1)

    fun settingRelated(): ByteArray = SETTING_RELATED

    fun acceptAgreements(): ByteArray = ACCEPT_AGREEMENTS

    fun reverseCapabilities(): ByteArray = REVERSE_CAPABILITIES

    /** Carries the BAND's own name, e.g. "HUAWEI Band 11 Pro-90F". Fire-and-forget. */
    fun setUpDeviceStatus(deviceName: String): ByteArray =
        tlv(1, byteArrayOf(1)) + tlv(2, deviceName) + tlv(3, byteArrayOf(0))

    fun accountStep04(): ByteArray = tlv(1, byteArrayOf(1))

    fun extendedAccount(accountId: String = ACCOUNT_ID): ByteArray =
        tlv(1, accountId) + tlv(3, byteArrayOf(1))

    /** Fire-and-forget. */
    fun countryCode(country: String, siteId: Int): ByteArray =
        tlv(1, country) + tlv(2, byteArrayOf(siteId.toByte()))

    fun sendAccount(accountId: String = ACCOUNT_ID): ByteArray = tlv(1, accountId)

    fun battery(): ByteArray = tlv(1)

    /** Count-then-index history: how many records exist in `[start, end]`. */
    fun fitnessCount(start: Long, end: Long): ByteArray =
        tlv(0x81) +
            tlv(3, HuaweiProtocol.intBytes(start.toInt(), 4)) +
            tlv(4, HuaweiProtocol.intBytes(end.toInt(), 4))

    fun fitnessRecord(index: Int): ByteArray =
        tlv(0x81) + tlv(2, HuaweiProtocol.intBytes(index, 2))

    /**
     * Reply to the band's PhoneInfo request: echo each requested tag, filled.
     *
     * `0x00`/`0x0F` are omitted entirely; `0x02`/`0x04`/`0x15` go back empty; `0x08` is the Android
     * version; `0x11` is an app version; everything else takes a single zero byte.
     *
     * A request whose only tag is the result tag is the band ACKNOWLEDGING our reply — answering it
     * ping-pongs without end, so callers must filter that out first.
     */
    fun phoneInfoReply(requestedTags: List<Int>): ByteArray {
        var out = ByteArray(0)
        for (tag in requestedTags) {
            out += when (tag) {
                0x00, 0x0F -> ByteArray(0)
                0x02, 0x04, 0x15 -> tlv(tag)
                0x08 -> tlv(tag, "14")
                0x11 -> tlv(tag, HuaweiProtocol.intBytes(1_600_103_320, 4))
                else -> tlv(tag, byteArrayOf(0))
            }
        }
        return out
    }

    /** A plain success acknowledgement. */
    fun ok(): ByteArray =
        tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4))
}
