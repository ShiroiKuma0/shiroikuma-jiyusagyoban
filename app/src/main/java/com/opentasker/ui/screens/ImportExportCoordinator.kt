package com.opentasker.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The step a transfer is on, so a long import can say more than "working". */
enum class TransferStage {
    Preflight,
    Decode,
    Plan,
    Write,
}

data class TransferProgress(val stage: TransferStage, val fraction: Float? = null)

/**
 * Owns one import/export lane: its busy flag, its progress, and the job running it.
 *
 * Two things made these operations feel stuck. Nothing held the job, so a large Tasker or
 * MacroDroid backup could not be cancelled, and the only feedback was a relabelled button. Worse,
 * the busy flag was cleared on the last line of the coroutine, so anything that ended early left
 * the UI permanently busy. Clearing it in a `finally` is what makes cancellation safe: a cancelled
 * coroutine still runs its finally blocks.
 *
 * Cancelling never has to undo a write, because every importer decodes and reviews before the
 * database transaction; stopping mid-decode simply means no transaction was ever started.
 */
class ImportExportCoordinator(private val scope: CoroutineScope) {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    private var job: Job? = null

    /**
     * Runs [block] unless this lane is already busy. The block reports the stage it has reached so
     * the UI can show something more useful than a spinner. Returns false when the lane was busy
     * and nothing was started.
     */
    fun launch(block: suspend (report: (TransferStage) -> Unit) -> Unit): Boolean {
        if (_busy.value) return false
        _busy.value = true
        job = scope.launch {
            try {
                block { stage -> _progress.value = TransferProgress(stage) }
            } finally {
                _busy.value = false
                _progress.value = null
            }
        }
        return true
    }

    /** Stops the running transfer. The lane returns to idle through the block's finally. */
    fun cancel() {
        job?.cancel()
    }
}
