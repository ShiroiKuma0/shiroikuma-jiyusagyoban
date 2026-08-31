package com.opentasker.core.band

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live sync progress, as an object holding a StateFlow — the same idiom as ThemeStore.
 *
 * Nothing in this app uses dependency injection and this does not introduce any. The 「健康」 screen
 * collects this directly; the Action mirrors the same values into the variable store as %BAND_Phase,
 * %BAND_Pct and %BAND_Records, so a Scene bound to those names animates with no polling — the
 * SpeedTestAction precedent.
 */
data class BandSyncProgress(
    val running: Boolean = false,
    val phase: String = "",
    val stream: String = "",
    val streamIndex: Int = 0,
    val streamCount: Int = 0,
    val records: Int = 0,
    val inserted: Int = 0,
    val percent: Int = 0,
    val message: String = "",
    /** When this sync was announced, for the elapsed-seconds counter. 0 while idle. */
    val startedAtMillis: Long = 0L,
) {
    companion object {
        val IDLE = BandSyncProgress()
    }
}

object BandSyncState {
    private val _progress = MutableStateFlow(BandSyncProgress.IDLE)
    val progress: StateFlow<BandSyncProgress> = _progress.asStateFlow()

    /**
     * Announce a sync the instant the button is pressed, before any coroutine is dispatched.
     *
     * Connecting takes seconds — a GATT connect, an MTU negotiation and service discovery — and until
     * [begin] runs the screen would otherwise show nothing at all. Called on the main thread from the
     * click handler, so the state is already true by the time Compose recomposes that frame.
     *
     * Returns false when a sync is already in flight, in which case the caller must not start one:
     * the state on screen belongs to that sync and this must not overwrite it.
     */
    fun arm(): Boolean {
        if (_progress.value.running) return false
        _progress.value = BandSyncProgress(
            running = true,
            phase = "starting",
            startedAtMillis = System.currentTimeMillis(),
        )
        return true
    }

    fun begin(streamCount: Int) {
        // Keep the moment the button was pressed, so the counter does not jump back to zero when the
        // engine takes over from arm().
        val armedAt = _progress.value.takeIf { it.running }?.startedAtMillis ?: 0L
        _progress.value = BandSyncProgress(
            running = true,
            phase = "connecting",
            streamCount = streamCount,
            startedAtMillis = if (armedAt != 0L) armedAt else System.currentTimeMillis(),
        )
    }

    fun phase(phase: String, message: String = "") {
        _progress.value = _progress.value.copy(phase = phase, message = message)
    }

    fun stream(stream: String, index: Int) {
        val current = _progress.value
        _progress.value = current.copy(
            phase = "reading",
            stream = stream,
            streamIndex = index,
            percent = if (current.streamCount <= 0) 0 else (index * 100 / current.streamCount),
        )
    }

    fun counted(records: Int, inserted: Int) {
        _progress.value = _progress.value.copy(records = records, inserted = inserted)
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
        _progress.value = BandSyncProgress.IDLE
    }
}
