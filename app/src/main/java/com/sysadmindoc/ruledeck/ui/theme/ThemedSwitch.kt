package com.sysadmindoc.ruledeck.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
 * Never filled: both states are an outlined track with a small thumb. On is the accent with the
 * thumb on the right; off is an opaque darker yellow (#8C8C00 by default) with the thumb on the
 * left. Outline and dot, on and off, are four roles of their own (UiRoles.kt). The thumb keeps one size in both states — Material grows it when checked.
 * Geometry otherwise matches Material's (52×32 dp track, 2 dp frame, 48 dp touch target).
 */
@Composable
fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The smaller switch of a menu row (44 × 24 dp), without the 48 dp touch target — the row is the target. */
    compact: Boolean = false,
) {
    fun Color.shown() = if (enabled) this else copy(alpha = 0.38f)
    val frameColor by animateColorAsState(role(if (checked) UiRole.SWITCH_ON_FRAME else UiRole.SWITCH_OFF_FRAME).shown(), label = "switchFrame")
    val dotColor by animateColorAsState(role(if (checked) UiRole.SWITCH_ON_DOT else UiRole.SWITCH_OFF_DOT).shown(), label = "switchDot")
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "switchThumb")
    val interaction = if (onCheckedChange != null) {
        (if (compact) Modifier else Modifier.minimumInteractiveComponentSize())
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Canvas(modifier.then(interaction).size(if (compact) 44.dp else 52.dp, if (compact) 24.dp else 32.dp)) {
        val frame = 2.dp.toPx()
        val radius = size.height / 2
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(frame / 2, frame / 2),
            size = Size(size.width - frame, size.height - frame),
            cornerRadius = CornerRadius(radius - frame / 2),
            style = Stroke(frame),
        )
        // The dot is 46 % of the track's height and sits the same gap from the frame all round.
        val dot = size.height * 0.23f
        val inset = radius
        drawCircle(
            color = dotColor,
            radius = dot,
            center = Offset(inset + (size.width - 2 * inset) * position, radius),
        )
    }
}
