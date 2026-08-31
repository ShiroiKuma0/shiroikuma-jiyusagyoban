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

object HuaweiSyncState {
    private val _progress = MutableStateFlow(HuaweiSyncProgress.IDLE)
    val progress: StateFlow<HuaweiSyncProgress> = _progress.asStateFlow()

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
        _progress.value = _progress.value.copy(phase = phase, message = message)
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

    fun finish(message: String) {
        _progress.value = _progress.value.copy(
            running = false,
            phase = "done",
            percent = 100,
            message = message,
        )
    }

    /** Reset to idle — used when a sync is refused before it starts. */
    fun idle() {
        _progress.value = HuaweiSyncProgress.IDLE
    }
}
