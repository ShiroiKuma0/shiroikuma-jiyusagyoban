package com.opentasker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentasker.ui.theme.ThemedSwitch
import com.opentasker.ui.theme.UiRole
import com.opentasker.ui.theme.role

// Menus of the black-yellow theme (settled 2026-11-15 in the FairEmail chat): a menu must show at a
// glance which lines can be pressed, so every action row is an outlined pill as wide as the menu,
// with its icon inside at the left, coloured by what the row does. A menu is read by colour before
// it is read by word. Toggle rows stay plain and carry the outlined switch; a list of pure choices
// stays a plain list.
//
// [ThemedDropdownMenu] and [DropdownMenuItem] are drop-ins for Material's: a call site keeps its
// arguments and changes its import, and says what its row does with [icon], [role], [opens],
// [destructive] and [checked].

/** What a row does — which decides the colour of its icon. Each is a settable role (UiRoles.kt). */
enum class IconRole(internal val role: UiRole) {
    /** Runs, starts, loads, connects, places a call. */
    CONNECT(UiRole.ICON_CONNECT),

    /** Writes: new, rename, edit, reply. */
    WRITE(UiRole.ICON_MESSAGE),

    /** Is about a person, a group or a project. */
    PERSON(UiRole.ICON_CONTACT),

    /** Copies, moves, shares, exports, imports. */
    SHARE(UiRole.ICON_SHARE),

    /** Views or opens a page, leads to settings. */
    VIEW(UiRole.ICON_VIEW),

    /** Stops, disables, blocks, closes, leaves. */
    BLOCK(UiRole.ICON_BLOCK),

    /** Deletes or removes. */
    DELETE(UiRole.DELETE),
}

/** [role]'s colour, for an icon that says what its row does. */
@Composable
fun iconColor(role: IconRole): Color = role(role.role)

/** An icon coloured by what its row does: for the rows of menus, pickers, action lists and settings pages. */
@Composable
fun RoleIcon(icon: ImageVector, role: IconRole, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(icon, contentDescription, modifier, tint = iconColor(role))
}

private val MENU_PILL = RoundedCornerShape(percent = 50)
private const val DISABLED_ALPHA = 0.38f

/**
 * A [DropdownMenu] themed like the rest of the app: its ground and its frame are roles of their own.
 * Compose's dropdown already uses the whole window height before it scrolls, so a menu opened low on
 * the screen needs no shift.
 */
@Composable
fun ThemedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = role(UiRole.MENU_GROUND),
        border = BorderStroke(1.5.dp, role(UiRole.MENU_FRAME)),
        content = content,
    )
}

/**
 * One row of a menu.
 *
 * - An **action row** (the default) is a full-width outlined pill, 1.25 dp, its icon inside at the
 *   left. [opens] adds a › for a row that opens a screen, a dialog, a picker or a submenu;
 *   [destructive] lends the Delete role to frame, icon and label; a row that is not [enabled] is
 *   dimmed to 38 %.
 * - A **toggle row** ([checked] not null) is plain, 36 dp tall, with the outlined switch at the
 *   right. [onClick] flips it.
 * - A **choice row** ([choice]) is one of a list of pure choices — a sort order, a type, a weight:
 *   plain, with whatever [leadingIcon] marks the chosen one.
 *
 * [icon] and [role] say what the row does; an existing [leadingIcon] is drawn in the same place and
 * takes the role's colour. Same leading parameters as Material's `DropdownMenuItem`; [colors],
 * [contentPadding] and [interactionSource] are accepted and ignored.
 */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") colors: MenuItemColors? = null,
    @Suppress("UNUSED_PARAMETER") contentPadding: PaddingValues? = null,
    @Suppress("UNUSED_PARAMETER") interactionSource: MutableInteractionSource? = null,
    icon: ImageVector? = null,
    role: IconRole = IconRole.VIEW,
    opens: Boolean = false,
    destructive: Boolean = false,
    checked: Boolean? = null,
    choice: Boolean = false,
) {
    val iconRole = if (destructive) IconRole.DELETE else role
    fun Color.shown() = if (enabled) this else copy(alpha = DISABLED_ALPHA)
    val label = (if (destructive) role(UiRole.DELETE) else role(UiRole.MENU_LABEL)).shown()
    val glyph = iconColor(iconRole).shown()
    val lead: @Composable (() -> Unit)? = when {
        icon != null -> ({ Icon(icon, null, Modifier.size(20.dp), tint = glyph) })
        leadingIcon != null -> ({ CompositionLocalProvider(LocalContentColor provides glyph) { Box(Modifier.size(20.dp), Alignment.Center) { leadingIcon() } } })
        else -> null
    }
    val row = modifier.fillMaxWidth().sizeIn(minWidth = 112.dp, maxWidth = 320.dp)

    @Composable
    fun Label(weight: Modifier) {
        CompositionLocalProvider(LocalContentColor provides label) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal, color = label)) {
                Box(weight) { text() }
            }
        }
    }

    when {
        checked != null -> Row(
            row
                .heightIn(min = 36.dp)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
                // Lined up with what is inside the pills above: 8 dp to the pill, 14 dp inside it.
                .padding(start = 22.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead != null) { lead(); Spacer(Modifier.width(10.dp)) }
            Label(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ThemedSwitch(checked = checked, onCheckedChange = null, enabled = enabled, compact = true)
        }

        choice -> Row(
            row
                .heightIn(min = 40.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead != null) { lead(); Spacer(Modifier.width(10.dp)) }
            Label(Modifier.weight(1f))
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                CompositionLocalProvider(LocalContentColor provides label) { trailingIcon() }
            }
        }

        else -> {
            val frame = (if (destructive) role(UiRole.DELETE) else role(UiRole.MENU_ROW_FRAME)).shown()
            Box(row.padding(horizontal = 8.dp, vertical = 3.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 42.dp)
                        .clip(MENU_PILL)
                        .border(1.25.dp, frame, MENU_PILL)
                        .clickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = frame),
                            onClick = onClick,
                        )
                        .padding(start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lead != null) { lead(); Spacer(Modifier.width(10.dp)) }
                    Label(Modifier.weight(1f))
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(8.dp))
                        CompositionLocalProvider(LocalContentColor provides label) { trailingIcon() }
                    }
                    if (opens) {
                        Spacer(Modifier.width(8.dp))
                        Text("›", color = label, style = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

/** The line between two groups of menu rows: 1 dp, an opaque darker accent, its ends on the pills' edges. */
@Composable
fun MenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(1.dp)
            .background(role(UiRole.MENU_DIVIDER)),
    )
}

/** The plain line a menu opens with — a name, a count, a time. Not something to press. */
@Composable
fun MenuHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
        color = role(UiRole.MENU_LABEL),
        style = MaterialTheme.typography.labelMedium,
    )
}
