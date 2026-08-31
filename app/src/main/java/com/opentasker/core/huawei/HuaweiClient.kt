package com.opentasker.core.huawei

/**
 * The connection sequences, driving [HuaweiSession] over whatever [HuaweiTransport] it is given.
 *
 * Android-free, so the whole flow can be exercised against a fake transport. The real one is
 * [HuaweiRfcommClient].
 *
 * This is a direct transcription of the procedure verified end to end against 白い熊's band on
 * firmware 6.0.0.125 — a factory-reset band reaches a working watch face in about fifteen seconds,
 * with no Huawei account and no Huawei software. Two things gate it, and both are honoured here:
 *
 *  1. **A real Bluetooth pairing must already exist.** A band the platform silently bonded without
 *     user confirmation will speak this entire protocol flawlessly and still never leave its
 *     out-of-box wizard. Pairing is the caller's job; [provision] assumes it is done.
 *  2. **The FULL configuration set must be sent.** The band closes its wizard when it considers
 *     itself configured — not on any single command. Omitting `0x31`, `0x30`, `0x3F` or `0x3E`
 *     leaves it stuck indefinitely with every individual command still returning success.
 *
 * And the band waits only SECONDS after the pairing before abandoning its own flow, so provisioning
 * must follow immediately with no human-speed gap.
 */
class HuaweiClient(private val session: HuaweiSession) {

    /** What LinkParams told us. */
    data class Link(
        val authVersion: Int,
        val deviceSupportType: Int,
        val serverNonce: ByteArray,
    )

    data class Identity(
        val firmware: String?,
        val serial: String?,
        val model: String?,
        /** The band's SECOND MAC — its BLE identity, one bit from the Classic one. */
        val bleMac: String?,
    )

    /**
     * The six commands that make the band treat us as its companion rather than a mere caller.
     *
     * Discovered 2026-08-25 by diffing Huawei Health's RECONNECT against ours, frame by frame. Both
     * do the same handshake; Health then sends these six, and 104 ms after the last one the band
     * starts volunteering — the weather pull, DataSync, RR intervals. Ours sent none of them and
     * the band had never once volunteered anything to it.
     *
     * Not pairing-only: the cold pairing and the reconnect send the identical six, in this order,
     * with identical bytes. Every one is a read or a declaration; none writes a setting, a clock,
     * a language or any wizard state, so this is safe on every session.
     *
     * Failures are the caller's to ignore: a band that refuses one of these is still perfectly
     * usable for the request/response work that has always worked.
     */
    suspend fun announceCompanion() {
        val zone = java.util.TimeZone.getDefault()
        val offsetMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        runCatching { session.request(HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_PRODUCT_INFO, HuaweiCommands.productInfo()) }
        runCatching {
            session.request(
                HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_SET_TIME,
                HuaweiCommands.setTime(System.currentTimeMillis() / 1000, offsetMinutes / 60, offsetMinutes % 60),
            )
        }
        runCatching { session.request(HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_SUPPORTED_SERVICES, HuaweiCommands.supportedServices()) }
        runCatching { session.request(HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_SUPPORTED_COMMANDS, HuaweiCommands.supportedCommands()) }
        runCatching { session.request(HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_EXPAND_CAPABILITY, HuaweiCommands.expandCapability()) }
        runCatching { session.request(HuaweiCommands.SVC_ACCOUNT, HuaweiCommands.ACC_EXTENDED_ACCOUNT, HuaweiCommands.extendedAccount()) }
    }

    /** Step 1: LinkParams. Establishes the crypto parameters everything else depends on. */
    suspend fun linkParams(): Link {
        val frame = session.request(
            HuaweiCommands.SVC_DEVICE_CONFIG,
            HuaweiCommands.CMD_LINK_PARAMS,
            HuaweiCommands.linkParams(),
            encrypted = false,
        )
        val tlvs = frame.tlvs
        val five = tlvs.firstOrNull { it.tag == 5 }?.value
            ?: throw IllegalStateException("LinkParams carried no nonce")
        // authVersion is byte 1 of tag 5; the 16-byte server nonce follows it.
        val authVersion = five[1].toInt() and 0xFF
        val serverNonce = five.copyOfRange(2, minOf(18, five.size))
        val dst = tlvs.firstOrNull { it.tag == 7 }?.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        session.deviceSupportType = dst
        return Link(authVersion, dst, serverNonce)
    }

    /** Step 2: the band's own status byte. Health reads it, so we do too. */
    suspend fun deviceStatus(): Int? = runCatching {
        session.request(
            HuaweiCommands.SVC_DEVICE_CONFIG,
            HuaweiCommands.CMD_DEVICE_STATUS,
            HuaweiCommands.deviceStatus(),
            encrypted = false,
            // 15 s, matching the reference run. The band can be slow to answer this one, and the
            // default 6 s would swallow it as a timeout — silently skipping a step Health performs.
            timeoutMs = 15_000,
        ).tag(1)?.firstOrNull()?.toInt()?.and(0xFF)
    }.getOrNull()

    /** Step 3: SecurityNegotiation. Without it the band answers Auth with error 100004. */
    suspend fun securityNegotiation(deviceSupportType: Int, deviceId: String, phoneModel: String) {
        session.request(
            HuaweiCommands.SVC_DEVICE_CONFIG,
            HuaweiCommands.CMD_SECURITY_NEGOTIATION,
            HuaweiCommands.securityNegotiation(
                HuaweiCommands.authModeFor(deviceSupportType), deviceId, phoneModel,
            ),
            encrypted = false,
            timeoutMs = 15_000,
        )
    }

    /** Step 4: the PIN, which the band hands over already encrypted. No user interaction. */
    suspend fun fetchPin(authVersion: Int): ByteArray {
        val frame = session.request(
            HuaweiCommands.SVC_DEVICE_CONFIG,
            HuaweiCommands.CMD_PIN_CODE,
            HuaweiCommands.pinCode(),
            encrypted = false,
            timeoutMs = 15_000,
        )
        val ciphertext = frame.tag(1) ?: throw IllegalStateException("no PIN payload")
        val iv = frame.tag(2) ?: throw IllegalStateException("no PIN IV")
        return HuaweiCrypto.decryptPin(authVersion, ciphertext, iv)
    }

    /**
     * First-time bind: HiChain pass A (bind) then pass B (auth).
     *
     * @return the authToken to persist, and the session key for this connection.
     */
    suspend fun bind(deviceId: String, pin: ByteArray, requestId: Long): ByteArray {
        val authIdSelf = deviceId.toByteArray(Charsets.US_ASCII)
        val bindPass = HuaweiHiChain(
            session, HuaweiHiChain.OP_BIND, authIdSelf, HuaweiCrypto.pinKey(pin), requestId,
        )
        bindPass.run()
        val token = bindPass.authToken
            ?: throw IllegalStateException("HiChain bind produced no auth token")
        authenticate(deviceId, token, requestId)
        return token
    }

    /** Routine reconnect: HiChain pass B alone, on the stored token. No PIN, no re-bind. */
    suspend fun authenticate(deviceId: String, authToken: ByteArray, requestId: Long) {
        val pass = HuaweiHiChain(
            session, HuaweiHiChain.OP_AUTH, deviceId.toByteArray(Charsets.US_ASCII),
            authToken, requestId,
        )
        pass.run()
        session.sessionKey = pass.finalKey
            ?: throw IllegalStateException("HiChain auth produced no session key")
    }

    suspend fun identity(): Identity {
        val frame = session.request(
            HuaweiCommands.SVC_DEVICE_CONFIG,
            HuaweiCommands.CMD_PRODUCT_INFO,
            HuaweiCommands.productInfo(),
            timeoutMs = 10_000,
        )
        val tlvs = session.decrypt(frame)
        fun str(tag: Int) = tlvs.firstOrNull { it.tag == tag }
            ?.value?.toString(Charsets.UTF_8)?.trim()?.trimEnd('\u0000')?.ifEmpty { null }
        return Identity(str(33) ?: str(7), str(9), str(10), str(34))
    }

    suspend fun battery(): Int? = runCatching {
        val frame = session.request(
            HuaweiCommands.SVC_DEVICE_CONFIG, HuaweiCommands.CMD_BATTERY,
            HuaweiCommands.battery(), timeoutMs = 10_000,
        )
        session.decrypt(frame).firstOrNull { it.tag == 1 }
            ?.let { HuaweiProtocol.bytesToInt(it.value) }
    }.getOrNull()

    /**
     * The full configuration set, in Huawei Health's own order.
     *
     * Four of these — `0x31`, `0x30`, `0x3F`, `0x3E` — are what a factory-reset band will not leave
     * its wizard without, and their absence is invisible: every other command still returns success.
     *
     * Failures are tolerated individually rather than aborting: some are fire-and-forget by design
     * (the band never answers `0x1A/0x0A` or `0x01/0x3E`, so a timeout there is CORRECT), and a
     * refusal of one optional setting should not cost the whole provisioning run.
     */
    /**
     * Set the band's display language and unit system.
     *
     * Unlike most of [configure], this one REPORTS rather than tolerates: it is invoked because
     * someone asked for a specific language, so silently swallowing a refusal would leave them
     * staring at an unchanged band with no way to tell whether the command was even understood.
     *
     * @return true when the band answered with its success code.
     */
    suspend fun setLocale(locale: String, imperial: Boolean): Boolean = runCatching {
        val cfg = HuaweiCommands
        session.requireOk(cfg.SVC_LOCALE, cfg.CMD_SET_LOCALE, cfg.setLocale(locale, imperial))
        true
    }.getOrDefault(false)

    /**
     * Push a language the way the band actually accepts one.
     *
     * The capture is explicit that Health sends the locale **immediately after announcing itself**,
     * and `configure()` reproduces that adjacency. Sent on its own in an ordinary session, the band
     * returns its success code — 100000, the same one it returns for everything — and does not
     * change language: 白い熊 asked for English on 2026-08-29, was told it had switched, and the
     * band stayed Japanese. So the announcement goes first here too.
     *
     * @return whether the band ACKED. That is NOT whether the language changed — the band ACKs a
     *   locale it has no pack for just the same, falling back to English outside mainland China —
     *   so the caller must verify by reading, never by believing this.
     */
    suspend fun pushLocale(deviceName: String, locale: String, imperial: Boolean): Boolean {
        val cfg = HuaweiCommands
        // Never answered; send and move on, exactly as configure() does.
        runCatching {
            session.send(cfg.SVC_DEVICE_CONFIG, cfg.CMD_SETUP_DEVICE_STATUS, cfg.setUpDeviceStatus(deviceName))
        }
        return setLocale(locale, imperial)
    }

    suspend fun configure(deviceName: String, epochSeconds: Long, zoneHours: Int, zoneMinutes: Int, locale: String? = null) {
        suspend fun attempt(service: Int, command: Int, payload: ByteArray) {
            runCatching { session.request(service, command, payload, timeoutMs = 6_000) }
        }
        val cfg = HuaweiCommands
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_PRODUCT_INFO, cfg.productInfo())
        val time = cfg.setTime(epochSeconds, zoneHours, zoneMinutes)
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_SET_TIME, time)
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_SET_TIME, time)
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_SUPPORTED_SERVICES, cfg.supportedServices())
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_SUPPORTED_COMMANDS, cfg.supportedCommands())
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_EXPAND_CAPABILITY, cfg.expandCapability())

        // The four that actually close the wizard.
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_SETTING_RELATED, cfg.settingRelated())
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_ACCEPT_AGREEMENTS, cfg.acceptAgreements())
        attempt(cfg.SVC_DEVICE_CONFIG, cfg.CMD_REVERSE_CAPABILITIES, cfg.reverseCapabilities())
        // Never answered — send and move on rather than burning six seconds waiting.
        session.send(
            cfg.SVC_DEVICE_CONFIG, cfg.CMD_SETUP_DEVICE_STATUS, cfg.setUpDeviceStatus(deviceName),
        )

        // Health sends the locale here, immediately after the setup status. Only re-asserted when
        // one has been chosen: a band whose language was picked at first run must not have it
        // silently rewritten by us just because we reconnected.
        if (locale != null) attempt(cfg.SVC_LOCALE, cfg.CMD_SET_LOCALE, cfg.setLocale(locale, false))

        attempt(cfg.SVC_ACCOUNT, cfg.ACC_UNKNOWN_04, cfg.accountStep04())
        attempt(cfg.SVC_ACCOUNT, cfg.ACC_EXTENDED_ACCOUNT, cfg.extendedAccount())
        session.send(cfg.SVC_ACCOUNT, cfg.ACC_COUNTRY_CODE, cfg.countryCode("CZ", 0x07))
        attempt(cfg.SVC_ACCOUNT, cfg.ACC_SEND_ACCOUNT, cfg.sendAccount())
        attempt(cfg.SVC_ACCOUNT, cfg.ACC_UNKNOWN_04, cfg.accountStep04())
    }

    /**
     * Answer whatever the band asks while a session is open.
     *
     * Two rules, both learned expensively on the real device:
     *  * **Never answer a frame carrying the result tag** — that is the band acknowledging us, and
     *    replying makes both sides ping-pong without end.
     *  * **Never answer `0x3D` WearStatus** — it is a notification, and replying makes the band
     *    resend it immediately, thousands of times.
     *
     * @return how many requests were served.
     */
    suspend fun serve(timeoutMs: Long): Int = pump(timeoutMs).answered

    /** What one [pump] saw. [received] counts everything, including what we deliberately ignore. */
    data class ServeResult(val received: Int, val answered: Int)

    /**
     * One round of serving.
     *
     * [ServeResult.received] exists because silence is what tells a caller the band has finished
     * with it, and several of the band's messages are deliberately left unanswered — counting only
     * replies would read an ongoing conversation as silence.
     */
    suspend fun pump(timeoutMs: Long): ServeResult {
        var received = 0
        var served = 0
        for (frame in session.poll(timeoutMs)) {
            received++
            if (frame.isAck) continue
            val tlvs = session.decrypt(frame)
            when {
                frame.serviceId == HuaweiCommands.SVC_DEVICE_CONFIG &&
                    frame.commandId == HuaweiCommands.CMD_PHONE_INFO -> {
                    val tags = tlvs.map { it.tag }
                    session.send(
                        frame.serviceId, frame.commandId, HuaweiCommands.phoneInfoReply(tags),
                    )
                    served++
                }

                frame.serviceId == HuaweiCommands.SVC_DEVICE_CONFIG &&
                    frame.commandId == HuaweiCommands.CMD_PERMISSION_CHECK -> {
                    val perm = tlvs.firstOrNull { it.tag == 1 }?.value ?: byteArrayOf(0, 0)
                    session.send(
                        frame.serviceId, frame.commandId,
                        HuaweiProtocol.tlv(1, perm) + HuaweiProtocol.tlv(2, byteArrayOf(0, 1)),
                    )
                    served++
                }

                frame.serviceId == HuaweiCommands.SVC_ACCOUNT &&
                    frame.commandId in intArrayOf(
                        HuaweiCommands.ACC_UNKNOWN_03,
                        HuaweiCommands.ACC_UNKNOWN_04,
                        HuaweiCommands.ACC_UNKNOWN_06,
                    ) -> {
                    session.send(frame.serviceId, frame.commandId, HuaweiCommands.ok())
                    served++
                }

                // WearStatus and everything else: logged by the caller, deliberately unanswered.
                else -> Unit
            }
        }
        return ServeResult(received, served)
    }
}
