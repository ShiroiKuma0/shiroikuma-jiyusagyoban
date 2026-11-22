package com.opentasker.ui.gengoshima

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.opentasker.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import com.opentasker.ui.theme.ThemedSwitch
import com.opentasker.ui.theme.UiRole
import com.opentasker.ui.theme.role

/**
 * A top-right header action of a 言語島 window — 閉じる, 止める — as an outlined pill, the same
 * shape as the window's other buttons rather than bare text (白い熊, 2026-10-12).
 */
@Composable
internal fun HeaderPill(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label, fontSize = 18.sp) }
}

/**
 * An on/off setting of a 言語島 window: its label and the theme's outlined switch, the whole row the
 * touch target. It used to be a pill with a filled check box (白い熊, 2026-10-23); the theme allows
 * nothing filled and keeps one look for every on/off, so the state now reads from the switch — the
 * dot's side and its brightness — and the name stays so the call sites did not move.
 *
 * [choice] is for one entry of a single choice (the island of a word): then the mark is a radio
 * button, because a mark that says which entry is chosen is not a switch.
 */
@Composable
internal fun TogglePill(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    choice: Boolean = false,
    onToggle: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (choice) {
                    Modifier.selectable(selected = checked, role = Role.RadioButton, onClick = onToggle)
                } else {
                    Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (choice) {
            RadioButton(
                selected = checked,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = role(UiRole.RADIO_ON),
                    unselectedColor = role(UiRole.RADIO_OFF),
                ),
            )
        }
        Text(label, fontSize = 16.sp, color = role(UiRole.LIST_LABEL))
        if (!choice) ThemedSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 閉じる on a window opened from the 言語島 board: back to the board, not out of 言語島. The board
 * finishes itself when a tile hands over, so it is opened again rather than returned to.
 */
internal fun Activity.closeToBoard() {
    finish()
    GengoshimaBoardActivity.open(this)
}
