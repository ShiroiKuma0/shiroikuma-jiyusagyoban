package com.opentasker.core.huawei

/**
 * Huawei Health's reconnect, replayed for the Watch D3.
 *
 * The watch acknowledges our dictionary queries and answers none of them, while it answers Health's
 * identical bytes at once (2026-10-18). The queries are not the difference, so the CONNECTION must be:
 * Health sends some thirty commands on every reconnect that our sync never did. This is that list, in
 * Health's order and with Health's payloads, from its reconnect at 2026-10-17 19:48:25 (after the
 * firmware restart — so an ordinary reconnect, not a first pairing). Decrypted from the btsnoop;
 * docs/huawei-watch-d3.md §3.7.
 *
 * Left out on purpose:
 *  * `0x0C/0x01` — the language. Connecting never changes it; 白い熊 sets it by hand.
 *  * `0x07/0x02` — the user profile. Personal values; not ours to put in the code.
 *  * `0x2C/0x01`, `0x07/0x03`, `0x01/0x07`, `0x01/0x08` — the sync asks for these itself.
 * Built rather than copied: the capabilities (0x01/0x02, 0x03, 0x37 — the sync's own reader), the
 * device status (`0x01/0x3E`, with the watch's own name), the account and country (`0x1A`, the ID
 * from [HuaweiSettings.accountId]) and the feature file (`0x28`, [FEATURE_FILE] from the assets).
 */
object HuaweiD3Reconnect {
    data class Step(val service: Int, val command: Int, val hex: String)

    /** Markers for the steps built at run time. */
    val SETUP = Step(0x01, 0x3E, "")
    val COUNTRY = Step(0x1A, 0x0A, "")
    val FEATURE = Step(0x28, 0x02, "")
    val ACCOUNT = Step(0x1A, 0x01, "")

    val STEPS: List<Step> = listOf(
        Step(0x01, 0x31, "010002000300040005000600"),  // asks six device values
        Step(0x37, 0x02, "012468772e756e6974656465766963652e70686f6e656c6f636b73637265656e737461747573022268772e77617463682e6473742e70686f6e656c6f636b73637265656e73746174757303042fb08ebb04010005217b2273637265656e537461747573223a312c226c6f636b537461747573223a307d"),  // phone lock-screen status (screen on, unlocked)
        SETUP,
        Step(0x01, 0x3D, "0100"),  // wear status
        Step(0x01, 0x3F, "0107fdf773fa29bf3b"),  // 7 bytes, meaning unknown
        Step(0x01, 0x35, "010101"),  // 01
        Step(0x30, 0x03, "0100"),  // asks; the watch answers 00003800
        Step(0x17, 0x17, "010400000003"),  // workout service, 3
        Step(0x07, 0x23, "010400000001"),  // fitness, 1
        Step(0x07, 0x29, "01016e02013c060101030119040158050101070128080128"),  // fitness: 110 / 60 / 25 / 88 / 40 / 40 — Health's values, the watch already holds them
        Step(0x0F, 0x09, "010103"),  // weather, 3
        COUNTRY,
        Step(0x37, 0x01, "011d68772e756e69656465766963652e676574416c61726d734e756d626572020f676574416c61726d734e756d626572830b8409050435a66faf060102"),  // getAlarmsNumber query
        Step(0x02, 0x04, "8106020100030100"),  // notification capability
        Step(0x01, 0x21, "0100"),  // asks
        Step(0x08, 0x08, "020207ea03033032360413332c34342c35382c3132382c3236322c3238320571302c312c322c34352c34362c34372c34382c34392c35302c35312c35322c35332c39332c39342c39352c3132302c3132312c3132322c3132332c3132342c3136392c3137302c3137312c3236372c3236382c3236392c3237332c3237342c3237352c3237362c3237372c3237382c323739"),  // 2026 holiday calendar for CZ (day numbers)
        Step(0x01, 0x04, "8106020101030101"),  // date and time format (tag 2 date, tag 3 time) — not "command support", which is 0x01/0x03
        Step(0x20, 0x09, "010101"),  // 01
        Step(0x01, 0x1D, "0100"),  // asks
        FEATURE,
        Step(0x25, 0x0E, "010100"),  // 00
        Step(0x23, 0x10, "010100"),  // 00
        Step(0x32, 0x05, "010103"),  // 03
        Step(0x33, 0x04, "010002000300"),  // asks
        Step(0x09, 0x0C, "010100"),  // 00
        ACCOUNT,
        Step(0x02, 0x02, "0100"),  // asks
        Step(0x27, 0x01, "01000200030004000500"),  // watch-face service: asks
        Step(0x27, 0x02, "0100060103"),  // watch-face list
    )

    /**
     * Huawei's country feature file, as Health uploads it on every reconnect: which health features
     * the phone side supports in CZ — blood pressure (ABPM thresholds 135/85, 120/70, 130/80), ECG,
     * arterial stiffness, the health check, emotion, plateau care, arrhythmia, sleep apnea. No
     * personal data. Its SHA-256 is the one Health announced (`d02ff995…`).
     */
    const val FEATURE_FILE = "feature_CZ_device.txt"
    const val FEATURE_ASSET = "huawei/feature_CZ_device.txt"
    const val FEATURE_FILE_ID = 0x0F

    data class Result(val sent: Int, val answered: Int, val feature: String)

    suspend fun run(
        context: android.content.Context,
        session: HuaweiSession,
        api: HuaweiClient,
        deviceName: String,
        stepTimeoutMs: Long = 1_500,
    ): Result {
        val cfg = HuaweiCommands
        var sent = 0
        var answered = 0
        var feature = "not sent"
        runCatching { api.readCapabilities() }
        val account = HuaweiSettings.accountId(context)
        account?.let {
            sent++
            if (runCatching { session.request(cfg.SVC_ACCOUNT, cfg.ACC_EXTENDED_ACCOUNT, cfg.extendedAccountFull("CZ", "en-US", it), timeoutMs = stepTimeoutMs) }.isSuccess) answered++
        }
        for (step in STEPS) {
            when (step) {
                SETUP -> { session.send(step.service, step.command, cfg.setUpDeviceStatus(deviceName)); sent++ }
                COUNTRY -> { session.send(cfg.SVC_ACCOUNT, cfg.ACC_COUNTRY_CODE, cfg.countryCode("CZ", 0x07)); sent++ }
                ACCOUNT -> account?.let {
                    sent++
                    if (runCatching { session.request(cfg.SVC_ACCOUNT, cfg.ACC_SEND_ACCOUNT, cfg.sendAccount(it), timeoutMs = stepTimeoutMs) }.isSuccess) answered++
                }
                FEATURE -> {
                    val bytes = runCatching { context.assets.open(FEATURE_ASSET).use { it.readBytes() } }.getOrNull()
                    feature = if (bytes == null) "asset missing" else
                        HuaweiUploadClient(session).uploadFile(FEATURE_FILE, bytes, FEATURE_FILE_ID)
                }
                else -> {
                    sent++
                    val payload = HuaweiCrypto.hex(step.hex)
                    if (runCatching { session.request(step.service, step.command, payload, timeoutMs = stepTimeoutMs) }.isSuccess) answered++
                }
            }
        }
        // Whatever the watch asked meanwhile (its phone-info question above all) is answered here.
        runCatching { api.pump(1_500) }
        return Result(sent, answered, feature)
    }
}
