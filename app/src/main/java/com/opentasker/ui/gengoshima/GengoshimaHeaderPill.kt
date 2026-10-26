package com.opentasker.ui.gengoshima

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
 * An on/off setting of a 言語島 window as a pill whose state reads at a glance (白い熊, 2026-10-23).
 * All yellow in both states (白い熊 wants no grey). On: a thick border and a solid box holding a
 * heavy black check. Off: a thin border and an empty outlined box. The check, the border's weight
 * and the filled box all differ, so the state reads without any change of colour.
 */
@Composable
internal fun TogglePill(label: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .border(if (checked) 3.dp else 1.dp, PILL_YELLOW, shape)
            .clickable(role = Role.Switch, onClick = onToggle)
            .padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val box = RoundedCornerShape(6.dp)
        Box(
            Modifier
                .size(28.dp)
                .then(if (checked) Modifier.background(PILL_YELLOW, box) else Modifier.border(2.dp, PILL_YELLOW, box)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Canvas(Modifier.size(20.dp)) {
                // Drawn rather than the Material glyph, whose stroke is too thin to read at arm's length.
                val tick = Path().apply {
                    moveTo(size.width * 0.08f, size.height * 0.55f)
                    lineTo(size.width * 0.40f, size.height * 0.85f)
                    lineTo(size.width * 0.94f, size.height * 0.18f)
                }
                drawPath(tick, Color.Black, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            label,
            fontSize = 16.sp,
            color = PILL_YELLOW,
        )
    }
}

private val PILL_YELLOW = Color(0xFFFFFF00)

/**
 * 閉じる on a window opened from the 言語島 board: back to the board, not out of 言語島. The board
 * finishes itself when a tile hands over, so it is opened again rather than returned to.
 */
internal fun Activity.closeToBoard() {
    finish()
    GengoshimaBoardActivity.open(this)
}
