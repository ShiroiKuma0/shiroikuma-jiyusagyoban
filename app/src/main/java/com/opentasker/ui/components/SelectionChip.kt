package com.opentasker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A chip whose SELECTED state is the bordered one.
 *
 * Material3 has this the other way round — it fills the selected chip and outlines the unselected —
 * and in 白い熊's black-and-yellow that reads as precisely backwards (白い熊, 2026-08-09). Everywhere
 * else in this app a yellow border means live: an OutlinedButton is the thing you can press, a filled
 * yellow Button is the thing you are about to do. Against that, M3's grey-filled slab looks disabled
 * and the outlined chip beside it looks chosen, so 「記事変換」 showed "正確 81MB" as picked while the
 * code had "速い 16MB".
 *
 * The unselected chip carries NO border. A thin one in `outlineVariant` was the first attempt and in
 * this palette it is nearly as bright as the primary — two yellow outlines side by side differing
 * only in width, which is not a state anyone reads at a glance.
 *
 * Two channels, never the border alone: the chosen chip also takes the check and the full-strength
 * label, so the state survives a quick glance and someone who does not separate the hues.
 */
@Composable
fun SelectionChip(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { if (enabled && !selected) onSelect() },
        label = { Text(label) },
        leadingIcon = if (!selected) null else {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = FilterChipDefaults.filterChipColors(
            // Neither state is filled: the border carries it, so a fill would only compete.
            containerColor = Color.Transparent,
            selectedContainerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
