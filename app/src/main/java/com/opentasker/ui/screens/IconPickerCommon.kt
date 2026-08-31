package com.opentasker.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/** Render a [Drawable] to an in-memory [ImageBitmap] for previews — WITHOUT persisting a PNG. */
internal fun drawableToPreview(drawable: Drawable, sizePx: Int = 96): ImageBitmap? = runCatching {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return@runCatching drawable.bitmap.asImageBitmap()
    }
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: sizePx
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: sizePx
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, w, h)
    drawable.draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()

/** The standard themed dialog border used across the icon pickers. */
@Composable
internal fun dialogBorder(): Modifier =
    Modifier.border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(28.dp))
