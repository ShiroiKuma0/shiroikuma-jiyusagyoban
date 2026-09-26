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
    val (stateResource, actionResource) = disclosureLabels(expanded)
    val stateLabel = stringResource(stateResource)
    val actionLabel = stringResource(actionResource)
    return this
        .semantics { stateDescription = stateLabel }
        .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
}

/**
 * The string resources a disclosure row reads out: its state now, then which way the next tap
 * goes. An open row is "expanded" and offers to collapse; a closed one is "collapsed" and offers to
 * expand. Getting either half backwards is the bug [expandCollapseToggle] exists to prevent, and
 * the pair is plain data, so it is tested on the JVM.
 */
internal fun disclosureLabels(expanded: Boolean): Pair<Int, Int> =
    if (expanded) {
        R.string.a11y_expanded to R.string.action_collapse
    } else {
        R.string.a11y_collapsed to R.string.action_expand
    }
