package com.opentasker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The black-yellow pill — the one button look of every 白い熊 app (白い熊, 2026-11-02/03).
 *
 * **Never filled.** A filled yellow slab is too heavy on the eyes; every pill is an accent frame
 * with the background showing through. The MAIN action of a block is told apart by weight alone:
 * a thick frame and bold text ([Button]); every other action is a thin frame and normal text
 * ([OutlinedButton]). Settled in the 連絡先 chat after bevels, gold rims, tints, glows and icons were
 * all rendered and turned down — weight was the one that read as main-vs-regular without noise.
 * The numbers are commons' `Pill.kt` (sk12), so a pill here and a pill in 連絡先 are the same pill.
 *
 * Both are drop-in replacements for Material's: the call sites keep their names and arguments and
 * only the import changes, which is how "everywhere in the app, always" stays true for buttons
 * added later — a new screen picks these up by importing from here. [shape] is accepted and
 * ignored, and so is [border]: a pill is always fully round and its frame is its weight. A caller's `colors` keeps its meaning by lending its
 * colour to the frame and the text (a red destructive button becomes a red-framed pill).
 */
private val PILL_SHAPE = RoundedCornerShape(percent = 50)
private val PILL_STROKE_MAIN = 3.dp
private val PILL_STROKE = 1.25.dp
private const val DISABLED_ALPHA = 0.38f

/** The main action: thick accent frame, bold text, nothing filled. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") shape: Shape = PILL_SHAPE,
    colors: ButtonColors? = null,
    @Suppress("UNUSED_PARAMETER") border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Pill(onClick, modifier, enabled, colors, contentPadding, main = true, content)

/** Every other action: thin accent frame, normal text. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") shape: Shape = PILL_SHAPE,
    colors: ButtonColors? = null,
    @Suppress("UNUSED_PARAMETER") border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Pill(onClick, modifier, enabled, colors, contentPadding, main = false, content)

/**
 * What used to be a bare-text button — Cancel, No, Clear — is a regular pill too (白い熊,
 * 2026-11-03): a word on its own reads as a note, not as something to press. Same thin frame and
 * yellow text as [OutlinedButton]; the name is kept so Material's call sites move over unchanged.
 */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") shape: Shape = PILL_SHAPE,
    colors: ButtonColors? = null,
    @Suppress("UNUSED_PARAMETER") border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Pill(onClick, modifier, enabled, colors, contentPadding, main = false, content)

@Composable
private fun Pill(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: ButtonColors?,
    contentPadding: PaddingValues,
    main: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    // A filled caller's container colour was its accent; an outlined caller's content colour was.
    val accent = colors?.let { c ->
        c.containerColor.takeIf { it != Color.Transparent && it.alpha > 0f } ?: c.contentColor
    } ?: MaterialTheme.colorScheme.primary
    val ink = if (enabled) accent else accent.copy(alpha = DISABLED_ALPHA)
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PILL_SHAPE,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = accent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = accent.copy(alpha = DISABLED_ALPHA),
        ),
        border = BorderStroke(if (main) PILL_STROKE_MAIN else PILL_STROKE, ink),
        contentPadding = contentPadding,
    ) {
        if (main) {
            ProvideTextStyle(LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)) { content() }
        } else {
            content()
        }
    }
}
