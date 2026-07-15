package com.opentasker.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Live music pulse for the audio-reactive 音楽端灯: taps the device OUTPUT MIX via [Visualizer]
 * (audio session 0) and distills it into two small signals the meteor canvas polls per frame
 * through [Bridge] (injected as `window.OngakuPulse` into WEB scene elements):
 *  - [Bridge.level]: smoothed loudness 0..1 — fast attack, slow release
 *  - [Bridge.beat]:  a decaying 0..1 impulse fired on bass onsets (running-average spectral flux)
 *
 * Mate XT EMUI quirks (verified via music.viz.test, 2026-07-11): a fresh Visualizer(0) can arrive
 * ALREADY ENABLED (disable before resizing), and *polled* reads are throttled to ~4Hz — so this
 * uses the push [Visualizer.OnDataCaptureListener] at the device max rate (typically 20Hz).
 *
 * Nothing is recorded: the Visualizer yields transient 8-bit visualization snapshots only.
 * Ref-counted — each visible reactive WEB element [acquire]s on show and [release]s when hidden,
 * screen-off, or the %Ongaku_Reactive knob turns off; the Visualizer exists only while held.
 * Reading [Bridge] while stopped just returns zeros.
 */
object MusicPulseSource {
    private const val TAG = "OpenTasker"

    private var refs = 0
    private var viz: Visualizer? = null

    @Volatile private var levelV = 0f
    @Volatile private var beatAt = 0L        // elapsedRealtime of the last bass onset
    @Volatile private var beatPeak = 0f      // strength of that onset, 0..1
    // elapsedRealtime of the last capture frame with REAL signal (raw RMS above the noise floor).
    // Android Auto / A2DP-offload playback bypasses the output mix, so the Visualizer runs but
    // captures pure silence — this lets consumers detect that and fall back (Bridge.silentMs()).
    @Volatile private var signalAt = 0L

    // Bass running average for onset detection (listener thread only).
    private var bassAvg = 0.0
    private var bassInit = false

    // --- beat grid (tempo phase-lock; listener thread writes, Bridge reads the volatiles) ---
    // Onset-interval clustering estimates the beat period (folded into the 60–180 BPM band);
    // the anchor is a known beat time, nudged PLL-style toward on-beat onsets so beatPhase()
    // stays aligned even when individual onsets are missed.
    private val onsets = ArrayDeque<Long>()
    @Volatile private var periodMs = 0.0     // 0 = no tempo estimate yet
    @Volatile private var anchorMs = 0L      // a beat instant on the grid
    @Volatile private var tempoConfV = 0f    // 0..1 cluster agreement

    private fun updateTempo(now: Long) {
        onsets.addLast(now)
        while (onsets.size > 32 || now - onsets.first() > 10_000) onsets.removeFirst()
        if (onsets.size < 6) { tempoConfV = 0f; return }
        val t = onsets.toLongArray()
        // Successive + skip-one inter-onset intervals, each folded into 333..1000ms (180..60 BPM) —
        // folding maps half/double-time hits onto the same beat period.
        val iois = ArrayList<Double>(t.size * 2)
        for (i in 1 until t.size) {
            for (j in 1..2) {
                if (i - j < 0) continue
                var d = (t[i] - t[i - j]).toDouble()
                if (d < 80) continue
                while (d < 333) d *= 2
                while (d > 1000) d /= 2
                iois.add(d)
            }
        }
        if (iois.isEmpty()) { tempoConfV = 0f; return }
        // Modal cluster: the candidate with the most intervals within ±8% wins; period = cluster mean.
        var bestN = 0
        var bestSum = 0.0
        for (c in iois) {
            var n = 0
            var s = 0.0
            for (x in iois) if (kotlin.math.abs(x - c) < c * 0.08) { n++; s += x }
            if (n > bestN) { bestN = n; bestSum = s }
        }
        val p = bestSum / bestN
        tempoConfV = (bestN.toFloat() / iois.size).coerceIn(0f, 1f)
        if (periodMs <= 0 || kotlin.math.abs(p - periodMs) > periodMs * 0.1) {
            periodMs = p                     // tempo changed — re-anchor the grid on this onset
            anchorMs = now
        } else {
            periodMs += (p - periodMs) * 0.2
            // Phase-lock: if this onset lands near the grid, pull the anchor toward it (30%).
            val ph = ((now - anchorMs).toDouble() % periodMs) / periodMs
            val err = if (ph > 0.5) ph - 1.0 else ph
            if (kotlin.math.abs(err) < 0.2) anchorMs += (err * periodMs * 0.3).toLong()
        }
    }

    @Synchronized
    fun acquire(context: Context) {
        refs++
        if (viz != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "music pulse: RECORD_AUDIO not granted — staying silent")
            return
        }
        runCatching {
            val v = Visualizer(0)
            // EMUI: fresh instance can be ENABLED already → disable, resize best-effort, re-enable.
            runCatching { if (v.enabled) v.enabled = false }
            runCatching { v.captureSize = Visualizer.getCaptureSizeRange()[1] }
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, wave: ByteArray?, rate: Int) {
                        wave ?: return
                        var sum = 0.0
                        for (b in wave) {
                            val c = (b.toInt() and 0xFF) - 128
                            sum += (c * c).toDouble()
                        }
                        val raw = (sqrt(sum / wave.size) / 64.0).coerceIn(0.0, 1.0).toFloat()
                        if (raw > 0.015f) signalAt = SystemClock.elapsedRealtime()
                        val cur = levelV
                        // Fast attack so hits register, slow release so the level breathes down.
                        levelV = if (raw > cur) cur + (raw - cur) * 0.5f else cur + (raw - cur) * 0.12f
                    }

                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) {
                        fft ?: return
                        var bass = 0.0
                        for (k in 1..8) {   // low bins ≈ bass; magnitude from the (re, im) pairs
                            val re = fft[2 * k].toDouble()
                            val im = fft[2 * k + 1].toDouble()
                            bass += sqrt(re * re + im * im)
                        }
                        if (!bassInit) { bassAvg = bass; bassInit = true }
                        // Onset = bass jumps well above its ~2s running average.
                        if (bassAvg > 1.0 && bass > bassAvg * 1.45) {
                            val now = SystemClock.elapsedRealtime()
                            // Refractory 180ms: one onset per drum hit, not per FFT frame of its decay.
                            if (now - beatAt > 180) {
                                beatPeak = (((bass / bassAvg) - 1.0) / 1.5).coerceIn(0.4, 1.0).toFloat()
                                beatAt = now
                                updateTempo(now)
                            }
                        }
                        bassAvg += (bass - bassAvg) * 0.08
                    }
                },
                Visualizer.getMaxCaptureRate(), true, true,
            )
            v.enabled = true
            viz = v
            // Grace period: count "silent since" from the start, so silentMs() ramps from 0 and the
            // fallback only engages if real signal genuinely never arrives.
            signalAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "music pulse: visualizer started (rate=${Visualizer.getMaxCaptureRate()} mHz)")
        }.onFailure { Log.w(TAG, "music pulse: start failed: ${it.message}") }
    }

    @Synchronized
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0 && viz != null) {
            runCatching {
                viz?.enabled = false
                viz?.release()
            }
            viz = null
            levelV = 0f
            beatPeak = 0f
            signalAt = 0L
            bassInit = false
            bassAvg = 0.0
            onsets.clear()
            periodMs = 0.0
            tempoConfV = 0f
            Log.i(TAG, "music pulse: visualizer released")
        }
    }

    /** Injected as `window.OngakuPulse`; the page's rAF loop polls these each frame. */
    object Bridge {
        @JavascriptInterface
        fun level(): Float = levelV

        @JavascriptInterface
        fun beat(): Float {
            val dt = SystemClock.elapsedRealtime() - beatAt
            return if (dt > 600) 0f else beatPeak * exp(-dt / 180.0).toFloat()
        }

        @JavascriptInterface
        fun bpm(): Float = if (periodMs > 0) (60_000.0 / periodMs).toFloat() else 0f

        /** Position within the current beat, 0 (on the beat) → 1 (next beat). 0 while no tempo. */
        @JavascriptInterface
        fun beatPhase(): Float {
            val p = periodMs
            if (p <= 0) return 0f
            var ph = ((SystemClock.elapsedRealtime() - anchorMs).toDouble() % p) / p
            if (ph < 0) ph += 1.0
            return ph.toFloat()
        }

        /**
         * ms since the capture last carried real signal (Long.MAX_VALUE if never / not running).
         * Stays huge during Android Auto / A2DP-offload playback, where the output mix is silent
         * even though music plays — consumers use this to switch to a non-reactive fallback.
         */
        @JavascriptInterface
        fun silentMs(): Long {
            val at = signalAt
            if (at == 0L) return Long.MAX_VALUE
            return SystemClock.elapsedRealtime() - at
        }

        /** Tempo confidence 0..1, fading out when onsets stop coming (track pause/quiet outro). */
        @JavascriptInterface
        fun tempoConf(): Float {
            val silent = SystemClock.elapsedRealtime() - beatAt
            if (silent <= 2_000) return tempoConfV
            return tempoConfV * exp(-(silent - 2_000) / 2_000.0).toFloat()
        }
    }
}
