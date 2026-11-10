package com.opentasker.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The black-yellow switch, used in place of Material's [androidx.compose.material3.Switch] everywhere.
 *
 * Never filled: both states are an outlined track with a small thumb. On is the full accent with the
 * thumb on the right; off is the accent dimmed to 55 % (#8C8C00 for the default #FFFF00) with the
 * thumb on the left. The thumb keeps one size in both states — Material grows it when checked.
 * Geometry otherwise matches Material's (52×32 dp track, 2 dp frame, 48 dp touch target).
 */
@Composable
fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val target = (if (checked) accent else accent.dimmed()).let { if (enabled) it else it.copy(alpha = 0.38f) }
    val color by animateColorAsState(target, label = "switchColor")
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "switchThumb")
    val interaction = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Canvas(modifier.then(interaction).size(52.dp, 32.dp)) {
        val frame = 2.dp.toPx()
        val radius = size.height / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(frame / 2, frame / 2),
            size = Size(size.width - frame, size.height - frame),
            cornerRadius = CornerRadius(radius - frame / 2),
            style = Stroke(frame),
        )
        val inset = 16.dp.toPx()
        drawCircle(
            color = color,
            radius = 8.dp.toPx(),
            center = Offset(inset + (size.width - 2 * inset) * position, radius),
        )
    }
}

/** The accent scaled to 55 % over black — #FFFF00 becomes #8C8C00. */
private fun Color.dimmed(): Color = Color(red * 0.55f, green * 0.55f, blue * 0.55f, alpha)
