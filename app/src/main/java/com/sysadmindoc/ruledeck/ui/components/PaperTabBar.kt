package com.sysadmindoc.ruledeck.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sysadmindoc.ruledeck.ui.theme.UiRole
import com.sysadmindoc.ruledeck.ui.theme.role

/** One paper tab: [badge] is a count shown after the title in a filled pill (0 = none). */
data class PaperTab(val id: Long, val title: String, val badge: Int = 0)

/**
 * Tabs drawn as stacked paper files — the look of 白い熊 Messēji's conversation tabs and 白い熊
 * 書類管理's file tabs, ported from Messēji's `FolderTabBar` (a View) with its measures unchanged:
 * one accent line runs along the bottom, turns up the left of the active tab, across its top,
 * slants down its right and runs on under the rest; the inactive tabs are shorter and show their
 * own outline, dimmed. Nothing is filled.
 *
 * The row scrolls sideways when the tabs outrun the screen, and keeps the active tab in view — a
 * quarter of the width in from the left when it has to move. A tap selects; a long press is
 * [onLongPress] (the tab's menu).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaperTabBar(
    tabs: List<PaperTab>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    textScale: Float = 1.3f,
    onLongPress: (Long) -> Unit = {},
) {
    val ink = role(UiRole.TAB_INK)
    val paper = role(UiRole.TAB_GROUND)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val inactiveHeight = (INACTIVE_HEIGHT_DP * textScale).dp
    val barHeight = (BAR_HEIGHT_DP - INACTIVE_HEIGHT_DP).dp + inactiveHeight
    val slant = SLANT_DP.dp
    val scroll = rememberScrollState()
    // Each tab's box inside the row, as laid out — what the outlines are drawn around.
    val boxes = remember { mutableStateMapOf<Long, Rect>() }

    BoxWithConstraints(modifier.fillMaxWidth().height(barHeight).background(paper)) {
        val viewport = with(density) { maxWidth.toPx() }
        val selectedBox = boxes[selectedId]
        LaunchedEffect(selectedId, selectedBox, viewport) {
            val box = selectedBox ?: return@LaunchedEffect
            val slantPx = with(density) { slant.toPx() }
            val visible = box.left >= scroll.value && box.right + slantPx <= scroll.value + viewport
            if (!visible) scroll.animateScrollTo((box.left - viewport / SCROLL_LEAD_FRACTION).toInt().coerceAtLeast(0))
        }
        Row(
            Modifier
                .horizontalScroll(scroll)
                .widthIn(min = maxWidth)
                .height(barHeight)
                .drawBehind {
                    val stroke = STROKE_DP.dp.toPx()
                    val slantPx = slant.toPx()
                    val style = Stroke(width = stroke, pathEffect = PathEffect.cornerPathEffect(CORNER_DP.dp.toPx()))
                    val bottom = size.height - stroke / 2
                    fun outline(box: Rect, fromLeftEdge: Boolean) = Path().apply {
                        if (fromLeftEdge) {
                            moveTo(0f, bottom)
                            lineTo(box.left, bottom)
                        } else {
                            moveTo(box.left, bottom)
                        }
                        lineTo(box.left, box.top + stroke / 2)
                        lineTo(box.right, box.top + stroke / 2)
                        lineTo(box.right + slantPx, bottom)
                    }
                    tabs.forEach { tab ->
                        if (tab.id != selectedId) boxes[tab.id]?.let { drawPath(outline(it, false), ink.copy(alpha = SHADED_ALPHA), style = style) }
                    }
                    // The border line, wrapping around the active tab.
                    val line = boxes[selectedId]?.takeIf { tabs.any { t -> t.id == selectedId } }?.let { outline(it, true) }
                        ?: Path().apply { moveTo(0f, bottom) }
                    line.lineTo(size.width, bottom)
                    drawPath(line, ink, style = style)
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEach { tab ->
                val active = tab.id == selectedId
                Box(
                    Modifier
                        .padding(end = slant)
                        .height(if (active) barHeight - ACTIVE_TOP_DP.dp else inactiveHeight)
                        .onGloballyPositioned {
                            val at = it.positionInParent()
                            val box = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                            if (boxes[tab.id] != box) boxes[tab.id] = box
                        }
                        .combinedClickable(
                            onClick = { onSelect(tab.id) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress(tab.id)
                            },
                        )
                        .padding(horizontal = (TAB_PADDING_DP * textScale).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tab.title,
                            fontSize = (TITLE_SP * textScale).sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) ink else ink.copy(alpha = DIM_ALPHA),
                            maxLines = 1,
                        )
                        if (tab.badge > 0) {
                            Spacer(Modifier.width(BADGE_GAP_DP.dp))
                            Box(
                                Modifier
                                    .defaultMinSize(minWidth = BADGE_SIZE_DP.dp, minHeight = BADGE_SIZE_DP.dp)
                                    .background(if (active) ink else ink.copy(alpha = DIM_ALPHA), RoundedCornerShape(50))
                                    .padding(horizontal = BADGE_PADDING_DP.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(tab.badge.toString(), fontSize = BADGE_SP.sp, fontWeight = FontWeight.Bold, color = paper, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Messēji's FolderTabBar, measure for measure.
private const val SHADED_ALPHA = 0x55 / 255f
private const val DIM_ALPHA = 0x8C / 255f
private const val TITLE_SP = 15f
private const val BADGE_SP = 12f
private const val SCROLL_LEAD_FRACTION = 4
private const val SLANT_DP = 10f
private const val ACTIVE_TOP_DP = 4f
private const val INACTIVE_HEIGHT_DP = 34f
private const val BAR_HEIGHT_DP = 46f
private const val STROKE_DP = 1.5f
private const val CORNER_DP = 8f
private const val TAB_PADDING_DP = 14f
private const val BADGE_GAP_DP = 6f
private const val BADGE_SIZE_DP = 20f
private const val BADGE_PADDING_DP = 5f
