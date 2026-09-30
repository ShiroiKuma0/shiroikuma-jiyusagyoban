package com.opentasker.ui.gengoshima

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.gengoshima.GenerationRunner.Progress
import com.opentasker.core.gengoshima.GenerationRunner.Step
import com.opentasker.core.gengoshima.GenerationRunner.StepState
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * 言語島's progress window at the three moments that matter: mid-voicing, finished well, and
 * finished with a failure. Rendered because the window only exists while a run does, over a phone
 * that is normally locked.
 */
private const val T0 = 1_700_000_000_000L

private val RUNNING = Progress(
    steps = listOf(
        Step("訳す / Translate", StepState.DONE, "12 / 12"),
        Step("音声 / Voice", StepState.RUN, "作成中 / making 6 / 12 · 2秒 · 残り約 21秒 / ~21s left\n年を取ると、長時間働くのがきつくなってくるよね。"),
        Step("整理 / Tidy the folders"),
    ),
    percent = 41,
    log = listOf(
        "→ Claude: Work — friends — 12 文",
        "  1/12  仕事の話をしよう。",
        "  2/12  毎朝八時に家を出るんだ。",
        "✓ 仕事の話: 12 文 · 31秒",
        "→ 白い熊 音声: 12 文",
        "  ♪ 1/12  1.8秒の音声 · 作成 4.1秒  仕事の話をしよう。",
        "  ♪ 5/12  2.4秒の音声 · 作成 3.4秒  年を取ると、長時間働くのがきつくなってくるよね。",
    ),
    startedAt = T0,
)

private val DONE = RUNNING.copy(
    steps = listOf(
        Step("訳す / Translate", StepState.DONE, "12 / 12"),
        Step("音声 / Voice", StepState.DONE, "12 / 12"),
        Step("整理 / Tidy the folders", StepState.DONE, "移動 0 · 削除 0"),
    ),
    percent = 100,
    log = RUNNING.log + "✓ 音声 12 / 12",
    running = false,
    ok = true,
    result = "できました — 訳 12 · 音声 12",
    finishedAt = T0 + 83_000,
)

private val FAILED = DONE.copy(
    steps = listOf(
        Step("訳す / Translate", StepState.FAIL, "no API key — set %Gengoshima_ApiKey in 日本語の設定 and run it"),
        Step("音声 / Voice", StepState.SKIP, "読ませるものはありません / nothing to voice"),
        Step("整理 / Tidy the folders", StepState.DONE, "移動 0 · 削除 0"),
    ),
    log = listOf("✕ no API key — set %Gengoshima_ApiKey in 日本語の設定 and run it"),
    ok = false,
    result = "終わらなかったものがあります — 訳 0 · 音声 0 · 失敗 12 — no API key",
)

@Composable
private fun Frame(p: Progress) = OpenTaskerTheme { ProgressPanelContent(p, T0 + 37_000, {}, {}) }

@PreviewTest
@Preview(name = "言語島 progress — running", widthDp = 413, heightDp = 860, fontScale = 1.3f, showBackground = true)
@Composable
fun GengoshimaProgressRunningPreview() = Frame(RUNNING)

@PreviewTest
@Preview(name = "言語島 progress — done", widthDp = 413, heightDp = 860, fontScale = 1.3f, showBackground = true)
@Composable
fun GengoshimaProgressDonePreview() = Frame(DONE)

@PreviewTest
@Preview(name = "言語島 progress — failed", widthDp = 413, heightDp = 860, fontScale = 1.3f, showBackground = true)
@Composable
fun GengoshimaProgressFailedPreview() = Frame(FAILED)
