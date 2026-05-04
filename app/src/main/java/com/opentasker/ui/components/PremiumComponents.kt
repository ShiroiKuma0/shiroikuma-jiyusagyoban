package com.opentasker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.opentasker.ui.theme.ComponentSize
import com.opentasker.ui.theme.Opacity
import com.opentasker.ui.theme.Radii
import com.opentasker.ui.theme.Spacing

/**
 * Premium UI components for OpenTasker with consistent design system.
 */

// ========== Text Field with Error Support ==========
/**
 * Enhanced TextField with validation, error display, and helper text.
 */
@Composable
fun TextFieldWithError(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    helperText: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder != null) {{ Text(placeholder) }} else null,
            isError = error != null,
            leadingIcon = leadingIcon,
            trailingIcon = if (error != null) {
                {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(ComponentSize.iconMedium)
                    )
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = ComponentSize.buttonMedium),
            shape = RoundedCornerShape(Radii.sm),
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            readOnly = readOnly,
            interactionSource = interactionSource,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                errorBorderColor = MaterialTheme.colorScheme.error,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = Opacity.disabled),
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = Opacity.disabled),
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        
        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = Spacing.md)
            )
        } else if (helperText != null) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = helperText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = Spacing.md)
            )
        }
    }
}

// ========== Loading Button ==========
/**
 * Button with built-in loading state animation.
 */
@Composable
fun LoadingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    text: String,
    icon: ImageVector? = null,
    variant: ButtonVariant = ButtonVariant.Primary,
) {
    val buttonColors = when (variant) {
        ButtonVariant.Primary -> ButtonDefaults.buttonColors()
        ButtonVariant.Secondary -> ButtonDefaults.outlinedButtonColors()
        ButtonVariant.Destructive -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        )
        ButtonVariant.Text -> ButtonDefaults.textButtonColors()
    }
    
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ComponentSize.buttonMedium),
        enabled = enabled && !isLoading,
        colors = buttonColors,
        shape = RoundedCornerShape(Radii.md),
        contentPadding = PaddingValues(
            horizontal = Spacing.md,
            vertical = Spacing.sm
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(ComponentSize.iconSmall),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
        }
        
        if (icon != null && !isLoading) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(ComponentSize.iconMedium)
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
        }
        
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

enum class ButtonVariant {
    Primary, Secondary, Destructive, Text
}

// ========== Loading Skeleton ==========
/**
 * Animated skeleton loader for list items and content.
 */
@Composable
fun LoadingSkeleton(
    modifier: Modifier = Modifier,
    lines: Int = 3,
    lineHeight: TextStyle = MaterialTheme.typography.bodyMedium
) {
    Column(modifier = modifier) {
        repeat(lines) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineHeight.fontSize.value.dp * 1.2f)
                    .padding(vertical = Spacing.xs),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(Radii.sm)
            ) {}
        }
    }
}

// ========== State Badge ==========
/**
 * Visual badge for status indicators.
 */
@Composable
fun StateBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(Radii.xs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(
                horizontal = Spacing.sm,
                vertical = Spacing.xs
            )
        )
    }
}

// ========== Loading Indicator ==========
/**
 * Full-screen or inline loading indicator.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(ComponentSize.iconLarge),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ========== Error State ==========
/**
 * Error state display with optional action.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = "Error",
            modifier = Modifier.size(ComponentSize.iconLarge),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

// ========== Disabled State Feedback ==========
/**
 * Visual feedback for disabled UI elements.
 */
fun Modifier.disabledAlpha(disabled: Boolean): Modifier =
    if (disabled) this.then(Modifier.background(Color.Black.copy(alpha = 0.2f))) else this
