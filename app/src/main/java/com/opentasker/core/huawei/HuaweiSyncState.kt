package com.opentasker.core.huawei

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live Huawei sync progress, in the same idiom as the Hume band's `BandSyncState`.
 *
 * It deliberately does **not** reuse that type, and not only because `HuaweiSafetyGuardTest` forbids
 * `core/huawei` from importing `core/band`. The two syncs have genuinely different shapes: the Hume
 * band is read as a fixed list of BLE streams, so its progress is stream-indexed, while this band is
 * read by asking how many records a window holds and then fetching `1..N`. Pasting Hume's
 * `stream`/`streamIndex`/`streamCount` in here would make every row on screen a small lie.
 *
 * One number is genuinely better here: [percent] is real. `recordCount` is known before the fetch
 * loop starts, so the bar measures work rather than estimating it — Hume's stream-index percentage
 * is a proxy for progress, this is progress.
 */
data class HuaweiSyncProgress(
    val running: Boolean = false,
    /** starting · connecting · handshake · device · counting · reading · writing · done */
    val phase: String = "",
    val recordIndex: Int = 0,
    val recordCount: Int = 0,
    /** A run walks several bounded windows; see [HuaweiSyncArgs]. */
    val windowIndex: Int = 0,
    val windowCount: Int = 0,
    val samples: Int = 0,
    val inserted: Int = 0,
    val percent: Int = 0,
    val message: String = "",
    /** When this sync was announced, for the elapsed-seconds counter. 0 while idle. */
    val startedAtMillis: Long = 0L,
) {
    companion object {
        val IDLE = HuaweiSyncProgress()
    }
}

/**
 * A sync that failed, or lost a part of itself to a cut link — what the sync dialog restarts on.
 *
 * Two syncs on 2026-11-10 ended that way and said so only in the one grey progress line: the first
 * failed outright, the second “succeeded” with sleep, the dictionary and the ECG all lost — and last
 * night never arrived. Both were the phone's Bluetooth crashing under the sync. A part's own failure
 * is folded into the summary as `sleep: Broken pipe`, so that is where the lost parts are read back
 * from. The board's sync dialog reads it, says it in red and runs the sync again.
 */
data class HuaweiSyncTrouble(
    val atMillis: Long,
    /** The whole sync failed; false when it finished but lost [lost] on the way. */
    val failed: Boolean,
    val message: String,
    /** The parts a cut link cost a finished sync — sleep · dictionary · ecg — in sync order. */
    val lost: List<String>,
) {
    companion object {
        private const val CUT = "Broken pipe"

        /** Null when there is nothing to tell: the sync went through and no part of it was cut. */
        fun of(ok: Boolean, message: String, atMillis: Long): HuaweiSyncTrouble? {
            val lost = message.split(" · ").mapNotNull { part ->
                val colon = part.indexOf(": ")
                if (colon > 0 && part.contains(CUT, ignoreCase = true)) part.substring(0, colon).trim() else null
            }
            if (ok && lost.isEmpty()) return null
            return HuaweiSyncTrouble(atMillis, failed = !ok, message = message, lost = lost)
        }
    }
}

object HuaweiSyncState {
    private val _progress = MutableStateFlow(HuaweiSyncProgress.IDLE)
    val progress: StateFlow<HuaweiSyncProgress> = _progress.asStateFlow()

    /** The newest sync that failed or was cut, until the sync dialog has read it. */
    private val _trouble = MutableStateFlow<HuaweiSyncTrouble?>(null)
    val trouble: StateFlow<HuaweiSyncTrouble?> = _trouble.asStateFlow()

    fun dismissTrouble() {
        _trouble.value = null
    }

    /**
     * Announce a sync the instant the button is pressed, before any coroutine is dispatched.
     *
     * **This must not be optimised away**, and the reason is stronger here than for the Hume band.
     * Reaching a usable session means an RFCOMM socket connect, LinkParams, SecurityNegotiation, a
     * PIN fetch and a full HiChain pass — many seconds during which the band says nothing and the
     * screen would otherwise show nothing either. Called on the main thread from the click handler,
     * so the state is already true by the time Compose recomposes that frame.
     *
     * Returns false when a sync is already in flight, in which case the caller must not start one:
     * the state on screen belongs to that sync and this must not overwrite it.
     */
    fun arm(): Boolean {
        if (_progress.value.running) return false
        _progress.value = HuaweiSyncProgress(
            running = true,
            phase = "starting",
            startedAtMillis = System.currentTimeMillis(),
        )
        return true
    }

    fun begin(windowCount: Int) {
        // Keep the moment the button was pressed, so the counter does not jump back to zero when
        // the runner takes over from arm().
        val armedAt = _progress.value.takeIf { it.running }?.startedAtMillis ?: 0L
        _progress.value = HuaweiSyncProgress(
            running = true,
            phase = "connecting",
            windowCount = windowCount,
            startedAtMillis = if (armedAt != 0L) armedAt else System.currentTimeMillis(),
        )
    }

    fun phase(phase: String, message: String = "") {
        val current = _progress.value
        // The record counts describe the reading and nothing after it. Left in place, the last
        // window's "4/5" stood beside sleep, dictionary and ECG as if those were stuck at it.
        _progress.value = if (phase == "reading" || phase == "counting") {
            current.copy(phase = phase, message = message)
        } else {
            current.copy(phase = phase, message = message, recordIndex = 0, recordCount = 0)
        }
    }

    /** A window's records are all in — the callback reports each record BEFORE it is fetched. */
    fun windowDone() {
        val current = _progress.value
        if (current.recordCount > 0) record(current.recordCount, current.recordCount)
    }

    fun window(index: Int) {
        _progress.value = _progress.value.copy(phase = "counting", windowIndex = index)
    }

    /** Progress through one window's records. [count] is known up front, so this is not an estimate. */
    fun record(index: Int, count: Int) {
        val current = _progress.value
        val windows = current.windowCount.coerceAtLeast(1)
        val done = (current.windowIndex - 1).coerceAtLeast(0)
        val withinWindow = if (count <= 0) 0.0 else index.toDouble() / count
        _progress.value = current.copy(
            phase = "reading",
            recordIndex = index,
            recordCount = count,
            percent = (((done + withinWindow) / windows) * 100).toInt().coerceIn(0, 100),
        )
    }

    fun counted(samples: Int, inserted: Int) {
        _progress.value = _progress.value.copy(samples = samples, inserted = inserted)
    }

    fun finish(message: String, ok: Boolean) {
        _progress.value = _progress.value.copy(
            running = false,
            phase = "done",
            percent = 100,
            message = message,
        )
        // A later clean sync does not clear an unread failure: the night it lost is still missing.
        HuaweiSyncTrouble.of(ok, message, System.currentTimeMillis())?.let { _trouble.value = it }
    }

    /** Reset to idle — used when a sync is refused before it starts. */
    fun idle() {
        _progress.value = HuaweiSyncProgress.IDLE
    }
}
