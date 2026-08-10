package com.opentasker.ui.screens

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.storage.RestoreCandidate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.opentasker.core.permissions.OemBatteryGuidance
import com.opentasker.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.permissions.RuntimePermissionOutcome
import com.opentasker.core.permissions.RuntimePermissionRequestHistory
import com.opentasker.ui.theme.ThemeMode
import com.opentasker.ui.theme.ThemePreference
import kotlinx.coroutines.launch
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.power.ShizukuPowerState
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.scripting.TermuxScriptState
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.capabilities.SetupRequirementResolver
import com.opentasker.core.platform.PromotedOngoingNotificationSupport
import com.opentasker.core.contexts.PushTriggerTokenStore
import com.opentasker.core.engine.DirectBootTriggerStore
import com.opentasker.core.contexts.CompanionAssociationResult
import com.opentasker.core.contexts.CompanionDeviceAssociation
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

private enum class SetupSection {
    ENGINE,
    NEEDED,
    OPTIONAL,
    RELIABILITY,
}

private fun SetupSection.titleRes(): Int = when (this) {
    SetupSection.ENGINE -> R.string.setup_section_engine
    SetupSection.NEEDED -> R.string.setup_section_needed
    SetupSection.OPTIONAL -> R.string.setup_section_optional
    SetupSection.RELIABILITY -> R.string.setup_section_reliability
}

private data class PermissionSetupItem(
    val title: String,
    val body: String,
    val granted: Boolean,
    val actionLabel: String,
    val action: PermissionAction,
    val requiredFor: String,
    val optional: Boolean = false,
    val allowActionWhenGranted: Boolean = false,
    val section: SetupSection = SetupSection.ENGINE,
    val requirements: Set<SetupRequirement> = emptySet(),
)

data class BackupSetupState(
    val busy: Boolean,
    val latestBackupName: String? = null,
    val pendingRestore: Boolean = false,
    /** What the staged restore would install, so the pending banner is specific rather than generic. */
    val pendingRestoreSummary: RestoreCandidate? = null,
)

private sealed interface PermissionAction {
    data class RuntimePermission(val permission: String) : PermissionAction
    data class SettingsIntent(val intent: Intent) : PermissionAction
    data object ShizukuPermission : PermissionAction
    data class ShizukuKillSwitch(val enabled: Boolean) : PermissionAction
    /** Try each OEM settings component in order, falling back to a web guide URL. */
    data class OemSettings(
        val targets: List<OemBatteryGuidance.SettingsTarget>,
        val fallbackUrl: String,
    ) : PermissionAction
    data object None : PermissionAction
}

@Composable
fun PermissionOnboardingScreen(
    contentPadding: PaddingValues,
    onMessage: (String) -> Unit,
    backupState: BackupSetupState,
    onCreateBackup: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onCancelPendingRestore: () -> Unit = {},
    profiles: List<Profile> = emptyList(),
    tasks: List<Task> = emptyList(),
    globalFallbackTaskId: Long? = null,
    onGlobalFallbackTaskChange: (Long?) -> Unit = {},
) {
    val context = LocalContext.current
    val advancedProtectionEnabled by AdvancedProtectionReader.enabled.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionHistory = remember(context) { RuntimePermissionRequestHistory(context) }
    val permissionGrantedMessage = stringResource(R.string.permission_granted)
    val permissionDeniedRetryMessage = stringResource(R.string.permission_denied_retry)
    val permissionDeniedSettingsMessage = stringResource(R.string.permission_denied_settings)
    val shizukuPermissionRequestedMessage = stringResource(R.string.setup_shizuku_permission_requested)
    val shizukuPermissionFailedMessage = stringResource(R.string.setup_shizuku_permission_failed)
    val shizukuModeDisabledMessage = stringResource(R.string.setup_shizuku_mode_disabled)
    val shizukuModeEnabledMessage = stringResource(R.string.setup_shizuku_mode_enabled)
    var refreshTick by remember { mutableIntStateOf(0) }
    val directBootEnabled by DirectBootTriggerStore.observe(context).collectAsState(initial = false)
    val setupScope = rememberCoroutineScope()
    var pendingPermission by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.let { permission ->
            val shouldShowRationale = context.findActivity()
                ?.shouldShowRequestPermissionRationale(permission)
                ?: false
            when (permissionHistory.recordResult(permission, granted, shouldShowRationale).outcome) {
                RuntimePermissionOutcome.Granted -> onMessage(permissionGrantedMessage)
                RuntimePermissionOutcome.DeniedCanRetry -> onMessage(permissionDeniedRetryMessage)
                RuntimePermissionOutcome.SettingsRequired -> onMessage(permissionDeniedSettingsMessage)
            }
        }
        pendingPermission = null
        refreshTick++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val automationRequirements = remember(profiles, tasks) {
        SetupRequirementResolver.resolve(profiles, tasks)
    }
    val items = remember(context, refreshTick) { buildPermissionItems(context, permissionHistory) }
    val visibleItems = remember(items, automationRequirements) {
        items.filter { item ->
            item.section != SetupSection.NEEDED || item.requirements.any(automationRequirements::contains)
        }
    }
    val sectionItems = remember(visibleItems) {
        SetupSection.entries.associateWith { section ->
            visibleItems
                .filter { it.section == section }
                .sortedWith(compareBy<PermissionSetupItem> { it.granted }.thenBy { it.title })
        }
    }
    val requiredItems = remember(visibleItems) {
        visibleItems.filter { it.section == SetupSection.ENGINE || it.section == SetupSection.NEEDED }
    }
    val grantedCount = requiredItems.count { it.granted }
    val pendingCount = requiredItems.size - grantedCount
    val progress = if (requiredItems.isEmpty()) 0f else grantedCount.toFloat() / requiredItems.size.toFloat()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
                shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            modifier = Modifier.size(72.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize(),
                                color = if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface,
                                strokeWidth = 6.dp,
                            )
                            Text(
                                stringResource(R.string.setup_progress_percent, (progress * 100).toInt()),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.title_setup_checklist), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.setup_checklist_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            PermissionStatusPill(
                                if (pendingCount == 0) stringResource(R.string.status_ready) else stringResource(R.string.status_pending, pendingCount),
                                if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        PermissionMetric("$grantedCount", stringResource(R.string.status_ready), Modifier.weight(1f))
                        PermissionMetric("$pendingCount", stringResource(R.string.status_needs_setup), Modifier.weight(1f))
                    }
                    Text(
                        stringResource(R.string.setup_status_order),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { ThemeSetupCard() }

        item {
            DirectBootSetupCard(
                enabled = directBootEnabled,
                onEnabledChange = { enabled ->
                    setupScope.launch {
                        DirectBootTriggerStore.setEnabled(context, enabled)
                        refreshTick++
                    }
                },
            )
        }

        if (advancedProtectionEnabled) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.42f)),
                    shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.lg),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.setup_advanced_protection_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.setup_advanced_protection_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        item {
            BackupSetupCard(
                state = backupState,
                onCreateBackup = onCreateBackup,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onCancelPendingRestore = onCancelPendingRestore,
            )
        }

        item {
            GlobalFallbackTaskCard(
                taskId = globalFallbackTaskId,
                tasks = tasks,
                onTaskChange = onGlobalFallbackTaskChange,
            )
        }

        item { TermuxScriptAllowlistCard(onMessage) }
        item { PushTriggerSetupCard(onMessage) }
        item { CompanionSetupCard(onMessage) }
        item { LocaleGrantManagementCard(tasks = tasks, refreshKey = refreshTick) }

        SetupSection.entries.forEach { section ->
            val itemsForSection = sectionItems.getValue(section)
            if (itemsForSection.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(section.titleRes()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(itemsForSection, key = { it.title }) { item ->
                    val alreadyReadyMessage = stringResource(R.string.setup_item_already_ready, item.title)
                    PermissionSetupCard(
                        item = item,
                        onRunAction = {
                            when (val action = item.action) {
                        PermissionAction.None -> onMessage(alreadyReadyMessage)
                        is PermissionAction.RuntimePermission -> {
                            pendingPermission = action.permission
                            permissionHistory.recordRequest(action.permission)
                            permissionLauncher.launch(action.permission)
                        }
                        is PermissionAction.SettingsIntent -> openSettingsIntent(context, action.intent, onMessage)
                        is PermissionAction.OemSettings -> openOemSettings(context, action, onMessage)
                        PermissionAction.ShizukuPermission -> {
                            val requested = ShizukuPowerBackend.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                            onMessage(
                                if (requested) shizukuPermissionRequestedMessage
                                else shizukuPermissionFailedMessage,
                            )
                            refreshTick++
                        }
                        is PermissionAction.ShizukuKillSwitch -> {
                            ShizukuPowerBackend.setKillSwitchEnabled(context, action.enabled)
                            onMessage(
                                if (action.enabled) shizukuModeDisabledMessage
                                else shizukuModeEnabledMessage,
                            )
                            refreshTick++
                        }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalFallbackTaskCard(
    taskId: Long?,
    tasks: List<Task>,
    onTaskChange: (Long?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.setup_global_fallback_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.setup_global_fallback_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TaskActionFieldInput(
                label = stringResource(R.string.setup_global_fallback_label),
                hint = stringResource(R.string.setup_global_fallback_hint),
                value = taskId?.toString().orEmpty(),
                tasks = tasks,
                onChange = { onTaskChange(it.toLongOrNull()) },
            )
        }
    }
}

@Composable
private fun CompanionSetupCard(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    var refreshTick by remember { mutableIntStateOf(0) }
    var associations by remember(context, refreshTick) {
        mutableStateOf(CompanionDeviceAssociation.list(context))
    }
    val associatedMessage = stringResource(R.string.setup_companion_associated)
    val failureMessage = stringResource(R.string.setup_companion_failed)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        associations = CompanionDeviceAssociation.list(context)
        refreshTick++
        onMessage(associatedMessage)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.setup_companion_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.setup_companion_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (associations.isEmpty()) {
                Text(
                    stringResource(R.string.setup_companion_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                associations.forEach { association ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(association.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(
                            onClick = {
                                CompanionDeviceAssociation.disassociate(context, association)
                                associations = CompanionDeviceAssociation.list(context)
                                refreshTick++
                            },
                        ) {
                            Text(stringResource(R.string.setup_companion_revoke))
                        }
                    }
                }
            }
            Button(
                enabled = activity != null,
                onClick = {
                    val started = activity?.let {
                        CompanionDeviceAssociation.associate(it) { result ->
                            when (result) {
                                is CompanionAssociationResult.Found -> launcher.launch(
                                    IntentSenderRequest.Builder(result.intentSender).build(),
                                )
                                is CompanionAssociationResult.Created -> {
                                    associations = CompanionDeviceAssociation.list(context)
                                    refreshTick++
                                    onMessage(associatedMessage)
                                }
                                is CompanionAssociationResult.Failed -> onMessage(
                                    result.message.ifBlank { failureMessage },
                                )
                            }
                        }
                    } ?: false
                    if (!started) onMessage(failureMessage)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.setup_companion_associate))
            }
        }
    }
}

@Composable
private fun PushTriggerSetupCard(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val token = remember(context) { PushTriggerTokenStore(context).token() }
    val tokenLabel = stringResource(R.string.setup_push_title)
    val copiedMessage = stringResource(R.string.setup_push_copied)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.setup_push_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.setup_push_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.setup_push_token, token),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(tokenLabel, token))
                    onMessage(copiedMessage)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.setup_push_copy))
            }
        }
    }
}

@Composable
private fun LocaleGrantManagementCard(
    tasks: List<Task>,
    refreshKey: Int,
) {
    val context = LocalContext.current
    val store = remember(context) { LocaleGrantStore(context) }
    var grants by remember(context, refreshKey) { mutableStateOf(store.grants()) }
    val taskNames = remember(tasks) { tasks.associateBy(Task::id) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.setup_locale_grants_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.setup_locale_grants_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (grants.isEmpty()) {
                Text(
                    stringResource(R.string.setup_locale_grants_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                grants.forEach { grant ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                taskNames[grant.taskId]?.name
                                    ?: stringResource(R.string.setup_locale_grant_unknown_task, grant.taskId),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.setup_locale_grant_task_id, grant.taskId),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                store.revoke(grant.token)
                                grants = grants.filterNot { it.token == grant.token }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.setup_locale_grant_revoke))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentMode by ThemePreference.observe(context).collectAsState(initial = ThemeMode.System)
    val onSelectMode: (ThemeMode) -> Unit = { mode ->
        scope.launch { ThemePreference.set(context, mode) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.setup_theme_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.setup_theme_helper_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.chunked(2).forEach { rowModes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowModes.forEach { mode ->
                            ThemeChoice(
                                mode = mode,
                                selected = mode == currentMode,
                                onSelect = onSelectMode,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectBootSetupCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val stateDescription = stringResource(
        if (enabled) R.string.setup_direct_boot_enabled else R.string.setup_direct_boot_disabled,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.setup_direct_boot_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.setup_direct_boot_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stateDescription,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.semantics { this.stateDescription = stateDescription },
            )
        }
    }
}

@Composable
private fun ThemeChoice(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (mode) {
        ThemeMode.System -> stringResource(R.string.theme_system)
        ThemeMode.Dark -> stringResource(R.string.theme_dark)
        ThemeMode.Light -> stringResource(R.string.theme_light)
        ThemeMode.HighContrast -> stringResource(R.string.theme_high_contrast)
    }
    val accent = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val selectionDescription = stringResource(
        if (selected) R.string.a11y_option_selected else R.string.a11y_option_not_selected,
        label,
    )
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { if (!selected) onSelect(mode) },
            )
            .semantics {
                this.selected = selected
                stateDescription = selectionDescription
            },
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) 0.58f else 0.72f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = selectionDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BackupSetupCard(
    state: BackupSetupState,
    onCreateBackup: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onCancelPendingRestore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.setup_backup_label), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.setup_backup_helper_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BackupStateBanner(state)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onCreateBackup,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (state.busy) stringResource(R.string.setup_backup_working) else stringResource(R.string.setup_backup_create))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onExportBackup,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Text(stringResource(R.string.action_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onImportBackup,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Text(stringResource(R.string.action_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.pendingRestore) {
                    OutlinedButton(
                        onClick = onCancelPendingRestore,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.setup_backup_restore_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupStateBanner(state: BackupSetupState) {
    val color = when {
        state.pendingRestore -> MaterialTheme.colorScheme.primary
        state.latestBackupName != null -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val title = when {
        state.pendingRestore -> stringResource(R.string.setup_backup_restore_staged)
        state.latestBackupName != null -> stringResource(R.string.setup_backup_available)
        else -> stringResource(R.string.setup_backup_none)
    }
    val body = when {
        state.pendingRestore -> state.pendingRestoreSummary?.let { summary ->
            summary.error?.let { error -> stringResource(R.string.setup_backup_restore_unreadable, error) }
                ?: stringResource(
                    R.string.setup_backup_restore_summary,
                    summary.sourceLabel,
                    summary.schemaVersion,
                    summary.profileCount,
                    summary.taskCount,
                    summary.sceneCount,
                )
        } ?: stringResource(R.string.setup_backup_restore_body)
        state.latestBackupName != null -> stringResource(R.string.setup_backup_latest, state.latestBackupName)
        else -> stringResource(R.string.setup_backup_none_body)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (state.latestBackupName != null || state.pendingRestore) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PermissionSetupCard(
    item: PermissionSetupItem,
    onRunAction: () -> Unit,
) {
    val stateLabel = when {
        item.optional && item.granted -> stringResource(R.string.status_detected)
        item.optional -> stringResource(R.string.status_optional)
        item.granted -> stringResource(R.string.status_ready)
        else -> stringResource(R.string.status_needs_setup)
    }
    val stateColor = when {
        item.optional && item.granted -> MaterialTheme.colorScheme.tertiary
        item.optional -> MaterialTheme.colorScheme.secondary
        item.granted -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.granted || item.optional) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (item.granted || item.optional) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f) else MaterialTheme.colorScheme.error.copy(alpha = 0.26f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = stateColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        stateColor.copy(alpha = 0.28f),
                    ),
                ) {
                    Box(modifier = Modifier.padding(9.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            when {
                                item.granted -> Icons.Filled.CheckCircle
                                item.optional -> Icons.Filled.Info
                                else -> Icons.Filled.Error
                            },
                            contentDescription = when {
                                item.granted -> stringResource(R.string.status_granted)
                                item.optional -> stringResource(R.string.status_optional)
                                else -> stringResource(R.string.status_required)
                            },
                            tint = stateColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = stateColor,
                    )
                }
            }
            Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PermissionRequirement(label = if (item.optional) stringResource(R.string.setup_optional_requirement, item.requiredFor) else item.requiredFor)
            if (!item.granted) {
                Button(onClick = onRunAction, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(item.actionLabel)
                }
            } else if (item.allowActionWhenGranted) {
                OutlinedButton(onClick = onRunAction, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.setup_review_settings))
                }
            }
        }
    }
}

@Composable
private fun PermissionMetric(value: String, label: String, modifier: Modifier = Modifier) {
    SummaryMetric(value = value, label = label, modifier = modifier)
}

@Composable
private fun PermissionStatusPill(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun PermissionRequirement(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun buildPermissionItems(
    context: Context,
    permissionHistory: RuntimePermissionRequestHistory,
): List<PermissionSetupItem> {
    val shizukuStatus = ShizukuPowerBackend.inspect(context)
    val shizukuActionLabel = context.getString(when (shizukuStatus.state) {
        ShizukuPowerState.NotInstalled -> R.string.setup_action_open_setup_guide
        ShizukuPowerState.ManagerInstalled -> R.string.setup_action_open_shizuku_settings
        ShizukuPowerState.PermissionNeeded -> R.string.setup_action_request_permission
        ShizukuPowerState.BackendUnavailable,
        ShizukuPowerState.Ready,
        -> R.string.setup_action_disable_power_mode
        ShizukuPowerState.Disabled -> R.string.setup_action_enable_power_mode
    })
    val shizukuSummary = context.getString(when (shizukuStatus.state) {
        ShizukuPowerState.NotInstalled -> R.string.setup_shizuku_status_not_installed
        ShizukuPowerState.ManagerInstalled -> R.string.setup_shizuku_status_manager_stopped
        ShizukuPowerState.PermissionNeeded -> R.string.setup_shizuku_status_permission_needed
        ShizukuPowerState.BackendUnavailable -> R.string.setup_shizuku_status_transport_unavailable
        ShizukuPowerState.Ready -> R.string.setup_shizuku_status_ready
        ShizukuPowerState.Disabled -> R.string.setup_shizuku_status_disabled
    })
    val shizukuAction = when (shizukuStatus.state) {
        ShizukuPowerState.NotInstalled -> PermissionAction.SettingsIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuPowerBackend.SETUP_URL)),
        )
        ShizukuPowerState.ManagerInstalled -> PermissionAction.SettingsIntent(
            packageDetailsIntent(ShizukuPowerBackend.MANAGER_PACKAGE),
        )
        ShizukuPowerState.PermissionNeeded -> PermissionAction.ShizukuPermission
        ShizukuPowerState.BackendUnavailable,
        ShizukuPowerState.Ready,
        -> PermissionAction.ShizukuKillSwitch(enabled = true)
        ShizukuPowerState.Disabled -> PermissionAction.ShizukuKillSwitch(enabled = false)
    }
    val termuxStatus = TermuxScriptBackend.inspect(context)
    val termuxSummary = context.getString(when (termuxStatus.state) {
        TermuxScriptState.TermuxMissing -> R.string.setup_termux_status_missing
        TermuxScriptState.VersionUnsupported -> R.string.setup_termux_status_version_unsupported
        TermuxScriptState.PermissionRequired -> R.string.setup_termux_status_permission_needed
        TermuxScriptState.Ready -> R.string.setup_termux_status_ready
    })
    val oem = OemBatteryGuidance.forDevice(Build.MANUFACTURER, Build.BRAND)
    val request = context.getString(R.string.setup_action_request)
    val openSettings = context.getString(R.string.setup_action_open_settings)
    val promotedSupported = PromotedOngoingNotificationSupport.isPlatformSupported()
    val promotedGranted = !promotedSupported || (
        context.getSystemService(NotificationManager::class.java)
            ?.let { manager -> PromotedOngoingNotificationSupport.canPostPromotedNotifications(manager) }
            == true
        )
    return listOfNotNull(
        PermissionSetupItem(
            title = context.getString(R.string.setup_notifications_card_title),
            body = context.getString(R.string.setup_notifications_card_body),
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            actionLabel = request,
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionAction.None
            },
            requiredFor = context.getString(R.string.setup_notifications_required_for),
        ),
        if (promotedSupported) PermissionSetupItem(
            title = context.getString(R.string.setup_promoted_notifications_title),
            body = context.getString(R.string.setup_promoted_notifications_body),
            granted = promotedGranted,
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            ),
            requiredFor = context.getString(R.string.setup_promoted_notifications_required_for),
            optional = true,
            section = SetupSection.RELIABILITY,
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_exact_alarm_card_title),
            body = context.getString(R.string.setup_exact_alarm_card_body),
            granted = ExactAlarmSupport.canScheduleExactAlarms(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(ExactAlarmSupport.settingsIntent(context)),
            requiredFor = context.getString(R.string.setup_exact_alarm_required_for),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_battery_title),
            body = context.getString(R.string.setup_battery_card_body, oem.summary),
            granted = ignoresBatteryOptimizations(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            requiredFor = context.getString(R.string.setup_battery_required_for),
            section = SetupSection.RELIABILITY,
        ),
        if (oem.needsExtraSteps) PermissionSetupItem(
            title = context.getString(R.string.setup_oem_guidance_title, oem.oemName),
            body = context.getString(
                R.string.setup_oem_guidance_body,
                oem.oemName,
                context.getString(oem.riskLevel.labelRes()),
                oem.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"),
                oem.dontKillMyAppUrl,
            ),
            granted = false,
            actionLabel = if (oem.settingsTargets.isNotEmpty()) {
                context.getString(R.string.setup_action_open_oem_settings, oem.oemName)
            } else {
                context.getString(R.string.setup_action_open_dontkillmyapp)
            },
            action = PermissionAction.OemSettings(oem.settingsTargets, oem.dontKillMyAppUrl),
            requiredFor = context.getString(R.string.setup_oem_required_for, oem.oemName),
            optional = true,
            section = SetupSection.RELIABILITY,
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_usage_card_title),
            body = context.getString(R.string.setup_usage_card_body),
            granted = UsageAccess.hasUsageStatsAccess(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            requiredFor = context.getString(R.string.setup_usage_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.USAGE_ACCESS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_notification_access_title),
            body = context.getString(R.string.setup_notification_access_body),
            granted = hasNotificationListenerAccess(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            requiredFor = context.getString(R.string.setup_notification_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.NOTIFICATION_ACCESS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_calendar_access_title),
            body = context.getString(R.string.setup_calendar_access_body),
            granted = hasPermission(context, Manifest.permission.READ_CALENDAR),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CALENDAR),
            requiredFor = context.getString(R.string.setup_calendar_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.CALENDAR),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_contacts_access_title),
            body = context.getString(R.string.setup_contacts_access_body),
            granted = hasPermission(context, Manifest.permission.READ_CONTACTS),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CONTACTS),
            requiredFor = context.getString(R.string.setup_contacts_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.CONTACTS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_overlay_access_title),
            body = context.getString(R.string.setup_overlay_access_body),
            granted = Settings.canDrawOverlays(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = context.getString(R.string.setup_overlay_access_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.OVERLAY),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_write_settings_title),
            body = context.getString(R.string.setup_write_settings_body),
            granted = Settings.System.canWrite(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = context.getString(R.string.setup_write_settings_required_for),
            optional = true,
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.WRITE_SETTINGS),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_foreground_location_title),
            body = context.getString(R.string.setup_foreground_location_body),
            granted = hasAnyLocationPermission(context),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
            requiredFor = context.getString(R.string.setup_foreground_location_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.FOREGROUND_LOCATION),
        ),
        if (Build.VERSION.SDK_INT >= 29) PermissionSetupItem(
            title = context.getString(R.string.setup_activity_recognition_title),
            body = context.getString(R.string.setup_activity_recognition_body),
            granted = hasPermission(context, Manifest.permission.ACTIVITY_RECOGNITION),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.ACTIVITY_RECOGNITION),
            requiredFor = context.getString(R.string.setup_activity_recognition_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.PHYSICAL_ACTIVITY),
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_nearby_wifi_title),
            body = context.getString(R.string.setup_nearby_wifi_body),
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES),
            actionLabel = request,
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                PermissionAction.None
            },
            requiredFor = context.getString(R.string.setup_nearby_wifi_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.NEARBY_WIFI),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_background_location_title),
            body = context.getString(if (Build.VERSION.SDK_INT >= 30) R.string.setup_background_location_body_modern else R.string.setup_background_location_body_legacy),
            granted = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            actionLabel = context.getString(R.string.action_open_app_settings),
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = context.getString(R.string.setup_background_location_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.BACKGROUND_LOCATION),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_bluetooth_title),
            body = context.getString(R.string.setup_bluetooth_body),
            granted = Build.VERSION.SDK_INT < 31 || hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT),
            actionLabel = request,
            action = if (Build.VERSION.SDK_INT >= 31) {
                PermissionAction.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                PermissionAction.None
            },
            requiredFor = context.getString(R.string.setup_bluetooth_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.BLUETOOTH),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_screen_recording_title),
            body = context.getString(R.string.setup_screen_recording_body),
            granted = Build.VERSION.SDK_INT < 35 || hasPermission(context, Manifest.permission.DETECT_SCREEN_RECORDING),
            actionLabel = context.getString(R.string.status_ready),
            action = PermissionAction.None,
            requiredFor = context.getString(R.string.setup_screen_recording_required_for),
            optional = true,
            section = SetupSection.OPTIONAL,
            requirements = setOf(SetupRequirement.SCREEN_RECORDING),
        ),
        if (Build.VERSION.SDK_INT >= ANDROID_17_API) PermissionSetupItem(
            title = context.getString(R.string.setup_local_network_title),
            body = context.getString(R.string.setup_local_network_body),
            granted = hasPermission(context, "android.permission.ACCESS_LOCAL_NETWORK"),
            actionLabel = request,
            action = PermissionAction.RuntimePermission("android.permission.ACCESS_LOCAL_NETWORK"),
            requiredFor = context.getString(R.string.setup_local_network_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.LOCAL_NETWORK),
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_sms_title),
            body = context.getString(R.string.setup_sms_body),
            granted = hasPermission(context, Manifest.permission.SEND_SMS),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.SEND_SMS),
            requiredFor = context.getString(R.string.setup_sms_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.SMS),
        ) else null,
        if (BuildConfig.SMS_RECEIVE_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_sms_receive_title),
            body = context.getString(R.string.setup_sms_receive_body),
            granted = hasPermission(context, Manifest.permission.RECEIVE_SMS),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.RECEIVE_SMS),
            requiredFor = context.getString(R.string.setup_sms_receive_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.SMS),
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = context.getString(R.string.setup_phone_state_title),
            body = context.getString(R.string.setup_phone_state_body),
            granted = hasPermission(context, Manifest.permission.READ_PHONE_STATE),
            actionLabel = request,
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_PHONE_STATE),
            requiredFor = context.getString(R.string.setup_phone_state_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.PHONE_STATE),
        ) else null,
        PermissionSetupItem(
            title = context.getString(R.string.setup_dnd_title),
            body = context.getString(R.string.setup_dnd_body),
            granted = hasNotificationPolicyAccess(context),
            actionLabel = openSettings,
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            requiredFor = context.getString(R.string.setup_dnd_required_for),
            section = SetupSection.NEEDED,
            requirements = setOf(SetupRequirement.DND),
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_shizuku_title),
            body = shizukuSummary,
            granted = shizukuStatus.isReady,
            actionLabel = shizukuActionLabel,
            action = shizukuAction,
            requiredFor = context.getString(R.string.setup_shizuku_required_for),
            optional = true,
            allowActionWhenGranted = true,
            section = SetupSection.OPTIONAL,
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_termux_title),
            body = context.getString(R.string.setup_termux_body, termuxSummary),
            granted = termuxStatus.isReady,
            actionLabel = when (termuxStatus.state) {
                TermuxScriptState.PermissionRequired -> request
                TermuxScriptState.Ready -> context.getString(R.string.action_open_app_settings)
                TermuxScriptState.TermuxMissing,
                TermuxScriptState.VersionUnsupported,
                -> context.getString(R.string.setup_action_open_setup_guide)
            },
            action = when (termuxStatus.state) {
                TermuxScriptState.PermissionRequired -> PermissionAction.RuntimePermission(TermuxScriptBackend.RUN_COMMAND_PERMISSION)
                TermuxScriptState.Ready -> PermissionAction.SettingsIntent(packageDetailsIntent(TermuxScriptBackend.TERMUX_PACKAGE))
                TermuxScriptState.TermuxMissing,
                TermuxScriptState.VersionUnsupported,
                -> PermissionAction.SettingsIntent(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxScriptBackend.SETUP_URL)))
            },
            requiredFor = context.getString(R.string.setup_termux_required_for),
            optional = true,
            section = SetupSection.OPTIONAL,
        ),
        PermissionSetupItem(
            title = context.getString(R.string.setup_app_visibility_title),
            body = context.getString(R.string.setup_app_visibility_body),
            granted = true,
            actionLabel = context.getString(R.string.status_ready),
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = context.getString(R.string.setup_app_visibility_required_for),
            allowActionWhenGranted = true,
        ),
    ).map { item -> item.withRuntimePermissionRecovery(context, permissionHistory) }
}

private fun PermissionSetupItem.withRuntimePermissionRecovery(
    context: Context,
    history: RuntimePermissionRequestHistory,
): PermissionSetupItem {
    val runtimePermission = action as? PermissionAction.RuntimePermission ?: return this
    if (granted) {
        history.clear(runtimePermission.permission)
        return this
    }
    if (!history.requiresSettings(runtimePermission.permission)) return this
    return copy(
        body = context.getString(R.string.setup_body_with_recovery, body, context.getString(R.string.permission_denied_settings_body)),
        actionLabel = context.getString(R.string.action_open_app_settings),
        action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
    )
}

private const val ANDROID_17_API = 37
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4107

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasAnyLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun ignoresBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun hasNotificationListenerAccess(context: Context): Boolean {
    val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName, ignoreCase = true) == true
}

private fun hasNotificationPolicyAccess(context: Context): Boolean {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    return notificationManager.isNotificationPolicyAccessGranted
}

private fun appDetailsIntent(context: Context): Intent =
    packageDetailsIntent(context.packageName)

private fun packageDetailsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openSettingsIntent(context: Context, intent: Intent, onMessage: (String) -> Unit) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ex: ActivityNotFoundException) {
        onMessage(context.getString(R.string.setup_settings_unavailable, ex.message ?: context.getString(R.string.setup_error_no_handler)))
    } catch (ex: SecurityException) {
        onMessage(context.getString(R.string.setup_settings_open_failed, ex.message ?: context.getString(R.string.setup_error_permission_denied)))
    }
}

private fun OemBatteryGuidance.RiskLevel.labelRes(): Int = when (this) {
    OemBatteryGuidance.RiskLevel.LOW -> R.string.setup_risk_low
    OemBatteryGuidance.RiskLevel.MEDIUM -> R.string.setup_risk_medium
    OemBatteryGuidance.RiskLevel.HIGH -> R.string.setup_risk_high
    OemBatteryGuidance.RiskLevel.SEVERE -> R.string.setup_risk_severe
}

/**
 * Try each OEM autostart/background settings component in order. OEM component names are fragile and
 * vary across versions, so every failure falls through to the next candidate and finally to the
 * device's dontkillmyapp.com page in a browser.
 */
private fun openOemSettings(context: Context, action: PermissionAction.OemSettings, onMessage: (String) -> Unit) {
    for (target in action.targets) {
        val intent = Intent().apply {
            setClassName(target.packageName, target.className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Component not present on this build; try the next candidate.
        } catch (_: SecurityException) {
            // Some OEM screens are not exported; try the next candidate.
        }
    }
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(action.fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(fallback)
        if (action.targets.isNotEmpty()) {
            onMessage(context.getString(R.string.setup_oem_fallback_opened))
        }
    } catch (ex: ActivityNotFoundException) {
        onMessage(context.getString(R.string.setup_oem_guide_unavailable, ex.message ?: context.getString(R.string.setup_error_no_handler)))
    }
}
