package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentasker.core.capabilities.ActionCapability
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.CapabilityRequirement
import com.opentasker.core.capabilities.CapabilityState

/**
 * The capability note shown beneath an action everywhere it appears (picker, task editor, config
 * dialog). Replaces the old imperative "go enable X" reason text with a NEUTRAL requirement sentence
 * plus a CLICKABLE STATUS PILL reflecting the live state — tapping the pill opens the relevant Settings
 * screen and re-evaluates on return.
 */
@Composable
internal fun CapabilityNote(capability: ActionCapability, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val met = remember(refresh) { CapabilityState.isMet(capability.requirement, context) }

    if (capability.requirement == CapabilityRequirement.None) {
        // No deep-linkable requirement: only genuinely-unsupported / no-setup actions carry a note here,
        // and there's nothing to toggle, so just surface the reason in red when not supported.
        if (capability.level != CapabilityLevel.Supported) {
            Text(
                capability.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier,
            )
        }
        return
    }

    val ok = met
    val pillColor = if (ok) Color(0xFFFFFF00) else MaterialTheme.colorScheme.error
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            CapabilityState.requirementNote(capability.requirement),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = {
                CapabilityState.settingsIntent(capability.requirement, context)?.let { i ->
                    runCatching { context.startActivity(i) }
                    refresh++
                }
            },
            shape = RoundedCornerShape(50),
            color = Color(0xFF000000),
            border = BorderStroke(1.dp, pillColor),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    CapabilityState.statusLabel(capability.requirement, ok),
                    style = MaterialTheme.typography.labelMedium,
                    color = pillColor,
                )
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = pillColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
