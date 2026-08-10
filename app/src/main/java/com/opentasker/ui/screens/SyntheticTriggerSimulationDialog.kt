package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.ContextType
import com.opentasker.core.engine.CooldownStore
import com.opentasker.core.engine.ExecutionAdmissionRegistry
import com.opentasker.core.engine.toExecutionAdmissionProfileLimits
import com.opentasker.core.engine.SyntheticContextResult
import com.opentasker.core.engine.SyntheticContextStatus
import com.opentasker.core.engine.SyntheticGateResult
import com.opentasker.core.engine.SyntheticTriggerSimulator
import com.opentasker.core.model.Profile
import com.opentasker.ui.theme.DesignSystem

@Composable
internal fun SyntheticTriggerSimulationDialog(
    profile: Profile,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val simulation = remember(profile) {
        val nowMs = System.currentTimeMillis()
        val remainingCooldownMs = CooldownStore(context).remaining(profile.id, nowMs)
        val cooldown = if (profile.cooldownSec <= 0 || remainingCooldownMs == 0L) {
            SyntheticGateResult.pass("No cooldown is currently blocking this profile.")
        } else {
            val seconds = ((remainingCooldownMs + 999L) / 1_000L).coerceAtLeast(1L)
            SyntheticGateResult.block("Cooldown has $seconds second(s) remaining.")
        }
        val admissionDecision = ExecutionAdmissionRegistry.preview(
            context = context,
            profileId = profile.id,
            profileLimits = profile.toExecutionAdmissionProfileLimits(),
        )
        val admission = SyntheticGateResult(
            accepted = admissionDecision.accepted,
            reason = admissionDecision.reason ?: "Admission rejected this run.",
        )
        SyntheticTriggerSimulator.simulate(
            profile = profile,
            nowMs = nowMs,
            cooldown = cooldown,
            admission = admission,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.synthetic_trigger_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 580.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(
                        stringResource(R.string.synthetic_trigger_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    SimulationGateCard(
                        title = stringResource(R.string.synthetic_trigger_profile_match),
                        accepted = simulation.profileMatched,
                        reason = simulation.profileReason,
                    )
                }
                item {
                    SimulationGateCard(
                        title = stringResource(R.string.synthetic_trigger_cooldown),
                        accepted = simulation.cooldown.accepted,
                        reason = simulation.cooldown.reason,
                    )
                }
                item {
                    SimulationGateCard(
                        title = stringResource(R.string.synthetic_trigger_admission),
                        accepted = simulation.admission.accepted,
                        reason = simulation.admission.reason,
                    )
                }
                item {
                    Text(
                        if (simulation.wouldTrigger) {
                            stringResource(R.string.synthetic_trigger_would_trigger)
                        } else {
                            stringResource(R.string.synthetic_trigger_would_not_trigger)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (simulation.wouldTrigger) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                item {
                    Text(
                        stringResource(R.string.synthetic_trigger_contexts, simulation.contexts.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (simulation.contexts.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.synthetic_trigger_no_contexts),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    items(simulation.contexts, key = { it.index }) { result ->
                        SyntheticContextCard(result)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun SimulationGateCard(
    title: String,
    accepted: Boolean,
    reason: String,
) {
    val color = if (accepted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(
                label = if (accepted) {
                    stringResource(R.string.synthetic_trigger_available)
                } else {
                    stringResource(R.string.synthetic_trigger_blocked)
                },
                color = color,
            )
        }
    }
}

@Composable
private fun SyntheticContextCard(result: SyntheticContextResult) {
    val passed = result.status == SyntheticContextStatus.PASSED
    val color = if (passed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.synthetic_trigger_context_number,
                            result.index + 1,
                            // The fork names context types inline rather than through string
                            // resources, so the label comes straight from the enum.
                            contextTypeLabel(result.spec.type),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.synthetic_trigger_template, result.template.description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    label = if (passed) {
                        stringResource(R.string.synthetic_trigger_passed)
                    } else {
                        stringResource(R.string.synthetic_trigger_blocked)
                    },
                    color = color,
                )
            }
            Text(
                stringResource(R.string.synthetic_trigger_source_event, result.event.type),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                result.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.displayMetadata.entries.forEach { (key, value) ->
                Text(
                    stringResource(R.string.synthetic_trigger_metadata, key, value),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun contextTypeLabel(type: ContextType): String = when (type) {
    ContextType.APPLICATION -> "Application"
    ContextType.TIME -> "Time"
    ContextType.DAY -> "Day"
    ContextType.LOCATION -> "Location"
    ContextType.STATE -> "Device state"
    ContextType.EVENT -> "Event"
    ContextType.PLUGIN -> "Plugin condition"
}
