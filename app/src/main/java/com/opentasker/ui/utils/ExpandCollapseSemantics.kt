package com.opentasker.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.opentasker.app.R

/**
 * Makes a whole row behave as the disclosure control for the section under it.
 *
 * A bare `Modifier.clickable { expanded = !expanded }` gives a screen reader nothing but "double tap
 * to activate": no role, and no way to tell whether the section is already open, so the same
 * announcement is read whether the next tap opens or closes it. Seven cards across Setup, the run
 * log, the Inspector, the scene library and both automation lists had exactly that.
 *
 * The state description is what carries the open/closed state; the click label says which way the
 * next tap goes. This mirrors the hand-written semantics already on the run log's variable-change
 * inspector, which is where the idiom came from.
 */
@Composable
fun Modifier.expandCollapseToggle(expanded: Boolean, onToggle: () -> Unit): Modifier {
    val stateLabel = if (expanded) {
        stringResource(R.string.a11y_expanded)
    } else {
        stringResource(R.string.a11y_collapsed)
    }
    val actionLabel = if (expanded) {
        stringResource(R.string.action_collapse)
    } else {
        stringResource(R.string.action_expand)
    }
    return this
        .semantics { stateDescription = stateLabel }
        .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
}
