package com.opentasker.core.band

/**
 * The paging state machine for one stream. Pure, so the whole of the band's flow control can be
 * tested without a device or an Android runtime.
 *
 * Paging is FRAME-counted, not byte-counted: after every 50 received frames the band expects a
 * CONTINUE, and the stream ends when a frame's last byte is 0xFF.
 */

/** Why a stream stopped. */
enum class BandStreamEnd { TERMINATOR, IDLE_TIMEOUT, FRAME_CAP, ERROR }

/** What the caller should do after feeding a frame. */
sealed interface BandStreamStep {
    /** Keep listening. */
    data object Await : BandStreamStep

    /** Send this, then keep listening. */
    data class SendContinue(val command: BandCommand) : BandStreamStep

    /** The stream is over. */
    data class Done(val reason: BandStreamEnd) : BandStreamStep
}

/**
 * Feed every notification frame to [onFrame] in arrival order.
 *
 * Records are sliced BEFORE the terminator is checked, deliberately. Each record is validated by its
 * own BCD date, so this is safe either way: if a terminator frame ever does carry real records they
 * are kept, and if it is a pure sentinel every slice fails validation and nothing is invented.
 */
class BandStreamMachine(
    private val stream: BandStream,
    private val framesPerPage: Int = FRAMES_PER_PAGE,
    private val frameCap: Int = FRAME_CAP,
) {
    var frames: Int = 0
        private set
    var pages: Int = 0
        private set

    /**
     * Longest and shortest notification seen, in bytes — including the terminating one.
     *
     * Everything downstream assumes one notification is one frame, and frame-counted paging is
     * meaningless if that ever stops being true. Recording the extremes is what lets the census say
     * so instead of the stream quietly mis-paging: a max above the granted payload means
     * fragmentation, and a max near 20 means the MTU request failed and frames are truncated.
     */
    var maxFrameBytes: Int = 0
        private set
    var minFrameBytes: Int = 0
        private set

    private val collected = mutableListOf<BandParsedFrame>()

    /** Everything parsed so far, flattened. */
    fun parsed(): BandParsedFrame =
        collected.fold(BandParsedFrame()) { acc, f -> acc + f }

    fun onFrame(frame: ByteArray): BandStreamStep {
        maxFrameBytes = maxOf(maxFrameBytes, frame.size)
        minFrameBytes = if (minFrameBytes == 0) frame.size else minOf(minFrameBytes, frame.size)
        val parsedFrame = BandRecords.parse(stream, frame)
        if (parsedFrame.recordCount > 0) collected += parsedFrame

        if (BandProtocol.isTerminator(frame)) return BandStreamStep.Done(BandStreamEnd.TERMINATOR)

        frames++
        if (frames >= frameCap) return BandStreamStep.Done(BandStreamEnd.FRAME_CAP)
        if (frames % framesPerPage == 0) {
            pages++
            return BandStreamStep.SendContinue(BandCommand.cont(stream))
        }
        return BandStreamStep.Await
    }

    companion object {
        /** The band expects a CONTINUE after every 50 frames. */
        const val FRAMES_PER_PAGE = 50

        /** Runaway guard: a stream that never terminates must not run forever. */
        const val FRAME_CAP = 4000
    }
}
