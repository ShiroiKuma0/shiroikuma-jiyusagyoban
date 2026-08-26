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
    const val SVC_LOCALE = 0x0C  // LocaleConfig — the band has NO language menu of its own
    const val SVC_ACCOUNT = 0x1A
    const val SVC_RRI = 0x19            // per-beat RR intervals — the reason this band exists
    const val SVC_FILE_UPLOAD = 0x28
    const val SVC_WORKOUT = 0x17        // recorded exercises, and the pointer to their GPS tracks
    const val SVC_FILE_TRANSFER = 0x2C  // band -> phone: sleep and RR intervals, by name
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

    // ---- LocaleConfig (0x0C) commands -----------------------------------
    const val CMD_SET_LOCALE = 0x01

    // ---- Weather (0x0F) and location (0x18) — a PUSH, never a request -------------------
    //
    // The band shows whatever the phone last told it. Nothing is fetched on the band's behalf, and
    // the band's separate pleas for an HTTP proxy (`0x37/0x02`, topic 2FB08EAB) are NOT part of
    // this and are deliberately unanswered — that would make us its general-purpose web client.
    const val SVC_WEATHER = 0x0F
    const val WEATHER_PUSH = 0x01
    const val WEATHER_UNIT = 0x05       // 01 Fahrenheit, 00 Celsius
    const val WEATHER_DISABLE = 0x0C    // the "Weather reports" switch, OFF direction only
    const val WEATHER_FORECAST = 0x08   // 24 hourly + 15 daily entries; Health sends it after EVERY push
    const val WEATHER_PUSH_DONE = 0x0B  // the little empty frame Health closes a push with

    /**
     * The three reads Health performs immediately BEFORE every weather push.
     *
     * `0x02` answers `{1: FF}`, `0x06` answers `{1: 00 0F}`, `0x0A` answers `{1: 00 00 00 03}` —
     * capability masks of some kind. We have never sent any of them, and on 2026-08-25 the forecast
     * we do send turned out never to have been applied even once, while the current-weather push in
     * the same session always was. These reads are the remaining difference in the sequence, and
     * they are the same shape of bug as the six announce commands that unblocked the session.
     *
     * The request form is tag 1 with a zero-length VALUE, which is not the same wire object as an
     * empty payload — an earlier sweep sent the latter and concluded the band "refuses" them.
     */
    val WEATHER_READS = listOf(0x02, 0x06, 0x0A)
    // ---- GNSS assistance (0x1F asks, 0x1C carries) --------------------------------------
    //
    // The band ASKS for this and we answer, which is the reverse of everything else in this file.
    // Decoded 2026-08-25 from a capture of Huawei Health serving it: 806 515 bytes across seven
    // files in 16.9 s. `HW_AGNSS_RTCM_33` is 114 genuine RTCM 3 messages (1019 GPS, 1020 GLONASS,
    // 1042 BeiDou, 1046 Galileo) — ordinary broadcast ephemeris, which is the short-lived kind the
    // band means by "Data expires in 6 h". The six `HW_PGNSS_*` files are Huawei's own predicted
    // ephemeris and are opaque.
    const val SVC_GNSS_ASK = 0x1F
    const val GNSS_NOTIFY = 0x01      // band --> phone: "I want assistance data"
    const val GNSS_WHAT = 0x02        // phone --> band: "what?"  band answers with a source string
    const val GNSS_READY = 0x03       // phone --> band: "it is here" — the band starts 0x1C on this

    const val SVC_GNSS_FILES = 0x1C
    const val GNSS_LIST = 0x01        // band asks what we hold; we answer ';'-joined names
    const val GNSS_PARAMS = 0x02      // band states unit size (tag 3) and block size (tag 4)
    const val GNSS_PICK = 0x03        // band names one file; we answer size (tag 2) + CRC16 (tag 3)
    const val GNSS_BLOCK = 0x04       // band asks for [offset, length); we ack then stream 0x05
    const val GNSS_DATA = 0x05        // phone --> band: ONE sequence byte then raw file bytes
    const val GNSS_DONE = 0x06        // band says the file is complete

    const val SVC_LOCATION = 0x18
    const val LOCATION_PUSH = 0x07

    // ---- Fitness settings (0x07) — the switches that decide what the band records --------
    //
    // Captured from Huawei Health on 2026-08-22 by toggling each switch with a decrypted btsnoop
    // running. **Heart rate and blood oxygen are absent from a fresh band because these are OFF, not
    // because they are unreachable** — continuous heart rate began recording the minute Health set
    // 0x17, at roughly one reading every five minutes.
    const val FIT_TRUSLEEP = 0x16
    const val FIT_CONTINUOUS_HR = 0x17
    const val FIT_AUTO_SPO2 = 0x24
    const val FIT_HIGH_HR_ALERT = 0x1D    // threshold in bpm, one byte
    const val FIT_LOW_HR_ALERT = 0x22
    const val FIT_LOW_SPO2_ALERT = 0x25   // threshold in percent

    // ---- WatchFace (0x27) — transferring a face is only HALF of installing one ----------
    //
    // The bytes go over 0x28, but the band does nothing with them until it is told to. Two more
    // commands finish the job: install (which needs the band's own screen size) and then activate.
    // Sending only the file leaves it stored and invisible, which is exactly how it looked when we
    // first tried it (白い熊: "It's not there").
    //
    // **This service answers with result 0, not 100000.** requireOk would reject its successes.
    const val SVC_WATCHFACE = 0x27
    const val WF_CAPABILITY = 0x01     // band's theme version and screen size
    const val WF_LIST = 0x02
    const val WF_CONTROL = 0x03        // tag 3: 01 = install, 02 = DELETE (not "make active")
    const val WF_PROGRESS = 0x05       // band-initiated; must be acknowledged

    // ---- FileUpload (0x28) — how a watch face gets ONTO the band ---------
    //
    // The band DRIVES this one: after the request is accepted it asks for a digest, states its
    // parameters, then repeatedly asks for a [offset, length] block which we answer with data
    // frames. Nothing is pushed unprompted.
    const val UPLOAD_REQUEST = 0x02
    const val UPLOAD_HASH = 0x03       // band asks; we answer with SHA-256 of the whole file
    const val UPLOAD_PARAMS = 0x04     // band states chunk size and block size
    const val UPLOAD_BLOCK = 0x05      // band: "send me [offset, length]"
    const val UPLOAD_DATA = 0x06       // us: raw, NOT TLV — see uploadFrame
    const val UPLOAD_DONE = 0x07       // band: finished

    // ---- FileTransfer (0x2C) — how sleep and RR intervals leave the band --
    //
    // The fitness service (0x07) hands out fixed-shape records one index at a time. Sleep and the
    // per-beat RR intervals are not records: they are FILES, fetched by name over this service.
    const val FILE_REQUEST = 0x01      // ask for a file by name over a time range
    const val FILE_NEGOTIATE = 0x03    // agree the chunk size
    const val FILE_START = 0x04        // "send it now", from an offset
    const val FILE_DATA = 0x05         // band -> phone, RAW bytes, pushed unprompted
    const val FILE_DONE = 0x06         // phone -> band, "I have it all"

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

    /**
     * The band's display language and unit system.
     *
     * This band has no language item in its own Settings, and that is not an omission: the
     * COMPANION owns the setting and pushes it. Huawei Health sends this exactly once per pairing,
     * right after announcing the phone — captured on 白い熊's band as
     * `0x0C/0x01 {1: "en-US", 2: 00}`, answered with the band's success code.
     *
     * That is also why the band has been English since the diagnostic trip to another phone: that
     * phone's Health pushed its own locale, and nothing here has ever sent this command, so the
     * band simply kept the last word it was given.
     *
     * [locale] is a BCP-47 tag in `xx-YY` form. An unsupported one is not an error — outside
     * mainland China the band falls back to English — so a wrong tag costs a language, not a band.
     */
    /**
     * Ask what a file holds for a time range.
     *
     * [id] selects WHICH stream inside a container file and is file-specific: `sequence_data` needs
     * one (Health was seen asking for 700004, 700013 and 700021), `rrisqi_data.bin` is fetched
     * without it. Only some ids return anything, so this is the knob a probe turns.
     *
     * The band answers with the byte count in tag 4, or result 144001 when it holds nothing for
     * that window — which is an ANSWER, not a fault.
     */
    fun fileRequest(name: String, type: Int, fromSeconds: Long, toSeconds: Long, id: Int?): ByteArray =
        tlv(1, name) +
            tlv(2, byteArrayOf(type.toByte())) +
            tlv(5, HuaweiProtocol.intBytes(fromSeconds.toInt(), 4)) +
            tlv(6, HuaweiProtocol.intBytes(toSeconds.toInt(), 4)) +
            (id?.let { tlv(12, HuaweiProtocol.intBytes(it, 4)) } ?: ByteArray(0))

    /** Empty tags: "tell me the chunk size", rather than proposing one. */
    fun fileNegotiate(type: Int): ByteArray =
        tlv(1, byteArrayOf(type.toByte())) + tlv(2) + tlv(3) + tlv(5)

    fun fileStart(type: Int, offset: Int, size: Int, id: Int?): ByteArray =
        tlv(1, byteArrayOf(type.toByte())) +
            tlv(2, HuaweiProtocol.intBytes(offset, 4)) +
            tlv(3, HuaweiProtocol.intBytes(size, 4)) +
            (id?.let { tlv(4, HuaweiProtocol.intBytes(it, 4)) } ?: ByteArray(0))

    /**
     * Offer a file to the band.
     *
     * [fileId] is the slot the band will use to refer to it for the rest of the transfer — Huawei
     * Health uses 1 for a watch face. [assetId] and [version] are the face's own identifiers, sent
     * alongside the name, which is itself `<assetId>_<version>`.
     */
    fun uploadRequest(
        name: String,
        size: Int,
        fileId: Int,
        assetId: String?,
        version: String?,
    ): ByteArray =
        tlv(1, name) +
            tlv(2, HuaweiProtocol.intBytes(size, 4)) +
            tlv(3, byteArrayOf(fileId.toByte())) +
            (assetId?.let { tlv(5, it) } ?: ByteArray(0)) +
            (version?.let { tlv(6, it) } ?: ByteArray(0))

    /** The band asks for this before it will take a byte: SHA-256 of the entire file. */
    fun uploadHash(fileId: Int, sha256: ByteArray): ByteArray =
        tlv(1, byteArrayOf(fileId.toByte())) + tlv(3, sha256)

    /**
     * One slice of the file — **raw bytes, not TLV.**
     *
     * `fileId | seq | offset(4 BE) | data`. Every frame carries its own absolute offset, which is
     * what makes the transfer restartable and a repeated block harmless. [seq] is the frame's index
     * within the current block and wraps at 8; the band tracks it.
     */
    fun uploadFrame(fileId: Int, seq: Int, offset: Int, data: ByteArray): ByteArray =
        byteArrayOf(fileId.toByte(), (seq and 0x07).toByte()) +
            HuaweiProtocol.intBytes(offset, 4) + data

    /** Acknowledge one of the band's own upload frames. */
    /**
     * Where the phone thinks it is.
     *
     * **The coordinates are LITTLE-endian IEEE-754 doubles**, while every integer elsewhere in this
     * protocol is big-endian. Writing them big-endian does not produce an obviously wrong number —
     * it produces something like 10⁻¹²⁹, which reads as a rounding artefact rather than a byte-order
     * mistake, so it does not announce itself.
     */
    fun location(epochSeconds: Long, latitude: Double, longitude: Double): ByteArray =
        tlv(1, HuaweiProtocol.intBytes(epochSeconds.toInt(), 4)) +
            tlv(2, leDouble(latitude)) +
            tlv(3, leDouble(longitude))

    /**
     * The weather to display.
     *
     * Tags 129 and 133 carry the condition and icon in the capture, but one sample cannot pin small
     * integers with no anchor, so they are omitted rather than guessed. The band shows temperature
     * and place without them; sending a wrong condition code would put a confidently wrong icon on
     * 白い熊's wrist, which is worse than none.
     */
    /**
     * The current-weather push, in the shape Health actually sends.
     *
     * Rebuilt 2026-08-25 against a capture of Health pushing to this same band, after our own shape
     * was acknowledged with `100000` and silently discarded for two days. Five things were wrong and
     * they had to be fixed together — each replay that changed only some of them still failed, which
     * is why this is one change rather than five:
     *
     *  * **Tags 17 and 18 are FOUR bytes**, big-endian. We sent one, which is the only outright
     *    malformation: a length check failing after the transport has already answered success is
     *    exactly the symptom we had.
     *  * **The day range lives in container 133** `{6: low, 7: high}`, not in 17/18. What 17 and 18
     *    actually mean is unmapped — Health sent 19 and 17 against a current 17 and a 13–21 range,
     *    so 18 matched the current temperature and 17 matched nothing we can name.
     *  * **Container 129, and tags 10 and 15**, are absent from ours entirely.
     *  * **The containers come FIRST** on the wire.
     *  * Tag 12 is not "now": it is the day marker, equal to the first daily-forecast entry's own
     *    timestamp, and the daily entries step from it by exactly 86400 s.
     *
     * And a correct push is still not enough on its own — the band draws the current hour out of
     * the [forecast], so [HuaweiSyncRunner.pushWeather] sends both.
     */
    fun weather(
        place: String,
        temperatureC: Int,
        dayMarkerSeconds: Long,
        humidityPercent: Int?,
        highC: Int?,
        lowC: Int?,
        uvIndex: Int? = null,
        windKmh: Int? = null,
    ): ByteArray =
        // 129 and 133 lead, as they do in every captured push.
        tlv(129, tlv(2, byteArrayOf(1)) + tlv(3, byteArrayOf(1, 3))) +
            (if (highC == null || lowC == null) ByteArray(0)
            else tlv(133, tlv(6, byteArrayOf(lowC.toByte())) + tlv(7, byteArrayOf(highC.toByte())))) +
            tlv(8, place) +
            tlv(9, byteArrayOf(temperatureC.toByte())) +
            tlv(10, byteArrayOf(0)) +
            tlv(12, HuaweiProtocol.intBytes(dayMarkerSeconds.toInt(), 4)) +
            // The UV index the band shows on its own UV page — confirmed 2026-08-25 by sending 9
            // and reading "9 / Strong" off the wrist. It had stood in this file as a hard-coded 3
            // labelled "unmapped" because Health's four captured pushes all happened to carry 3.
            tlv(15, byteArrayOf((uvIndex ?: 0).toByte())) +
            (humidityPercent?.let { tlv(16, byteArrayOf(it.toByte())) } ?: ByteArray(0)) +
            // Wind speed in km/h — confirmed 2026-08-25 by sending 100 and reading "100 km/h" off
            // the wrist. Health's capture had it at 19 against a 17°C reading, which is why it sat
            // here for two days as "unmapped, temperature-like": 19 is a plausible temperature.
            // A capture cannot tell a wind from a temperature; a screen can.
            tlv(17, HuaweiProtocol.intBytes(windKmh ?: 0, 4)) +
            // Still genuinely unmapped. Moving it to 44 changed nothing visible on any of the ten
            // pages, so it is carried at the right WIDTH and nothing is claimed about its meaning.
            tlv(18, HuaweiProtocol.intBytes(temperatureC, 4))

    /**
     * The temperature unit the band displays.
     *
     * On the WEATHER service, not the locale one — `0x0C/0x05` was a reasonable guess from the
     * locale command's unit-system byte and it is wrong. Captured 2026-08-23 by switching it both
     * ways with a decrypted snoop running.
     */
    fun weatherUnit(fahrenheit: Boolean): ByteArray =
        tlv(1, byteArrayOf(if (fahrenheit) 1 else 0))

    /**
     * Turn the band's weather display OFF.
     *
     * **There is no matching "on".** Switching the setting back on sent the band nothing at all —
     * Health simply resumes pushing weather, and the band shows whatever it is next given. So the
     * way to re-enable weather is to push some: see [weather].
     */
    fun weatherDisable(): ByteArray = tlv(129, byteArrayOf(2, 1, 2))

    /**
     * The ON half of the same switch — `{129: {2: 01}}`.
     *
     * The note above said there was no matching "on", inferred from Health resuming pushes after the
     * UI switch was turned back on. The 2026-08-25 capture shows why that inference was wrong: Health
     * sends this at PAIRING and again after a reconnect, not when the switch is touched. It is part
     * of establishing the weather session, which is exactly the part a companion that only ever
     * pushes would never see.
     */
    fun weatherEnable(): ByteArray = tlv(129, byteArrayOf(2, 1, 1))

    /**
     * The forecast — and the reason a current-weather push alone never showed anything.
     *
     * Established 2026-08-25 by experiment, after a byte-exact replay of Health's 59-byte push was
     * acknowledged and changed nothing: **the band draws the CURRENT HOUR OUT OF THE FORECAST, not
     * out of the push.** Replaying Health's push together with its 1290-byte `0x0F/0x08` put its
     * reading on screen instantly, and the temperature shown was 20 — the forecast's 13:00 entry —
     * while the push's own current-temperature tag said 17. That also explains the very first
     * symptom: a band with no hourly entry for *now* draws the current temperature as `--`.
     *
     * Structure, decoded from the capture:
     *
     *   129 → array of hourly `130 {3: epoch, 4: 01, 5: °C, 6: 07, 7: condition, 8: u32}`
     *   144 → array of daily  `145 {18: epoch, 19: condition, 20: high, 21: low,
     *                               22: sunrise, 23: sunset, 26: moonrise, 27: moonset, 30: phase}`
     *
     * Tags 4 and 6 are constant in every one of Health's 24 hourly entries; tag 8 tracks the
     * temperature within a degree and is not otherwise understood, so it is given the temperature
     * rather than a guess dressed up as a different number.
     *
     * **We send only the hours and days we actually have.** Health sends 24 and 15; padding ours out
     * to match would mean inventing weather, which is exactly the kind of confident fiction this
     * band's protocol has already cost enough time. A short array is what an honest forecast looks
     * like when the source gives one reading.
     */
    /**
     * How many hourly entries the band demands. Not a convention — a hard gate.
     *
     * Measured 2026-08-25 against the band itself, with the reply code finally being read:
     * 24 hourly + 15 daily → `100000`; 24 + 14 → `100000`; **23 + 15 → `115001`**; 24 + 0 → refused;
     * 3 + 1 (Health's own bytes, merely fewer of them) → refused.
     *
     * Everything `天気送信` ever sent carried ONE hour and ONE day, so every forecast it ever sent
     * was thrown away — and because the band treats the push and the forecast as a single record,
     * the push was discarded with it. That is the whole of the two-day "Weather pushed, screen never
     * moves" bug: not the shape, not the sequence, not the encoding. The count.
     */
    const val FORECAST_HOURS = 24

    /**
     * How many daily entries the band demands. Also a hard gate, and a separate one.
     *
     * Measured the same afternoon, holding the hourly count at 24: 7 days → `115001`, **8 days →
     * `100000`**, 10, 14 and 15 all accepted. A run with 15 days carrying ONLY tags 18–21 — no
     * sunrise, no sunset, no moon — was also accepted, so the sun and moon tags are not part of
     * this gate and an earlier test that seemed to say so was void (its payload had been refused
     * for the count before anything in it was read).
     */
    const val FORECAST_DAYS_MIN = 8

    /**
     * A weather word from the 天気 contract to the band's own condition code.
     *
     * **Measured on the band across five sweeps, 2026-08-25.** Every cell printed its own code as
     * its temperature — hour `n` carried condition `n` and read `n°` — so a photograph at any scroll
     * position labelled each icon and no counting was needed. 白い熊 photographed the pages; the
     * icons were read off the wrist.
     *
     * ```
     *  0 sun behind cloud      12 downpour              24 sun behind cloud
     *  1 mostly sunny          13 snow, light           25 heavy rain
     *  2 bare cloud            14 snow                  26 snow
     *  3 rain                  15 snow                  27 snow, heavier
     *  4 thunderstorm          16 snow                  28 snow, heavy
     *  5 sun behind cloud      17 snow, heavy           29 blowing snow
     *  6 rain                  18 fog                   30 blowing snow
     *  7 one drop              19 mist                  31 blowing snow
     *  8 two drops             20 wind-blown haze       32 single flake
     *  9 rain                  21 light drops           33 thermometer + sun   (heat)
     * 10 rain, heavier         22 light drops           34 thermometer + flake (cold)
     * 11 rain, heavy           23 rain                  35 BARE WIND LINES
     * ```
     *
     * **The range is 0–35.** 36, 37 and 48 all draw the same plain sun, and 48 is far outside any
     * plausible table, so that sun is the out-of-range fallback rather than a clear-sky icon. A
     * wrong code therefore fails *cheerfully* — it renders as fine weather — which is the kind of
     * failure that hides, so unrecognised words map to 0 and never into the fallback.
     *
     * **There is no sleet or hail icon.** Nothing in 0–35 depicts mixed or frozen-pellet
     * precipitation: the set runs sun / cloud / rain / snow / fog / wind / thermometer and stops.
     * Codes 5 and 6 were read as "mixed drops" in an earlier round and mapped to `sleet` and `hail`
     * on that basis; photographed side by side with a known rain icon between them they are plainly
     * a sun-behind-cloud and a plain rain, and that mapping was wrong. Both words now fall back to
     * the nearest **real** family, which is a choice between two imperfect answers rather than a
     * precision the band does not offer: `sleet` to snow because it is frozen and what it changes
     * for the wearer is the ground, `hail` to rain because it falls as discrete precipitation.
     * Neither is invented, and neither claims to be exact.
     *
     * Round one is worth recording as a warning: it swept hourly tag 6 across 0…23 and all 24 cells
     * drew the same icon, while the daily list plainly separated cloud from rain. **Tag 6 is not the
     * hourly condition; tag 4 is.** The hourly and daily spaces are ONE table.
     */
    fun conditionCode(word: String?): Int = when (word?.trim()?.lowercase()) {
        "clear", "mostly_clear" -> 1
        "partly_cloudy" -> 0
        "cloudy", "overcast" -> 2
        "wind" -> 35
        "fog" -> 18
        "haze" -> 19
        "drizzle" -> 7
        "rain" -> 9
        "heavy_rain" -> 12
        // No mixed-precipitation icon exists — see above. Nearest real family, honestly labelled.
        "sleet" -> 14
        "hail" -> 9
        "snow" -> 14
        "heavy_snow" -> 17
        "thunderstorm" -> 4
        else -> 0
    }

    /**
     * Stretch however many hours we actually know into the 24 the band insists on.
     *
     * Deliberately a separate, named step rather than something [forecast] does quietly, because
     * padding is the difference between a forecast and a flat line pretending to be one. The caller
     * pads, so the caller can say how much of what it sent was real.
     *
     * Each missing hour repeats the last known point, re-stamped to its own hour. Extrapolating a
     * curve would look more like weather and be no more true.
     */
    fun padHours(known: List<HourlyPoint>, startEpochSeconds: Long): List<HourlyPoint> {
        require(known.isNotEmpty()) { "padHours needs at least one real reading to repeat" }
        return (0 until FORECAST_HOURS).map { i ->
            val hour = startEpochSeconds + 3600L * i
            val src = known.getOrNull(i) ?: known.last()
            src.copy(epochSeconds = hour)
        }
    }

    /**
     * Stretch however many days we know into the [FORECAST_DAYS_MIN] the band insists on.
     *
     * Separate and named for the same reason as [padHours]: repeating a high and a low forward for
     * a week is not a week's forecast, and the code should not be able to pretend otherwise
     * silently. Each padded day repeats the last known one, re-stamped 86400 s onward.
     */
    fun padDays(known: List<DailyPoint>, startEpochSeconds: Long): List<DailyPoint> {
        require(known.isNotEmpty()) { "padDays needs at least one real day to repeat" }
        return (0 until maxOf(FORECAST_DAYS_MIN, known.size)).map { i ->
            val day = startEpochSeconds + 86_400L * i
            val src = known.getOrNull(i) ?: known.last()
            // A repeated day must not carry the FIRST day's sunrise: those are real values for a
            // real date, and stamping them onto another date makes them wrong rather than absent.
            if (i < known.size) src.copy(epochSeconds = day)
            else src.copy(
                epochSeconds = day,
                sunriseSeconds = null, sunsetSeconds = null,
                moonriseSeconds = null, moonsetSeconds = null, moonPhase = null,
            )
        }
    }

    /**
     * The `0x0F/0x08` forecast: [FORECAST_HOURS] hourly entries and [FORECAST_DAYS_MIN] days.
     *
     * @throws IllegalArgumentException rather than letting the band refuse it. A wrong count comes
     *   back as `115001` on a call whose reply nobody used to read, which cost two days; failing
     *   here names the problem at the point that can fix it.
     */
    fun forecast(
        hourly: List<HourlyPoint>,
        daily: List<DailyPoint>,
    ): ByteArray {
        require(hourly.size == FORECAST_HOURS) {
            "the band refuses a forecast that is not exactly $FORECAST_HOURS hours (got ${hourly.size})"
        }
        require(daily.size >= FORECAST_DAYS_MIN) {
            "the band refuses a forecast with fewer than $FORECAST_DAYS_MIN days (got ${daily.size})"
        }
        val hours = hourly.fold(ByteArray(0)) { acc, h ->
            acc + tlv(
                130,
                tlv(3, HuaweiProtocol.intBytes(h.epochSeconds.toInt(), 4)) +
                    // Tag 4 is the condition and tag 6 is a constant — NOT the other way round,
                    // which is what this file assumed until the icons were swept on the band.
                    tlv(4, byteArrayOf(h.condition.toByte())) +
                    tlv(5, byteArrayOf(h.temperatureC.toByte())) +
                    tlv(6, byteArrayOf(7)) +
                    tlv(7, byteArrayOf(h.uvIndex.toByte())) +
                    tlv(8, HuaweiProtocol.intBytes(h.feelsLikeC, 4)),
            )
        }
        val days = daily.fold(ByteArray(0)) { acc, d ->
            acc + tlv(
                145,
                tlv(18, HuaweiProtocol.intBytes(d.epochSeconds.toInt(), 4)) +
                    tlv(19, byteArrayOf(d.condition.toByte())) +
                    tlv(20, byteArrayOf(d.highC.toByte())) +
                    tlv(21, byteArrayOf(d.lowC.toByte())) +
                    (d.sunriseSeconds?.let { tlv(22, HuaweiProtocol.intBytes(it.toInt(), 4)) } ?: ByteArray(0)) +
                    (d.sunsetSeconds?.let { tlv(23, HuaweiProtocol.intBytes(it.toInt(), 4)) } ?: ByteArray(0)) +
                    (d.moonriseSeconds?.let { tlv(26, HuaweiProtocol.intBytes(it.toInt(), 4)) } ?: ByteArray(0)) +
                    (d.moonsetSeconds?.let { tlv(27, HuaweiProtocol.intBytes(it.toInt(), 4)) } ?: ByteArray(0)) +
                    (d.moonPhase?.let { tlv(30, byteArrayOf(it.toByte())) } ?: ByteArray(0)),
            )
        }
        return tlv(129, hours) + tlv(144, days)
    }

    /** One hour of the forecast the band draws its current reading from. */
    /**
     * One hour of the forecast.
     *
     * [condition] is **tag 4** and [uvIndex] is tag 7. Both were wrong here until 2026-08-25:
     * the condition was being written into tag 6 (which changes nothing on screen — swept across
     * 0…23 with no visible effect) and the UV into tag 7 only by accident of naming. Health holds
     * a constant 1 in tag 4 and a constant 7 in tag 6, and its tag 7 runs 3,4,2,2,1,1,1,0,0…0,1,1
     * — zero from dusk until morning, which is UV, not weather.
     */
    data class HourlyPoint(
        val epochSeconds: Long,
        val temperatureC: Int,
        val condition: Int = 1,
        val uvIndex: Int = 0,
        val feelsLikeC: Int = temperatureC,
    )

    /** One day of the forecast, which is where the band's high/low come from. */
    data class DailyPoint(
        val epochSeconds: Long,
        val highC: Int,
        val lowC: Int,
        val condition: Int = 1,
        val sunriseSeconds: Long? = null,
        val sunsetSeconds: Long? = null,
        /**
         * The moon, which the band shows on its further weather pages.
         *
         * **The band does not need any of these.** Settled 2026-08-25 on an ACCEPTED forecast:
         * we sent no moonrise, no moonset and no phase byte, and the band's Moon page still read
         * 02:33 / 19:07 and its Moon-phase page still read "Waxing gibbous · 93 % · Day 14",
         * correctly. It computes them from the position frame we send it.
         *
         * (An earlier test appeared to show the same thing and was worthless — that payload had
         * been refused with 115001 before anything in it was read. Only this one counts.)
         *
         * The fields stay because Health sends them and a future firmware might read them, but
         * nothing depends on filling them, and there is no honest way to invent a moonrise.
         */
        val moonriseSeconds: Long? = null,
        val moonsetSeconds: Long? = null,
        val moonPhase: Int? = null,
    )

    /** `{129: <empty>}` — Health sends this within milliseconds of every push. Meaning unmapped. */
    fun weatherPushDone(): ByteArray = tlv(129)

    private fun leDouble(v: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        return ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFF).toByte() }
    }

    /** A plain on/off switch: `{1: 01}` or `{1: 00}`. */
    fun fitnessToggle(on: Boolean): ByteArray = tlv(1, byteArrayOf(if (on) 1 else 0))

    /**
     * An alert switch with its threshold.
     *
     * Turning one OFF drops the threshold byte entirely rather than sending zero — that is what
     * Health does, and a zero threshold would be a legitimate-looking instruction to alert always.
     */
    fun fitnessAlert(on: Boolean, threshold: Int): ByteArray =
        if (on) tlv(1, byteArrayOf(1)) + tlv(2, byteArrayOf(threshold.toByte()))
        else tlv(1, byteArrayOf(0))

    /** Ask the band its theme version and screen size — the install command needs the latter. */
    fun watchFaceCapability(): ByteArray =
        tlv(1) + tlv(2) + tlv(3) + tlv(4) + tlv(5) + tlv(20) + tlv(21)

    /**
     * Announce a face BEFORE sending it — the step without which nothing works.
     *
     * [metaJson] is the face's store metadata, captured alongside the file. It carries a `content`
     * blob and a `contentSign` signature from Huawei's own servers, so it cannot be fabricated —
     * which is exactly why a face has to be captured rather than constructed, and why this only
     * installs faces already owned.
     *
     * Without this the band still accepts the file, verifies its digest, and acknowledges the
     * transfer — and then discards it, because there is no face record to attach the bytes to. Every
     * signal says success and nothing appears on the wrist.
     */
    fun watchFaceAnnounce(
        assetId: String,
        version: String,
        width: Int,
        height: Int,
        metaJson: String,
    ): ByteArray =
        tlv(1, assetId) + tlv(2, version) + tlv(3, byteArrayOf(1)) +
            tlv(5, HuaweiProtocol.intBytes(width, 2)) +
            tlv(6, HuaweiProtocol.intBytes(height, 2)) +
            tlv(8, metaJson)

    /** Install a face already transferred. [width]/[height] come from [watchFaceCapability]. */
    fun watchFaceInstall(assetId: String, version: String, width: Int, height: Int): ByteArray =
        tlv(1, assetId) + tlv(2, version) + tlv(3, byteArrayOf(1)) +
            tlv(5, HuaweiProtocol.intBytes(width, 2)) +
            tlv(6, HuaweiProtocol.intBytes(height, 2))

    /**
     * REMOVE a face from the band.
     *
     * This was called `watchFaceActivate` until 2026-08-23, on the reading that tag 3 = 02 made a
     * face current. It does the opposite, and sending it right after an install deleted the face we
     * had just spent a minute transferring: the band showed it for an instant, then fell back to the
     * previous one, and it was absent from the band's own list.
     *
     * The capture settles it across all 33 of Health's installs — after `tag3=01` the asset APPEARS
     * in the [WF_LIST] reply, and after `tag3=02` the named asset DISAPPEARS from it. Installing is
     * also what puts the face on screen, so there is no activate step to send: Health never sends
     * one. What Health does send is this, against the PREVIOUS face, to keep the band pruned.
     */
    fun watchFaceDelete(assetId: String, version: String): ByteArray =
        tlv(1, assetId) + tlv(2, version) + tlv(3, byteArrayOf(2))

    /** Ask which faces are on the band. The reply carries free space and one record per face. */
    fun watchFaceList(): ByteArray = tlv(1) + tlv(6, byteArrayOf(3))

    // --- workouts ----------------------------------------------------------------------------
    //
    // A recorded exercise is NOT one of the per-minute records the history fetch walks: it has its
    // own service, its own numbering, and — for an outdoor one — a GPS track that is not a record
    // at all but a FILE, pulled over the same 0x2C channel as sleep and the RR intervals.
    //
    // Everything here is unproven against 白い熊's band, because the band has never recorded a
    // workout: its own log says `"GPSTrack":{"Count":0}` and Huawei Health's every request for the
    // workout list came back empty. Written from published protocol descriptions, to be confirmed
    // the first time a walk exists.

    const val WORKOUT_LIST = 0x07       // which workouts exist in a time range
    const val WORKOUT_TOTALS = 0x08     // one workout's summary
    const val WORKOUT_SAMPLES = 0x0A    // its per-sample stream (heart rate, speed, altitude)
    const val WORKOUT_PACE = 0x0C       // its per-lap pace blocks

    /** The workouts recorded between two instants. Both are epoch SECONDS, big-endian like all TLV. */
    fun workoutList(fromSeconds: Long, toSeconds: Long): ByteArray =
        tlv(0x81, tlv(3, HuaweiProtocol.intBytes(fromSeconds.toInt(), 4)) +
            tlv(4, HuaweiProtocol.intBytes(toSeconds.toInt(), 4)))

    /** One workout's summary, by the number the list gave it. */
    fun workoutTotals(number: Int): ByteArray =
        tlv(0x81, tlv(2, HuaweiProtocol.intBytes(number, 2)))

    /**
     * A workout's GPS track, as a file name.
     *
     * The number is rendered in plain decimal — workout 12 is `12_gps.bin`. `_pdr.bin` is the
     * dead-reckoning track a band records when it never saw a satellite; it is a relative x/y
     * path, not coordinates, which is why it gets its own name and its own decoder or none at all.
     */
    fun gpsTrackName(number: Int): String = "${number}_gps.bin"

    fun pdrTrackName(number: Int): String = "${number}_pdr.bin"

    /** The band reports install progress unprompted and expects each report acknowledged. */
    fun watchFaceProgressAck(assetId: String, version: String): ByteArray =
        tlv(1, assetId) + tlv(2, version) +
            tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4))

    fun uploadAck(fileId: Int): ByteArray =
        tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.intBytes(HuaweiProtocol.RESULT_SUCCESS, 4)) +
            tlv(1, byteArrayOf(fileId.toByte()))

    fun fileDone(type: Int): ByteArray =
        tlv(1, byteArrayOf(type.toByte())) + tlv(2, byteArrayOf(1))

    fun setLocale(locale: String, imperial: Boolean): ByteArray =
        tlv(1, locale) + tlv(2, byteArrayOf(if (imperial) 1 else 0))

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
