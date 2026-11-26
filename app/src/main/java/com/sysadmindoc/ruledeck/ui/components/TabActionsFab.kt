package com.sysadmindoc.ruledeck.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.theme.UiRole
import com.sysadmindoc.ruledeck.ui.theme.role

/** One entry in a tab's action menu (New / Import / Export / …). [dividerBefore] draws a separator line
 *  above this item (used to set Export apart from the New/Import items). [role] says what the row
 *  does and so colours its icon; left null it is read off the label, so a caller that names its rows
 *  "New …", "Import …", "Export …" needs to say nothing. [opens] draws the › of a row that opens a
 *  screen, a dialog or a picker — by default every label that ends in "…". */
data class TabAction(
    val label: String,
    val icon: ImageVector,
    val dividerBefore: Boolean = false,
    val role: IconRole? = null,
    val opens: Boolean = label.endsWith("…"),
    val onClick: () -> Unit,
)

private fun TabAction.iconRole(): IconRole = role ?: when {
    label.startsWith("New") || label.startsWith("From template") -> IconRole.WRITE
    label.startsWith("Import") || label.startsWith("Export") -> IconRole.SHARE
    else -> IconRole.VIEW
}

/**
 * The uniform per-tab "+" button: tapping it opens a menu of [actions] (New <item>, Import JSON,
 * Import Tasker, Export, …). Same prominent entry point on every list tab.
 */
@Composable
fun TabActionsFab(actions: List<TabAction>) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        FloatingActionButton(
            onClick = { open = true },
            // Outlined like every other button: a ring, the glyph, and the ground showing through.
            containerColor = role(UiRole.FAB_GROUND),
            contentColor = role(UiRole.FAB_GLYPH),
            shape = CircleShape,
            modifier = Modifier.border(2.dp, role(UiRole.FAB_RING), CircleShape),
        ) {
            Icon(Icons.Filled.Add, contentDescription = tr("Add / import / export"))
        }
        ThemedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                if (action.dividerBefore) MenuDivider()
                DropdownMenuItem(
                    text = { Text(tr(action.label)) },
                    icon = action.icon,
                    role = action.iconRole(),
                    opens = action.opens,
                    onClick = { open = false; action.onClick() },
                )
            }
        }
    }
}
