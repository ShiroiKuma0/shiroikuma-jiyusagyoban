package com.opentasker.core.actions

import android.Manifest
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Diagnostic for the audio-reactive 音楽端灯 plan: taps the device OUTPUT MIX via
 * [Visualizer] on audio session 0 for a few seconds and reports whether real waveform
 * data arrives (some OEM builds return all-zeros there as a privacy clampdown — this
 * decides whether the reactive meteor pipeline is buildable on this device). Nothing is
 * recorded or stored: the Visualizer yields transient 8-bit visualization snapshots only.
 *
 * Args:
 *  - "seconds": sampling window (default 5, clamped 1..30) — play music while it runs.
 *  - "var": variable the multi-line report is stored into (default "viz").
 */
class MusicVizTestAction : Action {
    override val id = "music.viz.test"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val varName = args["var"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "viz"
        if (ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ctx.variables.set(varName, "🔴 RECORD_AUDIO 未許可 / permission not granted")
            return ActionResult.Failure("RECORD_AUDIO not granted")
        }
        val seconds = args["seconds"]?.trim()?.toIntOrNull()?.coerceIn(1, 30) ?: 5
        return withContext(Dispatchers.Default) {
            var viz: Visualizer? = null
            try {
                viz = Visualizer(0).apply {
                    // On EMUI a fresh Visualizer(0) can arrive ALREADY ENABLED (state 2), where
                    // setCaptureSize throws "called in wrong state" (白い熊's Mate XT, 2026-07-11).
                    // Disable first, resize best-effort (the default size works fine too), re-enable.
                    runCatching { if (enabled) enabled = false }
                    runCatching { captureSize = Visualizer.getCaptureSizeRange()[1] }   // max, typically 1024
                    enabled = true
                }
                val wave = ByteArray(viz.captureSize)
                val fft = ByteArray(viz.captureSize)
                var frames = 0
                var live = 0
                var peakRms = 0.0
                var peakBass = 0.0
                val end = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < end) {
                    if (viz.getWaveForm(wave) == Visualizer.SUCCESS) {
                        frames++
                        // Waveform bytes are unsigned 8-bit centred at 128; silence/blocked = flat 128 (or 0).
                        var sum = 0.0
                        for (b in wave) {
                            val c = (b.toInt() and 0xFF) - 128
                            sum += (c * c).toDouble()
                        }
                        val rms = sqrt(sum / wave.size)
                        if (rms > 1.5) live++
                        if (rms > peakRms) peakRms = rms
                    }
                    if (viz.getFft(fft) == Visualizer.SUCCESS) {
                        // Low bins ≈ bass; magnitude from the (re, im) pairs at 2k / 2k+1.
                        var bass = 0.0
                        for (k in 1..8) {
                            val re = fft[2 * k].toDouble()
                            val im = fft[2 * k + 1].toDouble()
                            bass += sqrt(re * re + im * im)
                        }
                        if (bass > peakBass) peakBass = bass
                    }
                    delay(50)
                }
                val pct = if (frames > 0) live * 100 / frames else 0
                val verdict = when {
                    frames == 0 -> "🔴 フレーム取得ゼロ — Visualizer が機能していない。\n🔴 No frames at all — Visualizer not delivering."
                    pct >= 30 -> "✅ 出力ミックスの生データ取得OK — 音楽反応化は実装可能。\n✅ Live output-mix data — the reactive pipeline is buildable."
                    else -> "🔴 ほぼ全て無音/ゼロ — EMUI がセッション0をブロックしている可能性。\n🔴 Nearly all zeros — EMUI likely blocks session-0 capture."
                }
                ctx.variables.set(
                    varName,
                    "計測 ${seconds}s: フレーム $frames / 有音 $live (${pct}%)\n" +
                        "ピークRMS ${(peakRms * 10).roundToInt() / 10.0} /127・ピーク低音 ${peakBass.roundToInt()}\n" +
                        verdict,
                )
                ActionResult.Success
            } catch (t: Throwable) {
                ctx.variables.set(varName, "🔴 Visualizer 生成/計測失敗: ${t.message}")
                ActionResult.Failure("visualizer failed: ${t.message}")
            } finally {
                runCatching {
                    viz?.enabled = false
                    viz?.release()
                }
            }
        }
    }
}
