package com.opentasker.ui.gengoshima

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.core.gengoshima.GengoshimaLanguage
import com.opentasker.ui.theme.UiRole
import com.opentasker.ui.theme.role

/**
 * A source sentence's language: `EN` outlined, `CS` a solid pill with letters in its ground's
 * opposite — so a Czech row stands out by fill and by its letters, never by hue alone (白い熊 is
 * red-green colour-blind). [onClick] null makes it a plain mark; otherwise a tap switches EN ⇄ CS.
 * It is a tag that marks a state, not an action pill, so the fill stays; its colours are the
 * check-box roles, which are exactly this pair: a chosen ground and the mark that reads on it.
 */
@Composable
fun LanguageChip(language: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val cs = GengoshimaLanguage.normalize(language) == GengoshimaLanguage.CS
    val shape = RoundedCornerShape(50)
    val base = if (cs) {
        modifier.background(role(UiRole.ISLAND_CHIP_GROUND), shape)
    } else {
        modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    }
    Text(
        GengoshimaLanguage.chip(language),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = if (cs) role(UiRole.ISLAND_CHIP_LABEL) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
