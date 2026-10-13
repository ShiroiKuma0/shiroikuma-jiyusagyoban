package com.opentasker.ui.gengoshima

import android.app.Activity
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/**
 * A top-right header action of a 言語島 window — 閉じる, 止める — as an outlined pill, the same
 * shape as the window's other buttons rather than bare text (白い熊, 2026-10-12).
 */
@Composable
internal fun HeaderPill(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label, fontSize = 18.sp) }
}

/**
 * 閉じる on a window opened from the 言語島 board: back to the board, not out of 言語島. The board
 * finishes itself when a tile hands over, so it is opened again rather than returned to.
 */
internal fun Activity.closeToBoard() {
    finish()
    GengoshimaBoardActivity.open(this)
}
