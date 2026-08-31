package com.opentasker.core.band

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One advertising device, as plain data — no `android.*` anywhere in this file.
 *
 * `BandGattClient` maps Android's `ScanResult` onto this and nothing else; every decision about what
 * a sighting *means* is made here, in Kotlin a JVM test can run. That split is the same one the rest
 * of `core/band` already lives by, and `BandSafetyGuardTest` enforces it.
 */
data class BandScanDevice(
    val address: String,
    /** From the advertisement itself, never `BluetoothDevice.name` — that one needs BLUETOOTH_CONNECT. */
    val name: String = "",
    /** Strongest sighting of this device in the window; advertisements vary by several dB. */
    val rssi: Int = 0,
    /** Lowercase 16-bit shorthand where the UUID is a Bluetooth SIG base one, e.g. `fff0`. */
    val serviceUuids: List<String> = emptyList(),
    val manufacturerIds: List<Int> = emptyList(),
    val manufacturerHex: String = "",
    val txPower: Int? = null,
    val connectable: Boolean? = null,
    val sightings: Int = 1,
)

/**
 * How sure we are that a device is a Hume band.
 *
 * Only [CONFIRMED] is a fact: it means something answered on `fff0`/`fff6`/`fff7`, which is the
 * signature this app actually talks to. The rest are readings of an advertisement, and an
 * advertisement can say whatever the firmware feels like saying.
 */
enum class BandVerdict {
    /** Connected, and the band's service and both characteristics were there. */
    CONFIRMED,

    /** Advertises `fff0`, or names itself like a band. Worth trying. */
    LIKELY,

    /** One weak sign only — usually just a plausible name. */
    POSSIBLE,

    /** Nothing suggests a band. Listed for completeness, never proposed. */
    UNLIKELY,
}

/** A device plus what we concluded about it and, crucially, *why*. */
data class BandScanCandidate(
    val device: BandScanDevice,
    val verdict: BandVerdict,
    val score: Int,
    /** Human-readable evidence, already in the report's language. Order is the order shown. */
    val reasons: List<String> = emptyList(),
    /** True when this address is the one 健康の設定 is currently configured with. */
    val isConfigured: Boolean = false,
    /** Set once a probe has run: null = not probed, otherwise the probe's own words. */
    val probeNote: String? = null,
)

/**
 * Ranking and prose for a scan.
 *
 * The report is deliberately evidence-first rather than verdict-first. A new band's MAC is going to
 * be typed into `健康の設定 -- [727][01]` and then trusted by every sync afterwards, so the one thing
 * this must never do is present a guess with the confidence of a measurement.
 */
object BandScanReport {

    /** The band's own service, as it appears in an advertisement. */
    const val BAND_SERVICE_SHORT = "fff0"

    /**
     * The band's own advertised name, measured rather than assumed.
     *
     * 白い熊's band advertises `Hume Band V2 A13A` (2026-08-11), and those four hex digits are the
     * last two octets of its own MAC — `D5:A7:06:DC:`**`A1`**`:`**`3A`**. So the prefix is stable
     * across units while the suffix identifies the unit, which is exactly what a replacement band
     * will need: a new one announces itself with the same nine characters and four different ones.
     *
     * This earns [BandVerdict.LIKELY] on its own, because unlike the generic hints below it is a
     * measurement of this model. It is still not a conclusion — only a probe is.
     */
    const val BAND_NAME_PREFIX = "hume band"

    /**
     * Advertised names worth a second look, in descending order of how much they mean.
     *
     * Everything after [BAND_NAME_PREFIX] is a net rather than a key: half the wristbands on earth
     * answer to "band" or "fit". Matching one earns [BandVerdict.POSSIBLE] and a probe, never a
     * verdict.
     */
    val NAME_HINTS: List<String> = listOf("hume", "band", "ring", "watch", "fit", "smart", "hr", "健康")

    private const val SCORE_SERVICE = 4

    /** Enough on its own to reach LIKELY: it is this model's measured advertised name. */
    private const val SCORE_BAND_NAME = 4
    private const val SCORE_NAME = 2
    private const val SCORE_CONFIGURED = 3
    private const val SCORE_CONNECTABLE = 1

    /** Reference RSSI at one metre for a small BLE peripheral, and the indoor path-loss exponent. */
    private const val RSSI_AT_ONE_METRE = -59.0
    private const val PATH_LOSS_EXPONENT = 2.5

    fun rank(
        devices: List<BandScanDevice>,
        configuredAddress: String,
        lang: String,
    ): List<BandScanCandidate> {
        val ja = isJapanese(lang)
        val configured = configuredAddress.trim().uppercase()
        return devices
            .map { device -> score(device, configured, ja) }
            .sortedWith(
                compareByDescending<BandScanCandidate> { it.score }
                    .thenByDescending { it.device.rssi }
                    .thenBy { it.device.address },
            )
    }

    private fun score(device: BandScanDevice, configured: String, ja: Boolean): BandScanCandidate {
        val reasons = mutableListOf<String>()
        var score = 0

        if (device.serviceUuids.any { it.equals(BAND_SERVICE_SHORT, ignoreCase = true) }) {
            score += SCORE_SERVICE
            reasons += if (ja) "バンドの fff0 サービスを広告している" else "advertises the band's fff0 service"
        }
        if (device.name.contains(BAND_NAME_PREFIX, ignoreCase = true)) {
            score += SCORE_BAND_NAME
            reasons += if (ja) {
                "名前が「${device.name}」— この機種の広告名"
            } else {
                "named \"${device.name}\" — this model's advertised name"
            }
        } else {
            val hint = NAME_HINTS.firstOrNull { device.name.contains(it, ignoreCase = true) }
            if (hint != null) {
                score += SCORE_NAME
                reasons += if (ja) "名前に「$hint」が入っている" else "its name contains \"$hint\""
            }
        }
        val isConfigured = device.address.equals(configured, ignoreCase = true)
        if (isConfigured) {
            score += SCORE_CONFIGURED
            reasons += if (ja) {
                "健康の設定 が今使っているアドレスと同じ"
            } else {
                "same address 健康の設定 is configured with"
            }
        }
        if (device.connectable == true) score += SCORE_CONNECTABLE

        val verdict = when {
            score >= SCORE_SERVICE -> BandVerdict.LIKELY
            score >= SCORE_NAME -> BandVerdict.POSSIBLE
            else -> BandVerdict.UNLIKELY
        }
        return BandScanCandidate(
            device = device,
            verdict = verdict,
            score = score,
            reasons = reasons,
            isConfigured = isConfigured,
        )
    }

    /**
     * Which devices are worth spending a connection on, best first.
     *
     * A probe costs seconds and wakes someone else's radio, so [BandVerdict.UNLIKELY] is never
     * probed however empty the room is. Finding nothing is a legitimate answer.
     */
    fun probeOrder(candidates: List<BandScanCandidate>, limit: Int): List<BandScanCandidate> =
        candidates.filter { it.verdict != BandVerdict.UNLIKELY }.take(limit.coerceAtLeast(0))

    /** Fold a finished probe back in, promoting to [BandVerdict.CONFIRMED] or demoting on refusal. */
    fun applyProbe(
        candidate: BandScanCandidate,
        confirmed: Boolean,
        note: String,
    ): BandScanCandidate = candidate.copy(
        verdict = if (confirmed) BandVerdict.CONFIRMED else candidate.verdict,
        score = if (confirmed) candidate.score + 10 else candidate.score,
        probeNote = note,
    )

    /** The address to hand back: the best CONFIRMED one, else the best LIKELY, else blank. */
    fun bestAddress(candidates: List<BandScanCandidate>): String {
        val ordered = candidates.sortedWith(
            compareByDescending<BandScanCandidate> { it.verdict == BandVerdict.CONFIRMED }
                .thenByDescending { it.score }
                .thenByDescending { it.device.rssi },
        )
        val best = ordered.firstOrNull() ?: return ""
        return if (best.verdict == BandVerdict.CONFIRMED || best.verdict == BandVerdict.LIKELY) {
            best.device.address
        } else {
            ""
        }
    }

    /**
     * Rough free-space distance from RSSI, in metres.
     *
     * Presented as "about", and it means it: a hand over the band, a pocket, or a body between the
     * two radios moves this by a factor of several. It is here to answer "which of these two is the
     * one on the table in front of me", which it does well, and nothing finer.
     */
    fun approximateMetres(rssi: Int): Double? {
        if (rssi == 0 || rssi < -120) return null
        val metres = 10.0.pow((RSSI_AT_ONE_METRE - rssi) / (10.0 * PATH_LOSS_EXPONENT))
        return if (metres.isFinite() && metres < 200) metres else null
    }

    fun isJapanese(lang: String): Boolean = lang.trim().lowercase().startsWith("ja")

    /** The spinner the live window turns, driven by the scan's own ticks. */
    val SPINNER_FRAMES: List<String> = listOf("◐", "◓", "◑", "◒")

    fun spinnerFrame(tick: Int): String = SPINNER_FRAMES[((tick % SPINNER_FRAMES.size) + SPINNER_FRAMES.size) % SPINNER_FRAMES.size]

    /**
     * One device, one line, for the window that fills up while the scan runs.
     *
     * Deliberately short: this is read at a glance by someone waiting, and a scene element has a
     * fixed height with no scrollbar — a verbose line would push the band off the bottom. The full
     * detail is what [describe] is for, and it replaces this list the moment the scan finishes.
     */
    fun liveLine(candidate: BandScanCandidate, ja: Boolean): String {
        val d = candidate.device
        val mark = when (candidate.verdict) {
            BandVerdict.CONFIRMED -> "◆"
            BandVerdict.LIKELY -> "◇"
            BandVerdict.POSSIBLE -> "·"
            BandVerdict.UNLIKELY -> " "
        }
        val name = d.name.ifBlank { if (ja) "（名前なし）" else "(no name)" }
        val metres = approximateMetres(d.rssi)?.let { " ≈${oneDecimalPublic(it)}m" }.orEmpty()
        val here = if (candidate.isConfigured) (if (ja) " ←現設定" else " ←configured") else ""
        return "$mark ${d.address}$here\n    $name · ${d.rssi}dBm$metres"
    }

    /**
     * The live list, newest evidence first.
     *
     * Ranked rather than in arrival order: the point of the window is to answer "is my band here",
     * and a band that turns up eighth should not be eighth on screen. [limit] keeps it inside the
     * element — a room with thirty beacons in it must not push the answer out of view.
     */
    fun liveList(candidates: List<BandScanCandidate>, ja: Boolean, limit: Int = LIVE_LIST_LIMIT): String {
        if (candidates.isEmpty()) return if (ja) "（まだ何も聴こえません）" else "(nothing heard yet)"
        val shown = candidates.take(limit).joinToString("\n") { liveLine(it, ja) }
        val hidden = candidates.size - minOf(candidates.size, limit)
        if (hidden <= 0) return shown
        return shown + "\n" + (if (ja) "… ほか $hidden 台" else "… and $hidden more")
    }

    /** As many as fit the scene element without pushing the answer off the bottom. */
    const val LIVE_LIST_LIMIT = 6

    fun oneDecimalPublic(value: Double): String = oneDecimal(value)

    /**
     * The whole dialog body.
     *
     * Written as plain text rather than HTML because it lands in `dialog.text`, gets copied to the
     * clipboard, and may well be pasted into a note — markup would survive all three and help none.
     */
    fun describe(
        candidates: List<BandScanCandidate>,
        seconds: Int,
        lang: String,
        showAll: Boolean,
        probed: Int,
    ): String {
        val ja = isJapanese(lang)
        val out = StringBuilder()

        val found = candidates.filter { it.verdict == BandVerdict.CONFIRMED }
        val likely = candidates.filter { it.verdict == BandVerdict.LIKELY }
        val possible = candidates.filter { it.verdict == BandVerdict.POSSIBLE }
        val rest = candidates.filter { it.verdict == BandVerdict.UNLIKELY }

        out.append(
            if (ja) {
                "バンド探索 — ${seconds}秒間、${candidates.size} 台を受信" +
                    (if (probed > 0) "、うち $probed 台に接続確認" else "")
            } else {
                "Band scan — ${seconds}s, ${candidates.size} device(s) heard" +
                    (if (probed > 0) ", $probed probed" else "")
            },
        )
        out.append("\n")

        if (candidates.isEmpty()) {
            out.append("\n")
            out.append(
                if (ja) {
                    "何も受信できませんでした。\n" +
                        "・バンドが充電器の上にあると広告を止めることがあります。手首に着けて、もう一度。\n" +
                        "・Hume 純正アプリが接続中だと、バンドは広告を出しません。切ってから試してください。\n" +
                        "・Bluetooth と「近くのデバイス」の権限が必要です。"
                } else {
                    "Nothing was heard.\n" +
                        "- A band on its charger often stops advertising. Put it on and scan again.\n" +
                        "- A band already connected to Hume's own app will not advertise. Disconnect there first.\n" +
                        "- Bluetooth and the Nearby-devices permission both have to be on."
                },
            )
            return out.toString()
        }

        if (found.isNotEmpty()) {
            out.append("\n")
            out.append(if (ja) "■ バンドを確認しました\n" else "■ Band confirmed\n")
            found.forEach { out.append(describeOne(it, ja, detailed = true)) }
        }
        if (likely.isNotEmpty()) {
            out.append("\n")
            out.append(
                if (ja) {
                    if (found.isEmpty()) "■ バンドらしい装置\n" else "■ ほかにバンドらしい装置\n"
                } else {
                    if (found.isEmpty()) "■ Looks like a band\n" else "■ Also band-like\n"
                },
            )
            likely.forEach { out.append(describeOne(it, ja, detailed = true)) }
        }
        if (possible.isNotEmpty()) {
            out.append("\n")
            out.append(if (ja) "■ 可能性のある装置\n" else "■ Possible\n")
            possible.forEach { out.append(describeOne(it, ja, detailed = true)) }
        }
        if (rest.isNotEmpty()) {
            out.append("\n")
            if (showAll) {
                out.append(
                    if (ja) "■ 近くのその他の装置（${rest.size} 台）\n" else "■ Other devices nearby (${rest.size})\n",
                )
                rest.forEach { out.append(describeOne(it, ja, detailed = false)) }
            } else {
                out.append(
                    if (ja) {
                        "■ ほかに ${rest.size} 台ありましたが、バンドらしくないため省略しました。\n"
                    } else {
                        "■ ${rest.size} other device(s) heard, none band-like — omitted.\n"
                    },
                )
            }
        }

        if (found.isEmpty() && likely.isEmpty()) {
            out.append("\n")
            out.append(
                if (ja) {
                    "確実なものは見つかりませんでした。バンドを手首に着け、Hume 純正アプリを閉じて、もう一度お試しください。"
                } else {
                    "Nothing conclusive. Put the band on, close Hume's own app, and scan again."
                },
            )
        }
        return out.toString().trimEnd()
    }

    private fun describeOne(candidate: BandScanCandidate, ja: Boolean, detailed: Boolean): String {
        val d = candidate.device
        val out = StringBuilder()
        val marker = if (candidate.isConfigured) " ←" + (if (ja) " 現在の設定" else " configured now") else ""
        out.append("\n  ").append(d.address).append(marker).append("\n")

        val name = d.name.ifBlank { if (ja) "（名前なし）" else "(no name)" }
        val metres = approximateMetres(d.rssi)
        val distance = metres?.let {
            if (ja) "約 ${oneDecimal(it)} m" else "about ${oneDecimal(it)} m"
        }
        val signal = buildString {
            append(if (ja) "電波 " else "signal ")
            append(d.rssi).append(" dBm")
            if (distance != null) append(" / ").append(distance)
        }
        out.append("    ").append(name).append(" · ").append(signal).append("\n")

        if (!detailed) return out.toString()

        if (d.serviceUuids.isNotEmpty()) {
            out.append("    ")
                .append(if (ja) "サービス: " else "services: ")
                .append(d.serviceUuids.joinToString(", "))
                .append("\n")
        }
        if (d.manufacturerIds.isNotEmpty()) {
            out.append("    ")
                .append(if (ja) "製造者 ID: " else "manufacturer id: ")
                .append(d.manufacturerIds.joinToString(", ") { "0x%04X".format(it) })
            if (d.manufacturerHex.isNotBlank()) out.append(" · ").append(d.manufacturerHex)
            out.append("\n")
        }
        d.txPower?.let {
            out.append("    ").append(if (ja) "送信出力: " else "tx power: ").append(it).append(" dBm\n")
        }
        d.connectable?.let {
            val word = if (ja) {
                if (it) "接続可" else "接続不可（ビーコン）"
            } else {
                if (it) "connectable" else "not connectable (beacon)"
            }
            out.append("    ").append(word)
            out.append(" · ").append(if (ja) "受信 ${d.sightings} 回" else "${d.sightings} advertisement(s)")
            out.append("\n")
        }
        candidate.probeNote?.let {
            out.append("    ").append(if (ja) "接続確認: " else "probe: ").append(it).append("\n")
        }
        if (candidate.reasons.isNotEmpty()) {
            out.append("    ")
                .append(if (ja) "根拠: " else "why: ")
                .append(candidate.reasons.joinToString(if (ja) " / " else "; "))
                .append("\n")
        }
        return out.toString()
    }

    /** Bytes as lowercase hex, bounded — an advertisement can carry 31 bytes and a dialog cannot. */
    fun hex(bytes: ByteArray, maxBytes: Int = 12): String {
        val shown = bytes.take(maxBytes)
        val body = shown.joinToString("") { "%02x".format(it) }
        return if (bytes.size > maxBytes) "$body… (${bytes.size} B)" else body
    }

    /**
     * `0000fff0-0000-1000-8000-00805f9b34fb` → `fff0`; anything else keeps its full form.
     *
     * The short form is what the protocol document and every comment in `core/band` use, so the
     * report reads the same way the code does.
     */
    fun shortUuid(uuid: String): String {
        val lower = uuid.lowercase()
        val sigBase = "-0000-1000-8000-00805f9b34fb"
        if (!lower.endsWith(sigBase)) return lower
        val head = lower.removeSuffix(sigBase)
        if (head.length != 8) return lower
        return if (head.startsWith("0000")) head.removePrefix("0000") else head
    }

    /**
     * One decimal place, built by hand rather than by `String.format`.
     *
     * `"%.1f".format(x)` takes the default Locale, and on a Japanese or European phone that is a
     * decimal comma — inside a line the reader will compare against another number, that is a
     * distraction with no upside.
     */
    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }
}
