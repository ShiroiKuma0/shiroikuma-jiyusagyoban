package com.opentasker.core.transfer

import com.opentasker.core.logging.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown out of [SettingsBackup.export] when a cancel arrives; carries no message of its own. */
class ExportCancelledException : Exception("cancelled")

/**
 * The one state export that may be in flight, and the switch that stops it.
 *
 * The 保存復元 contract forbids two concurrent exports and requires a `CANCEL_EXPORT` action, so a run
 * needs a process-local identity: who is exporting, whether a cancel has arrived, and what half-written
 * file has to be removed on the way out.
 *
 * **Nothing here is persisted, deliberately.** A stored "export in progress" flag survives the crash
 * that stranded it, and every later request then answers `ERROR:export already running` until the app is
 * force-stopped. This lives and dies with the process, and [end] runs from a `finally`.
 */
object StateExportRun {

    private const val TAG = "StateExport"

    private val running = AtomicBoolean(false)

    @Volatile private var activeReplyId: String = ""
    @Volatile private var cancelled = false
    /** Removes the partial output of the running export. Set by the writer once it knows the target. */
    @Volatile private var discardPartial: (() -> Unit)? = null

    /** True while an export is in flight — the caller's own [end] must follow in a `finally`. */
    val isRunning: Boolean get() = running.get()

    /**
     * Claim the export slot for [replyId]. Returns false when one is already running, which the
     * contract answers with `ERROR:export already running` rather than starting a second.
     */
    fun begin(replyId: String): Boolean {
        if (!running.compareAndSet(false, true)) return false
        activeReplyId = replyId
        cancelled = false
        discardPartial = null
        return true
    }

    /** Register how to remove the half-written file, once the writer knows where it is writing. */
    fun onDiscard(block: () -> Unit) {
        discardPartial = block
    }

    /**
     * Stop the running export. [replyId] blank = whatever is running, which is unambiguous because two
     * at once are forbidden; a non-blank id that does not match is ignored, so a stale cancel cannot
     * kill a later run.
     *
     * A cancel arriving when nothing is running — or after the export already finished — is a silent
     * no-op. 自由作業盤 fires this whenever 中止 is pressed, without knowing how far the app got.
     */
    fun requestCancel(replyId: String) {
        if (!running.get()) return
        if (replyId.isNotEmpty() && replyId != activeReplyId) return
        cancelled = true
        AppLogger.info(TAG, "Cancel requested for the running state export")
    }

    /** Checked between written categories, so the export unwinds at a boundary, never mid-write. */
    fun isCancelled(): Boolean = cancelled

    /** Remove the partial output. Called on the cancel and failure paths, never on success. */
    fun discard() {
        runCatching { discardPartial?.invoke() }
            .onFailure { AppLogger.warn(TAG, "Could not remove the partial export: ${it.message}") }
    }

    /** Release the slot. Must run from a `finally` — see the class note on persisted flags. */
    fun end() {
        activeReplyId = ""
        cancelled = false
        discardPartial = null
        running.set(false)
    }
}
