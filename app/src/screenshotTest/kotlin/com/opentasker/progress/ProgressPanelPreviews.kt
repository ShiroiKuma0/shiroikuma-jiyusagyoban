package com.opentasker.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.progress.ProgressPanelState
import com.opentasker.core.progress.ProgressRow

/**
 * The selection panel — the window 保存復元 puts up to choose what gets backed up.
 *
 * Rendered because it is a system overlay: it exists for the length of a decision, over a phone that
 * is normally locked, so `screencap` catches it only by luck. The thing being looked at here is the
 * 「すべて選択 / 解除」 control, which sat in the list looking like one more checkbox row until 白い熊
 * asked for it to be a pill (2026-09-03).
 */
private fun app(key: String, label: String, items: Int, marked: Int) = ProgressRow(
    key = key,
    label = label,
    marked = true,
    children = (1..items).map { i ->
        ProgressRow(key = "$key.$i", label = "項目 $i", marked = i <= marked, depth = 1)
    },
)

private val STATE = ProgressPanelState(
    title = "保存",
    outer = listOf(
        app("shiroikuma.raikidoban", "白い熊 雷起動盤", 10, 10),
        app("shiroikuma.kabuka", "白い熊 株価表示", 5, 5),
        app("shiroikuma.chizu", "白い熊 地図", 31, 17),
        app("shiroikuma.renrakusaki", "白い熊 連絡先", 3, 3),
        app("shiroikuma.jiyusagyoban", "白い熊 自由作業盤", 9, 9),
        app("shiroikuma.message", "白い熊 メッセージ", 7, 7),
    ),
    outerUnit = "アプリ",
    innerUnit = "項目",
    selecting = true,
    rowsSelectable = true,
    icons = false,
    cancelLabel = "キャンセル",
    confirmLabel = "保存開始",
)

@Composable
private fun Frame(state: ProgressPanelState) {
    Box(Modifier.fillMaxSize().background(Color.Black)) { ProgressPanelUi(state) }
}

/** The folded panel, which is where this control is actually reached for. */
@PreviewTest
@Preview(name = "Panel — select all", widthDp = 413, heightDp = 700, fontScale = 1.3f, showBackground = true)
@Composable
fun ProgressPanelSelectAllPreview() = Frame(STATE)

/** And unfolded, where the pill has room and must still hug its own words rather than stretch. */
@PreviewTest
@Preview(name = "Panel — select all wide", widthDp = 916, heightDp = 600, showBackground = true)
@Composable
fun ProgressPanelSelectAllWidePreview() = Frame(STATE)
