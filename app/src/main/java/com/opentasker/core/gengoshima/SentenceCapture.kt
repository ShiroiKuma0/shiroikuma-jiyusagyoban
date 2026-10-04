package com.opentasker.core.gengoshima

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.opentasker.core.contexts.DeviceStateEvents
import com.opentasker.core.input.ShizukuKeyEventListener
import com.opentasker.core.logging.AppLogger
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

/**
 * 言語島's walk capture: sentences spoken outside with the screen off, one WAV per sentence, for kxkb
 * to transcribe and 白い熊 to review at home (docs/sister-app-contract-kxkb-gengoshima.md).
 *
 * Driven by 物理鍵: vol-down triple → [toggleMode], vol-down single while the mode is on → [sentence].
 * The feedback is vibration only, because nothing here may need the eyes:
 *
 * | event | pattern |
 * | --- | --- |
 * | sentence started (also on entering the mode) | one 200 ms buzz |
 * | sentence saved | two short |
 * | capture mode ended | three short |
 * | error — mic lost or refused, nothing saved | one long 800 ms |
 *
 * ## Why these recording choices
 *
 * - **16 kHz mono PCM16 WAV** is byte-for-byte kxkb's own corpus clip format, so an accepted sentence's
 *   clip moves into its corpus without re-encoding.
 * - **No processing.** `UNPROCESSED` where the device has it, else `VOICE_RECOGNITION` (no AGC by
 *   definition). kxkb peak-normalises at decode time and its confidence threshold is calibrated on that.
 * - **28 s per sentence.** Whisper hears 30 s; a longer clip would silently lose its tail. At the cap
 *   the sentence is saved and the next one starts at once — a forgotten press costs no speech.
 * - **Under 0.2 s is a stray press** and is dropped (with the error buzz, so the finger learns).
 * - **Streamed to disk** as it records (`<uuid>.wav.part`, renamed on save), so an EMUI kill loses at
 *   most the sentence in progress; a leftover `.part` is discarded on the next start.
 *
 * **The CPU is held awake for the whole mode** (a partial wakelock, capped at [MODE_WAKE_CAP_MS]). With
 * the screen off and no recording running, the phone deep-sleeps between sentences, and the key
 * grabber's single tap is only reported after its 300 ms "is a second tap coming?" window — a `poll()`
 * timeout that does not run while the CPU is suspended. So the press that STARTS a sentence sat
 * unreported until the next press woke the phone, and then both fired at once: start + save, half a
 * second of silence (白い熊, 2026-10-04, sentences 2, 3 and 5 of the first walk). A press that SAVED
 * always worked, because AudioRecord keeps the CPU awake while it records.
 *
 * State lives here, not in a task: the presses arrive as separate task runs. It publishes
 * `gengoshima_capture=true|false` for profile STATE contexts, and while the mode is on it tells the key
 * grabber to swallow screen-off single taps so they mark sentences instead of changing the volume.
 */
object SentenceCapture {
    private const val TAG = "OpenTasker"
    const val DIR = "gengoshima_capture"
    const val STATE_KEY = "gengoshima_capture"

    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16
    const val HEADER_BYTES = 44
    private const val BYTES_PER_SEC = SAMPLE_RATE * CHANNELS * BITS / 8
    const val MAX_MS = 28_000L
    const val MIN_MS = 200L
    private const val MAX_DATA_BYTES = (MAX_MS * BYTES_PER_SEC / 1000).toInt()
    private const val MIN_DATA_BYTES = (MIN_MS * BYTES_PER_SEC / 1000).toInt()

    private val BUZZ_START = longArrayOf(0, 200)
    private val BUZZ_SAVED = longArrayOf(0, 80, 120, 80)
    private val BUZZ_MODE_END = longArrayOf(0, 80, 120, 80, 120, 80)
    private val BUZZ_ERROR = longArrayOf(0, 800)

    @Volatile var modeOn = false
        private set

    /** Longest a forgotten capture mode may keep the CPU awake. */
    private const val MODE_WAKE_CAP_MS = 3 * 60 * 60 * 1000L
    private var wake: PowerManager.WakeLock? = null

    private var recording: Recording? = null

    private class Recording(
        val uuid: String,
        val capturedAt: Long,
        val part: File,
        val record: AudioRecord,
        val thread: Thread,
    ) {
        @Volatile var stop = false
        @Volatile var failed = false
        @Volatile var dataBytes = 0
    }

    fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** Vol-down triple: enter the mode and start sentence 1, or save what is recording and leave. */
    @Synchronized
    fun toggleMode(ctx: Context): String {
        val app = ctx.applicationContext
        return if (modeOn) {
            val saved = finish(app, keep = true)
            setMode(false, app)
            buzz(app, BUZZ_MODE_END)
            ClipOffer.offerAsync(app)
            "capture mode off" + (saved?.let { ", saved $it" } ?: "")
        } else {
            discardStale(app)
            setMode(true, app)
            if (begin(app)) "capture mode on, recording" else "capture mode on, mic refused"
        }
    }

    /** Vol-down single in the mode: save the sentence being recorded, or start the next one. */
    @Synchronized
    fun sentence(ctx: Context): String {
        val app = ctx.applicationContext
        if (!modeOn) return "capture mode is off"
        return if (recording != null) {
            finish(app, keep = true)?.let { "saved $it" } ?: "nothing saved"
        } else {
            if (begin(app)) "recording" else "mic refused"
        }
    }

    private fun setMode(on: Boolean, app: Context) {
        modeOn = on
        if (on) {
            if (wake?.isHeld != true) {
                wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenTasker:gengoshima-capture")
                    .apply { setReferenceCounted(false); acquire(MODE_WAKE_CAP_MS) }
            }
        } else {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
        DeviceStateEvents.publishGengoshimaCapture(on)
        ShizukuKeyEventListener.setConsumeShort(on)
    }

    @SuppressLint("MissingPermission") // checked just below
    private fun begin(app: Context): Boolean {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLogger.warn(TAG, "言語島 capture: RECORD_AUDIO not granted")
            buzz(app, BUZZ_ERROR)
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, BYTES_PER_SEC / 5)
        val record = runCatching {
            AudioRecord(source(app), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes * 2)
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
        if (record == null) {
            AppLogger.warn(TAG, "言語島 capture: AudioRecord did not initialise")
            buzz(app, BUZZ_ERROR)
            return false
        }
        val uuid = UUID.randomUUID().toString()
        val part = File(dir(app), "$uuid.wav.part")
        lateinit var rec: Recording
        val thread = Thread({ pump(app, rec, bufBytes) }, "gengoshima-capture")
        rec = Recording(uuid, System.currentTimeMillis(), part, record, thread)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("not recording")
        } catch (e: Exception) {
            AppLogger.warn(TAG, "言語島 capture: startRecording failed: ${e.message}")
            runCatching { record.release() }
            buzz(app, BUZZ_ERROR)
            return false
        }
        recording = rec
        thread.start()
        buzz(app, BUZZ_START)
        AppLogger.info(TAG, "言語島 capture: sentence $uuid started")
        return true
    }

    /** The recording thread: stream PCM into the `.part` file until stopped, failed or at the cap. */
    private fun pump(app: Context, rec: Recording, bufBytes: Int) {
        val buf = ByteArray(bufBytes)
        try {
            RandomAccessFile(rec.part, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(HEADER_BYTES)) // patched on save
                while (!rec.stop) {
                    val n = rec.record.read(buf, 0, buf.size)
                    if (n < 0) { rec.failed = true; break }
                    if (n == 0) continue
                    val take = minOf(n, MAX_DATA_BYTES - rec.dataBytes)
                    out.write(buf, 0, take)
                    rec.dataBytes += take
                    if (rec.dataBytes >= MAX_DATA_BYTES) {
                        // The 28 s cap: save this one and open the next, off this thread.
                        Thread({ splitAtCap(app, rec) }, "gengoshima-capture-split").start()
                        break
                    }
                }
                writeHeader(out, rec.dataBytes)
                out.fd.sync()
            }
        } catch (e: Exception) {
            AppLogger.warn(TAG, "言語島 capture: write failed: ${e.message}")
            rec.failed = true
        }
        if (rec.failed) Thread({ failed(app, rec) }, "gengoshima-capture-fail").start()
    }

    @Synchronized
    private fun splitAtCap(app: Context, rec: Recording) {
        if (recording !== rec) return
        finish(app, keep = true)
        if (modeOn) begin(app)
    }

    @Synchronized
    private fun failed(app: Context, rec: Recording) {
        if (recording !== rec) return
        AppLogger.warn(TAG, "言語島 capture: mic lost, sentence ${rec.uuid} dropped")
        recording = null
        runCatching { rec.record.stop() }
        runCatching { rec.record.release() }
        rec.part.delete()
        buzz(app, BUZZ_ERROR)
    }

    /**
     * Stop the current sentence. [keep] saves it (two buzzes) unless it is a stray press; returns the
     * saved uuid or null. Must be called with the lock held.
     */
    private fun finish(app: Context, keep: Boolean): String? {
        val rec = recording ?: return null
        recording = null
        rec.stop = true
        runCatching { rec.record.stop() }
        if (Thread.currentThread() !== rec.thread) rec.thread.join(2_000)
        runCatching { rec.record.release() }
        if (rec.failed) { rec.part.delete(); return null }
        if (!keep || rec.dataBytes < MIN_DATA_BYTES) {
            rec.part.delete()
            if (keep) { AppLogger.info(TAG, "言語島 capture: stray press (${rec.dataBytes} B) dropped"); buzz(app, BUZZ_ERROR) }
            return null
        }
        val wav = File(rec.part.parentFile, "${rec.uuid}.wav")
        if (!rec.part.renameTo(wav)) { rec.part.delete(); buzz(app, BUZZ_ERROR); return null }
        File(rec.part.parentFile, "${rec.uuid}.json").writeText(
            JSONObject()
                .put("uuid", rec.uuid)
                .put("capturedAt", rec.capturedAt)
                .put("durationMs", rec.dataBytes * 1000L / BYTES_PER_SEC)
                .put("language", "en")
                .toString(),
        )
        buzz(app, BUZZ_SAVED)
        AppLogger.info(TAG, "言語島 capture: sentence ${rec.uuid} saved (${rec.dataBytes * 1000L / BYTES_PER_SEC} ms)")
        return rec.uuid
    }

    /** A `.part` left by a killed process is half a sentence with no header — never offered. */
    private fun discardStale(app: Context) {
        dir(app).listFiles { f -> f.name.endsWith(".wav.part") }?.forEach { it.delete() }
    }

    /** Clips saved and not yet taken by kxkb, oldest first. */
    fun pending(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.lastModified() }.orEmpty()

    private fun source(app: Context): Int {
        val unprocessed = Build.VERSION.SDK_INT >= 24 &&
            (app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    /** The canonical 44-byte RIFF header kxkb's corpus clips carry. */
    internal fun header(dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(HEADER_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE).putInt(BYTES_PER_SEC).putShort((CHANNELS * BITS / 8).toShort()).putShort(BITS.toShort())
        b.put("data".toByteArray()).putInt(dataBytes)
        return b.array()
    }

    private fun writeHeader(out: RandomAccessFile, dataBytes: Int) {
        out.seek(0)
        out.write(header(dataBytes))
    }

    private fun buzz(app: Context, pattern: LongArray) {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
