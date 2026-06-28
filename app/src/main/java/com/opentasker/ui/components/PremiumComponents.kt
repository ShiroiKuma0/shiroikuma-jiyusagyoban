package com.opentasker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem

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
    modifier: Modifier = Modifier,
    error: String? = null,
    helperText: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val errorContentDescription = stringResource(R.string.ui_error_content_description)
    
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
                        contentDescription = errorContentDescription,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(DesignSystem.ComponentSize.iconMedium)
                    )
                }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = DesignSystem.ComponentSize.buttonMedium)
                .semantics {
                    error?.let { this.error(it) }
                },
            shape = RoundedCornerShape(DesignSystem.Radii.sm),
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            readOnly = readOnly,
            interactionSource = interactionSource,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                errorBorderColor = MaterialTheme.colorScheme.error,
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = DesignSystem.Opacity.disabled),
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DesignSystem.Opacity.disabled),
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        
        if (error != null) {
            Spacer(modifier = Modifier.height(DesignSystem.Spacing.xs))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(start = DesignSystem.Spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else if (helperText != null) {
            Spacer(modifier = Modifier.height(DesignSystem.Spacing.xs))
            Text(
                text = helperText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = DesignSystem.Spacing.md)
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
    
    val stateDescription = when {
        isLoading -> stringResource(R.string.ui_loading_in_progress, text)
        !enabled -> stringResource(R.string.ui_disabled_label, text)
        else -> text
    }

    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = DesignSystem.ComponentSize.buttonMedium)
            .semantics { contentDescription = stateDescription },
        enabled = enabled && !isLoading,
        colors = buttonColors,
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        contentPadding = PaddingValues(
            horizontal = DesignSystem.Spacing.md,
            vertical = DesignSystem.Spacing.sm
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
        }
        
        if (icon != null && !isLoading) {
            Icon(
                icon,
                contentDescription = text,
                modifier = Modifier.size(DesignSystem.ComponentSize.iconMedium)
            )
            Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
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
                    .padding(vertical = DesignSystem.Spacing.xs),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(DesignSystem.Radii.sm)
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
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(DesignSystem.Radii.xs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(
                horizontal = DesignSystem.Spacing.sm,
                vertical = DesignSystem.Spacing.xs
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
    val errorContentDescription = stringResource(R.string.ui_error_content_description)
    val retryContentDescription = stringResource(R.string.ui_retry_loading_content_description)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(DesignSystem.ComponentSize.iconLarge),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp
        )
        if (message != null) {
            Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
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
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val errorContentDescription = stringResource(R.string.ui_error_content_description)
    val retryContentDescription = stringResource(R.string.ui_retry_content_description)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(DesignSystem.Spacing.lg)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = errorContentDescription,
            modifier = Modifier.size(DesignSystem.ComponentSize.iconLarge),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = retryContentDescription }
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

// ========== Disabled State Feedback ==========
/**
 * Visual feedback for disabled UI elements.
 */
fun Modifier.disabledAlpha(disabled: Boolean): Modifier =
    if (disabled) this.then(Modifier.alpha(DesignSystem.Opacity.disabled)) else this


