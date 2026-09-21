package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.model.ActionSpec
import com.opentasker.ui.theme.OpenTaskerTheme

/**
 * An expanded task's action rows — the one screen 白い熊 reads a task ON.
 *
 * *"When unfolded each task must show each action with its contents fully, breaking the value into
 * more lines if necessary — there can be no `…` display. The full task must be fully visible on first
 * look, without editing individual items."* (白い熊, 2026-09-20.) That is a claim about LAYOUT at a
 * given width, which nothing in a unit test can check and nothing on the phone can show me — it is
 * normally locked, and `screencap` returns the keyguard. So the rows are rendered here at both
 * widths, with the values that broke: a path, a four-argument `scene.show`, and a label of Japanese
 * long enough to wrap twice.
 *
 * The fixture is 白い熊's own 「話す時計設定」 from the screenshot that prompted the change, so what
 * these renders show is the very row that was wrong.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */

private val TIME_BASE = ActionSpec(
    type = "var.set",
    label = "音源の基底フォルダ（声フォルダの親）。話す時計・時刻読み上げの両方がここから声フォルダを引く。",
    args = linkedMapOf(
        "name" to "DT_TimeBase",
        "value" to "/storage/emulated/0/〇/[666] 私資料/[666][672] 時間と日付/音源",
    ),
)

private val CHIME = ActionSpec(
    type = "var.set",
    label = "読み上げ前のチャイム音源（フルパス）。空にするとチャイムなしで読み上げが始まる。",
    args = linkedMapOf(
        "name" to "DT_Chime",
        "value" to "/storage/emulated/0/〇/[666] 私資料/[666][672] 時間と日付/音源/chime.ogg",
    ),
)

/** Four arguments: the row that fitted three and hid the fourth behind an ellipsis. */
private val SHOW_SCENE = ActionSpec(
    type = "scene.show",
    args = linkedMapOf(
        "scene" to "話す時計設定",
        "position" to "center",
        "modal" to "true",
        "dismissOnOutside" to "false",
    ),
)

/** No arguments at all — the row falls back to the action's own description. */
private val NO_ARGS = ActionSpec(type = "task.stop")

@Composable
private fun Frame() {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(TIME_BASE, CHIME, SHOW_SCENE, NO_ARGS).forEachIndexed { i, spec ->
                    ActionRow(
                        index = i,
                        taskId = 1L,
                        action = spec,
                        onSetArg = { _, _ -> },
                        selected = false,
                        selectionActive = false,
                        menuExpanded = false,
                        clipboardEmpty = true,
                        onTap = {},
                        onLongPress = {},
                        onMenuDismiss = {},
                        onClone = {},
                        onCopy = {},
                        onCut = {},
                        onDeleteSelection = {},
                        onToggleEnabled = {},
                        onPasteBefore = {},
                        onPaste = {},
                        onEdit = {},
                        onDelete = {},
                        onRun = {},
                        runBusy = false,
                    )
                }
            }
        }
    }
}

/**
 * The unfolded panel — 916 dp, where the rule is absolute: every argument whole, no ellipsis
 * anywhere, and the label standing open without a chevron to open it with.
 */
@PreviewTest
@Preview(name = "Actions — unfolded", widthDp = 916, heightDp = 620, showBackground = true)
@Composable
fun ActionRowsUnfoldedPreview() {
    Frame()
}

/**
 * The same rows at **fontScale 1.3**, which is 白い熊's own setting — 30 % more type to wrap, and
 * the render that decides whether "fully visible" survives the size they actually read at.
 */
@PreviewTest
@Preview(
    name = "Actions — unfolded big type",
    widthDp = 916,
    heightDp = 760,
    fontScale = 1.3f,
    showBackground = true,
)
@Composable
fun ActionRowsUnfoldedFontScalePreview() {
    Frame()
}

/**
 * The folded cover panel — 413 dp, where each argument gets its own full-width line.
 *
 * The label has no fold here either (白い熊, 2026-09-20: *"remove the label fold on the folded panel
 * too"*), so this render is the one that answers what that costs: a documented label is a paragraph,
 * and at 413 dp it is several lines of one before the first argument.
 */
@PreviewTest
@Preview(name = "Actions — folded", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun ActionRowsFoldedPreview() {
    Frame()
}
