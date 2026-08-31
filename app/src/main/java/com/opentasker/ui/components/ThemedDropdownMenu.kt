package com.opentasker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A [DropdownMenu] themed like the rest of the app: a pure-black container + a yellow border, matching
 * the cards / search box / dialogs. Use this instead of the raw Material3 DropdownMenu everywhere so the
 * popup menus aren't the default lifted/"brownish" surface with no outline.
 */
@Composable
fun ThemedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        content = content,
    )
}
