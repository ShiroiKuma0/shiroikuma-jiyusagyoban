package com.opentasker.core.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * The 辺一覧 / 鍵一覧 reference sheets, rendered offline.
 *
 * These exist because the only other way to see this layout is to run the task on the phone, and the
 * phone is normally locked — a screenshot of it is not something this end can take. `./gradlew
 * updateDebugScreenshotTest` writes the PNGs under `app/src/screenshotTestDebug/reference/`, so the
 * indent cascade, the section rules and the rails can be looked at before a build goes over.
 *
 * **`@PreviewTest` is not optional.** The screenshot engine discovers methods carrying THAT
 * annotation, not `@Preview`; nothing in this toolchain generates it, so a preview without it is
 * silently invisible and the task fails with "did not discover any tests". See the note in
 * `CLAUDE.md`.
 *
 * The strings are exactly what `scene.gestures` and `key.bindings` emit, at the 1.5 scale both sheets
 * ship with. They are literals rather than live calls because those actions read the device database;
 * keeping the fixture here means the rendering can be checked without one.
 */

private const val EDGE_SHEET = """## 右辺
### 右中
**スワイプ ↑** → __音量パネル表示__
**スワイプ ↓** → __音量パネル表示__
**スワイプ ←** → __右中Back__

### 右下
**スワイプ ↑** → __キーボード表示__
**スワイプ ↓** → __右下スクショ__

## 下辺
### 下中
**スワイプ ↑** → __下中Home__
**スワイプ ←** → __次app__
**スワイプ →** → __前app__
**ロングスワイプ ↑** → __下中Recents__

### 下右
**スワイプ ←** → __次app__
**スワイプ →** → __前app__

## 左辺
### 左中
**スワイプ ↑** → __明度パネル表示__
**スワイプ ↓** → __明度パネル表示__

---

*辺 5 本 ・ ロングスワイプの閾値 200 dp*"""

private const val KEY_SHEET = """## 音量上キー
**単押し** → __上・単押し__
  *物理鍵 音量上単 ・ screen=on*
**二度押し** → __上・二度押し__
  *物理鍵 メディア*
**長押し** → __上・長押し__
  *物理鍵 ロック*

## 音量下キー
**単押し** → __録音停止 -- [80472]__
  *物理鍵 録音停止 ・ recording=true*
**単押し** → __下・単押し__
  *物理鍵 音量下単 ・ screen=on ・ recording=true でない*
**二度押し** → __下・二度押し__
  *物理鍵 カメラ*
**三度押し** → __下・三度押し__
  *物理鍵 時刻*
**長押し** → __下・長押し__
  *物理鍵 録音開始*

---

*割り当て 8 件 ・ 長押し 500 ms ・ 二度押し待ち 300 ms*"""

/** The English half of the same edge sheet — `lang=en`, which no device language can be set to show. */
private const val EDGE_SHEET_EN = """## Right edge
### 右中
**Swipe ↑** → __音量パネル表示__
**Swipe ↓** → __音量パネル表示__
**Swipe ←** → __右中Back__

### 右下
**Swipe ↑** → __キーボード表示__
**Swipe ↓** → __右下スクショ__

## Bottom edge
### 下中
**Swipe ↑** → __下中Home__
**Long swipe ↑** → __下中Recents__

---

*5 bars ・ long-swipe threshold 200 dp*"""

@Composable
private fun Sheet(markup: String) {
    OpenTaskerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            // AlertDialog's own text-slot padding. Without it the accent rail of a single-level sheet
            // sits flush against the frame and reads as clipped — an artefact of the fixture, not of
            // the layout, and exactly the kind of false alarm an unfaithful preview invents.
            Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                MarkupBody(markup, scale = 1.5f)
            }
        }
    }
}

// 330dp wide is the folded Mate XT cover panel — the narrowest the sheet has to survive, and where a
// two-step indent at 1.5× is most at risk of running off the edge.
@PreviewTest
@Preview(name = "辺一覧 folded", widthDp = 330, heightDp = 940, showBackground = true)
@Composable
fun EdgeSheetFolded() = Sheet(EDGE_SHEET)

@PreviewTest
@Preview(name = "辺一覧 unfolded", widthDp = 700, heightDp = 760, showBackground = true)
@Composable
fun EdgeSheetUnfolded() = Sheet(EDGE_SHEET)

@PreviewTest
@Preview(name = "辺一覧 英語", widthDp = 700, heightDp = 620, showBackground = true)
@Composable
fun EdgeSheetEnglish() = Sheet(EDGE_SHEET_EN)

@PreviewTest
@Preview(name = "鍵一覧 folded", widthDp = 330, heightDp = 1080, showBackground = true)
@Composable
fun KeySheetFolded() = Sheet(KEY_SHEET)

@PreviewTest
@Preview(name = "鍵一覧 unfolded", widthDp = 700, heightDp = 820, showBackground = true)
@Composable
fun KeySheetUnfolded() = Sheet(KEY_SHEET)
