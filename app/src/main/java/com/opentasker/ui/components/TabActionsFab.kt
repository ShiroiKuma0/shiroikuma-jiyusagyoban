package com.opentasker.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** One entry in a tab's action menu (New / Import / Export / …). */
data class TabAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

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
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add / import / export")
        }
        ThemedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = { open = false; action.onClick() },
                )
            }
        }
    }
}
