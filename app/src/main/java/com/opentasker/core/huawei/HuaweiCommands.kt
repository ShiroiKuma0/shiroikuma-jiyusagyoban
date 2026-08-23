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
    fun weather(
        place: String,
        temperatureC: Int,
        observedAtSeconds: Long,
        humidityPercent: Int?,
        highC: Int?,
        lowC: Int?,
    ): ByteArray =
        tlv(8, place) +
            tlv(9, byteArrayOf(temperatureC.toByte())) +
            tlv(12, HuaweiProtocol.intBytes(observedAtSeconds.toInt(), 4)) +
            (humidityPercent?.let { tlv(16, byteArrayOf(it.toByte())) } ?: ByteArray(0)) +
            // 17 is the LOW and 18 the HIGH — the reverse of what this file said until a capture
            // on 2026-08-23 showed 16 °C now with 12 and 18 either side of it. Written the old way
            // round the band would have shown the day's range inverted, which reads as plausible
            // weather rather than as a bug.
            (lowC?.let { tlv(17, byteArrayOf(it.toByte())) } ?: ByteArray(0)) +
            (highC?.let { tlv(18, byteArrayOf(it.toByte())) } ?: ByteArray(0))

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
