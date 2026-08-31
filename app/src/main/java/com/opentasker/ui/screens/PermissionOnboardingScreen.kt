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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import com.opentasker.core.storage.ConfigurationSnapshotPolicy
import com.opentasker.core.storage.ConfigurationSnapshotStatus
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.storage.RestoreCandidate
import androidx.compose.runtime.collectAsState
import com.opentasker.core.permissions.OemBatteryGuidance
import com.opentasker.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.permissions.RuntimePermissionOutcome
import com.opentasker.core.permissions.RuntimePermissionRequestHistory
import com.opentasker.ui.theme.ThemeMode
import com.opentasker.ui.theme.ThemePreference
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.launch
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.power.ShizukuPowerState
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.scripting.TermuxScriptState
import androidx.compose.ui.platform.testTag
import com.opentasker.core.capabilities.SetupRequirement
import com.opentasker.core.capabilities.SetupRequirementResolver
import com.opentasker.core.platform.PromotedOngoingNotificationSupport
import com.opentasker.core.contexts.PushTriggerTokenStore
import com.opentasker.core.contexts.UnifiedPushConnector
import com.opentasker.core.contexts.UnifiedPushEndpointStore
import com.opentasker.core.contexts.UnifiedPushRegistrationState
import com.opentasker.core.contexts.UnifiedPushRegistrationStatus
import com.opentasker.core.engine.DirectBootTriggerStore
import com.opentasker.core.contexts.CompanionAssociation
import com.opentasker.core.contexts.CompanionAssociationResult
import com.opentasker.core.contexts.CompanionDeviceAssociation
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.plugins.locale.LocaleGrant
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.updates.UpdateCheckAvailability
import com.opentasker.core.updates.UpdateCheckSettings
import com.opentasker.core.updates.UpdateCheckState
import com.opentasker.core.updates.UpdateCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

internal const val SETUP_FOCUS_BANNER_TAG = "setup_focus_banner"

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
    val snapshotPolicy: ConfigurationSnapshotPolicy = ConfigurationSnapshotPolicy(),
    val snapshotStatus: ConfigurationSnapshotStatus = ConfigurationSnapshotStatus(),
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

private class PermissionOnboardingViewModel(appContext: Context) : ViewModel() {
    private val context = appContext.applicationContext

    val permissionHistory = RuntimePermissionRequestHistory(context)

    private val refreshTick = MutableStateFlow(0L)
    val permissionItems: StateFlow<List<PermissionSetupItem>> = refreshTick
        .map { buildPermissionItems(context, permissionHistory) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val directBootEnabled: StateFlow<Boolean> = DirectBootTriggerStore.observe(context)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val themeMode: StateFlow<ThemeMode> = ThemePreference.observe(context)
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    private val _updateCheckState = MutableStateFlow(
        if (UpdateCheckAvailability.isAvailable()) UpdateCheckSettings(context).load() else UpdateCheckState(),
    )
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()

    private val _associations = MutableStateFlow<List<CompanionAssociation>>(emptyList())
    val associations: StateFlow<List<CompanionAssociation>> = _associations.asStateFlow()

    private val _pushToken = MutableStateFlow("")
    val pushToken: StateFlow<String> = _pushToken.asStateFlow()

    private val _pushRegistration = MutableStateFlow(UnifiedPushRegistrationState())
    val pushRegistration: StateFlow<UnifiedPushRegistrationState> = _pushRegistration.asStateFlow()

    private val _localeGrants = MutableStateFlow<List<LocaleGrant>>(emptyList())
    val localeGrants: StateFlow<List<LocaleGrant>> = _localeGrants.asStateFlow()

    private var externalRefreshJob: Job? = null

    init {
        refreshExternalState()
    }

    fun refresh() {
        refreshTick.update { it + 1L }
        refreshExternalState()
        refreshUpdateCheckState()
    }

    fun setDirectBootEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            DirectBootTriggerStore.setEnabled(context, enabled)
            refresh()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch(Dispatchers.IO) {
            ThemePreference.set(context, mode)
        }
    }

    fun setUpdateChecksEnabled(enabled: Boolean) {
        if (!UpdateCheckAvailability.isAvailable()) return
        viewModelScope.launch(Dispatchers.IO) {
            val settings = UpdateCheckSettings(context)
            settings.setEnabled(enabled)
            UpdateCheckWorker.sync(context)
            _updateCheckState.value = settings.load()
        }
    }

    fun refreshAssociations() {
        viewModelScope.launch(Dispatchers.IO) {
            _associations.value = CompanionDeviceAssociation.list(context)
        }
    }

    fun revokeLocaleGrant(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val store = LocaleGrantStore(context)
            store.revoke(token)
            _localeGrants.value = store.grants()
        }
    }

    fun chooseUnifiedPushDistributor(activityContext: Context, onResult: (Boolean) -> Unit) {
        UnifiedPushConnector.chooseDistributor(activityContext) { selected ->
            refreshExternalState()
            onResult(selected)
        }
    }

    fun registerUnifiedPush(activityContext: Context, onResult: (Boolean) -> Unit) {
        UnifiedPushConnector.register(activityContext) { requested ->
            refreshExternalState()
            onResult(requested)
        }
    }

    fun unregisterUnifiedPush() {
        UnifiedPushConnector.unregister(context)
        refreshExternalState()
    }

    private fun refreshExternalState() {
        externalRefreshJob?.cancel()
        externalRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            _associations.value = CompanionDeviceAssociation.list(context)
            _pushToken.value = PushTriggerTokenStore(context).token()
            _pushRegistration.value = UnifiedPushEndpointStore(context).state()
            _localeGrants.value = LocaleGrantStore(context).grants()
        }
    }

    private fun refreshUpdateCheckState() {
        if (!UpdateCheckAvailability.isAvailable()) {
            _updateCheckState.value = UpdateCheckState()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _updateCheckState.value = UpdateCheckSettings(context).load()
        }
    }
}

private class PermissionOnboardingViewModelFactory(
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PermissionOnboardingViewModel::class.java)) {
            return PermissionOnboardingViewModel(appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
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
    onSnapshotPolicyChanged: (ConfigurationSnapshotPolicy) -> Unit = {},
    onSnapshotDestinationSelected: (Uri, CharArray, Boolean) -> Unit = { _, passphrase, _ ->
        passphrase.fill('\u0000')
    },
    profiles: List<Profile> = emptyList(),
    tasks: List<Task> = emptyList(),
    globalFallbackTaskId: Long? = null,
    onGlobalFallbackTaskChange: (Long?) -> Unit = {},
    settingsOnly: Boolean = false,
    onRunOnboardingAgain: (() -> Unit)? = null,
    focusRequirements: Set<SetupRequirement> = emptySet(),
) {
    val context = LocalContext.current
    val viewModelFactory = remember(context) {
        PermissionOnboardingViewModelFactory(context.applicationContext)
    }
    val setupViewModel: PermissionOnboardingViewModel = viewModel(factory = viewModelFactory)
    val advancedProtectionEnabled by AdvancedProtectionReader.enabled.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionHistory = setupViewModel.permissionHistory
    val items by setupViewModel.permissionItems.collectAsState()
    val directBootEnabled by setupViewModel.directBootEnabled.collectAsState()
    val themeMode by setupViewModel.themeMode.collectAsState()
    val updateCheckState by setupViewModel.updateCheckState.collectAsState()
    val associations by setupViewModel.associations.collectAsState()
    val pushToken by setupViewModel.pushToken.collectAsState()
    val pushRegistration by setupViewModel.pushRegistration.collectAsState()
    val localeGrants by setupViewModel.localeGrants.collectAsState()
    val permissionGrantedMessage = stringResource(R.string.permission_granted)
    val permissionDeniedRetryMessage = stringResource(R.string.permission_denied_retry)
    val permissionDeniedSettingsMessage = stringResource(R.string.permission_denied_settings)
    val shizukuPermissionRequestedMessage = stringResource(R.string.setup_shizuku_permission_requested)
    val shizukuPermissionFailedMessage = stringResource(R.string.setup_shizuku_permission_failed)
    val shizukuModeDisabledMessage = stringResource(R.string.setup_shizuku_mode_disabled)
    val shizukuModeEnabledMessage = stringResource(R.string.setup_shizuku_mode_enabled)
    val pushDistributorSelectedMessage = stringResource(R.string.setup_push_distributor_selected)
    val pushDistributorUnavailableMessage = stringResource(R.string.setup_push_distributor_unavailable)
    val pushRegistrationRequestedMessage = stringResource(R.string.setup_push_registration_requested)
    val pushRegistrationFailedMessage = stringResource(R.string.setup_push_registration_failed)
    val pushUnregisteredMessage = stringResource(R.string.setup_push_unregistered)
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
        setupViewModel.refresh()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                setupViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val automationRequirements = remember(profiles, tasks) {
        SetupRequirementResolver.resolve(profiles, tasks)
    }
    // A template installed from onboarding scopes Setup to the grants that template actually
    // needs, so a first-time user sees the two rows standing between them and a working
    // automation rather than the whole checklist. Clearing the focus restores the full list.
    var focusCleared by rememberSaveable(focusRequirements) { mutableStateOf(false) }
    val activeFocus = if (focusCleared) emptySet() else focusRequirements
    val visibleItems = remember(items, automationRequirements, activeFocus) {
        items.filter { item ->
            val neededHere = item.section != SetupSection.NEEDED ||
                item.requirements.any(automationRequirements::contains)
            if (activeFocus.isEmpty()) {
                neededHere
            } else {
                item.requirements.any(activeFocus::contains)
            }
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
        if (activeFocus.isNotEmpty()) item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag(SETUP_FOCUS_BANNER_TAG),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(DesignSystem.Radii.md),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.setup_focus_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.setup_focus_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { focusCleared = true }) {
                        Text(stringResource(R.string.setup_focus_show_all))
                    }
                }
            }
        }
        if (!settingsOnly) item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(DesignSystem.Radii.md),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.setup_progress_ready_count, grantedCount, requiredItems.size),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.setup_checklist_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            PermissionStatusPill(
                                if (pendingCount == 0) stringResource(R.string.status_ready) else stringResource(R.string.status_pending, pendingCount),
                                if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            stringResource(R.string.setup_progress_percent, (progress * 100).toInt()),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    Text(
                        stringResource(R.string.setup_status_order),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (settingsOnly) {
            item { SettingsSectionLabel(stringResource(R.string.settings_section_general)) }
            if (UpdateCheckAvailability.isAvailable()) {
                item {
                    UpdateCheckSetupCard(
                        state = updateCheckState,
                        onEnabledChange = setupViewModel::setUpdateChecksEnabled,
                        onOpenRelease = { url ->
                            openSettingsIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), onMessage)
                        },
                    )
                }
            }
            item {
                ThemeSetupCard(
                    currentMode = themeMode,
                    onSelectMode = setupViewModel::setThemeMode,
                )
            }
            item {
                DirectBootSetupCard(
                    enabled = directBootEnabled,
                    onEnabledChange = setupViewModel::setDirectBootEnabled,
                )
            }
            onRunOnboardingAgain?.let { runAgain ->
                item { RunOnboardingAgainCard(onRunAgain = runAgain) }
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
            item { SettingsSectionLabel(stringResource(R.string.settings_section_data_recovery)) }
            item {
                BackupSetupCard(
                    state = backupState,
                    onCreateBackup = onCreateBackup,
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup,
                    onCancelPendingRestore = onCancelPendingRestore,
                    onSnapshotPolicyChanged = onSnapshotPolicyChanged,
                    onSnapshotDestinationSelected = onSnapshotDestinationSelected,
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
            item { SettingsSectionLabel(stringResource(R.string.settings_section_integrations)) }
            item {
                PushTriggerSetupCard(
                    token = pushToken,
                    registration = pushRegistration,
                    onChooseDistributor = {
                        setupViewModel.chooseUnifiedPushDistributor(context) { selected ->
                            onMessage(
                                if (selected) pushDistributorSelectedMessage else pushDistributorUnavailableMessage,
                            )
                        }
                    },
                    onRegister = {
                        setupViewModel.registerUnifiedPush(context) { requested ->
                            onMessage(
                                if (requested) pushRegistrationRequestedMessage else pushRegistrationFailedMessage,
                            )
                        }
                    },
                    onUnregister = {
                        setupViewModel.unregisterUnifiedPush()
                        onMessage(pushUnregisteredMessage)
                    },
                    onMessage = onMessage,
                )
            }
            item {
                CompanionSetupCard(
                    associations = associations,
                    onRefresh = setupViewModel::refreshAssociations,
                    onMessage = onMessage,
                )
            }
            item {
                LocaleGrantManagementCard(
                    tasks = tasks,
                    grants = localeGrants,
                    onRevoke = setupViewModel::revokeLocaleGrant,
                )
            }
        }

        if (!settingsOnly) SetupSection.entries.forEach { section ->
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
                            setupViewModel.refresh()
                        }
                        is PermissionAction.ShizukuKillSwitch -> {
                            ShizukuPowerBackend.setKillSwitchEnabled(context, action.enabled)
                            onMessage(
                                if (action.enabled) shizukuModeDisabledMessage
                                else shizukuModeEnabledMessage,
                            )
                            setupViewModel.refresh()
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
private fun SettingsIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.settings_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateCheckSetupCard(
    state: UpdateCheckState,
    onEnabledChange: (Boolean) -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val stateText = when {
        !state.enabled -> stringResource(R.string.setup_update_status_off)
        state.newerVersion != null -> stringResource(R.string.setup_update_status_available, state.newerVersion)
        state.lastCheckedAtMs != null -> stringResource(R.string.setup_update_status_current)
        else -> stringResource(R.string.setup_update_status_pending)
    }
    val toggleDescription = stringResource(
        if (state.enabled) R.string.setup_update_enabled else R.string.setup_update_disabled,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.enabled, onValueChange = onEnabledChange, role = Role.Switch)
                .semantics(mergeDescendants = true) { stateDescription = toggleDescription }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_update_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stateText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.newerVersion != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
            }
            Switch(checked = state.enabled, onCheckedChange = null)
        }
        if (state.newerVersion != null && state.releaseUrl != null) {
            TextButton(onClick = { onOpenRelease(state.releaseUrl) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.setup_update_open_release))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun GlobalFallbackTaskCard(
    taskId: Long?,
    tasks: List<Task>,
    onTaskChange: (Long?) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val selectedName = tasks.firstOrNull { it.id == taskId }?.name ?: stringResource(R.string.label_none)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_global_fallback_title), style = MaterialTheme.typography.titleSmall)
                Text(selectedName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.setup_global_fallback_label))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.label_none)) },
                        onClick = {
                            menuExpanded = false
                            onTaskChange(null)
                        },
                    )
                    tasks.forEach { task ->
                        DropdownMenuItem(
                            text = { Text(task.name) },
                            onClick = {
                                menuExpanded = false
                                onTaskChange(task.id)
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun CompanionSetupCard(
    associations: List<CompanionAssociation>,
    onRefresh: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val associatedMessage = stringResource(R.string.setup_companion_associated)
    val failureMessage = stringResource(R.string.setup_companion_failed)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        onRefresh()
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
                                onRefresh()
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
                                    onRefresh()
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
private fun PushTriggerSetupCard(
    token: String,
    registration: UnifiedPushRegistrationState,
    onChooseDistributor: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val tokenLabel = stringResource(R.string.setup_push_title)
    val copiedMessage = stringResource(R.string.setup_push_copied)
    val endpointCopiedMessage = stringResource(R.string.setup_push_endpoint_copied)
    val statusText = when (registration.status) {
        UnifiedPushRegistrationStatus.IDLE -> stringResource(R.string.setup_push_status_idle)
        UnifiedPushRegistrationStatus.REGISTERING -> stringResource(R.string.setup_push_status_registering)
        UnifiedPushRegistrationStatus.REGISTERED -> stringResource(R.string.setup_push_status_registered)
        UnifiedPushRegistrationStatus.UNREGISTERED -> stringResource(R.string.setup_push_status_unregistered)
        UnifiedPushRegistrationStatus.TEMPORARILY_UNAVAILABLE -> {
            stringResource(R.string.setup_push_status_temporarily_unavailable)
        }
        UnifiedPushRegistrationStatus.REGISTRATION_FAILED -> stringResource(
            R.string.setup_push_status_failed,
            stringResource(pushFailureReasonRes(registration.failureReason)),
        )
    }
    val distributor = registration.distributor
        ?: stringResource(R.string.setup_push_distributor_none)
    val endpoint = registration.endpoint
    val canUnregister = registration.status != UnifiedPushRegistrationStatus.IDLE &&
        registration.status != UnifiedPushRegistrationStatus.UNREGISTERED
    val canRegister = registration.status != UnifiedPushRegistrationStatus.REGISTERING
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
                stringResource(R.string.setup_push_status, statusText),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.setup_push_distributor, distributor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (endpoint != null) {
                Text(
                    stringResource(
                        R.string.setup_push_endpoint,
                        registration.endpointHost ?: stringResource(R.string.setup_push_endpoint_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onChooseDistributor,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.setup_push_choose_distributor))
                }
                if (canUnregister) {
                    OutlinedButton(
                        onClick = onUnregister,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.setup_push_unregister))
                    }
                } else {
                    Button(
                        onClick = onRegister,
                        enabled = canRegister,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.setup_push_register))
                    }
                }
            }
            if (endpoint != null) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText(tokenLabel, endpoint))
                        onMessage(endpointCopiedMessage)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.setup_push_copy_endpoint))
                }
            }
            Text(
                stringResource(R.string.setup_push_legacy_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.setup_push_token, token),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(tokenLabel, token))
                    onMessage(copiedMessage)
                },
                enabled = token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.setup_push_copy))
            }
        }
    }
}

private fun pushFailureReasonRes(reason: String?): Int = when (reason) {
    "VAPID_REQUIRED" -> R.string.setup_push_failure_vapid
    "NETWORK" -> R.string.setup_push_failure_network
    "ACTION_REQUIRED" -> R.string.setup_push_failure_action_required
    "INTERNAL_ERROR" -> R.string.setup_push_failure_internal
    "NO_DISTRIBUTOR" -> R.string.setup_push_failure_no_distributor
    else -> R.string.setup_push_failure_unknown
}

@Composable
private fun LocaleGrantManagementCard(
    tasks: List<Task>,
    grants: List<LocaleGrant>,
    onRevoke: (String) -> Unit,
) {
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
                                onRevoke(grant.token)
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
private fun ThemeSetupCard(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val offeredModes = ThemeMode.entries.filter {
        it != ThemeMode.Dynamic || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
    val modeLabels = ThemeMode.entries.associateWith { mode ->
        when (mode) {
            ThemeMode.System -> stringResource(R.string.theme_system)
            ThemeMode.Dark -> stringResource(R.string.theme_dark)
            ThemeMode.Light -> stringResource(R.string.theme_light)
            ThemeMode.HighContrast -> stringResource(R.string.theme_high_contrast)
            ThemeMode.Amoled -> stringResource(R.string.theme_amoled)
            ThemeMode.Dynamic -> stringResource(R.string.theme_dynamic)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_theme_label), style = MaterialTheme.typography.titleSmall)
                Text(modeLabels.getValue(currentMode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.setup_theme_label))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    offeredModes.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(modeLabels.getValue(mode)) },
                            onClick = {
                                menuExpanded = false
                                onSelectMode(mode)
                            },
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                selected = mode == currentMode
                            },
                            trailingIcon = if (mode == currentMode) {
                                { Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.label_selected)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun RunOnboardingAgainCard(onRunAgain: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.settings_run_onboarding_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.settings_run_onboarding_body),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            OutlinedButton(onClick = onRunAgain) {
                Text(stringResource(R.string.settings_run_onboarding_action))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
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

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, onValueChange = onEnabledChange, role = Role.Switch)
                .semantics(mergeDescendants = true) { this.stateDescription = stateDescription }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_direct_boot_title), style = MaterialTheme.typography.titleSmall)
                Text(stateDescription, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
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
        ThemeMode.Amoled -> stringResource(R.string.theme_amoled)
        ThemeMode.Dynamic -> stringResource(R.string.theme_dynamic)
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
    onSnapshotPolicyChanged: (ConfigurationSnapshotPolicy) -> Unit,
    onSnapshotDestinationSelected: (Uri, CharArray, Boolean) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = when {
        state.pendingRestore -> stringResource(R.string.setup_backup_restore_staged)
        state.latestBackupName != null -> stringResource(R.string.setup_backup_available)
        else -> stringResource(R.string.setup_backup_none)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.setup_backup_label), style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = summary,
                )
            }
            if (expanded) {
                Text(
                    stringResource(R.string.setup_backup_helper_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BackupStateBanner(state)
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
                SnapshotScheduleControls(
                    policy = state.snapshotPolicy,
                    status = state.snapshotStatus,
                    enabled = !state.busy,
                    onPolicyChanged = onSnapshotPolicyChanged,
                    onDestinationSelected = onSnapshotDestinationSelected,
                )
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
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = stateColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
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
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateLabel, style = MaterialTheme.typography.labelMedium, color = stateColor)
            }
            when {
                !item.granted && item.action != PermissionAction.None -> {
                    OutlinedButton(
                        onClick = onRunAction,
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    ) {
                        Text(item.actionLabel, maxLines = 1)
                    }
                }
                item.granted && item.allowActionWhenGranted -> {
                    IconButton(onClick = onRunAction) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.setup_review_settings))
                    }
                }
                else -> {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stateLabel,
                        tint = stateColor,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
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
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
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
        AppLogger.warn("OpenTasker.Setup", "Settings activity is unavailable", ex)
        onMessage(context.getString(R.string.setup_settings_unavailable, context.getString(R.string.setup_error_no_handler)))
    } catch (ex: SecurityException) {
        AppLogger.warn("OpenTasker.Setup", "Settings activity was denied", ex)
        onMessage(context.getString(R.string.setup_settings_open_failed, context.getString(R.string.setup_error_permission_denied)))
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
        AppLogger.warn("OpenTasker.Setup", "OEM guide activity is unavailable", ex)
        onMessage(context.getString(R.string.setup_oem_guide_unavailable, context.getString(R.string.setup_error_no_handler)))
    }
}

/**
 * Opt-in encrypted snapshot schedule.
 *
 * Archives are written only to the user-selected SAF tree and never applied automatically.
 */
@Composable
private fun SnapshotScheduleControls(
    policy: ConfigurationSnapshotPolicy,
    status: ConfigurationSnapshotStatus,
    enabled: Boolean,
    onPolicyChanged: (ConfigurationSnapshotPolicy) -> Unit,
    onDestinationSelected: (Uri, CharArray, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var pendingDestinationUri by rememberSaveable { mutableStateOf<String?>(null) }
    var enableAfterSelection by rememberSaveable { mutableStateOf(false) }
    val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pendingDestinationUri = uri?.toString()
    }
    fun chooseDestination(enableSchedule: Boolean) {
        enableAfterSelection = enableSchedule
        destinationPicker.launch(policy.destinationTreeUri?.let(Uri::parse))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = policy.enabled,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { checked ->
                        if (checked && policy.destinationTreeUri == null) {
                            chooseDestination(enableSchedule = true)
                        } else {
                            onPolicyChanged(policy.copy(enabled = checked))
                        }
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_snapshots_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.setup_snapshots_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = policy.enabled, onCheckedChange = null, enabled = enabled)
        }
        if (policy.enabled || policy.destinationTreeUri != null) {
            Text(
                stringResource(
                    if (policy.destinationTreeUri == null) {
                        R.string.setup_snapshots_destination_missing
                    } else {
                        R.string.setup_snapshots_destination_ready
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (policy.destinationTreeUri == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            OutlinedButton(
                onClick = { chooseDestination(enableSchedule = policy.enabled) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    stringResource(
                        if (policy.destinationTreeUri == null) {
                            R.string.setup_snapshots_destination_choose
                        } else {
                            R.string.setup_snapshots_destination_change
                        },
                    ),
                )
            }
        }
        if (policy.enabled) {
            Text(
                stringResource(
                    R.string.setup_snapshots_retention,
                    policy.maxSnapshots,
                    policy.maxAgeDays,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SNAPSHOT_RETENTION_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = policy.maxSnapshots == choice.first && policy.maxAgeDays == choice.second,
                        onClick = {
                            onPolicyChanged(policy.copy(maxSnapshots = choice.first, maxAgeDays = choice.second))
                        },
                        enabled = enabled,
                        label = {
                            Text(
                                stringResource(R.string.setup_snapshots_choice, choice.first, choice.second),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            Text(
                snapshotStatusLabel(context, status),
                style = MaterialTheme.typography.bodySmall,
                color = if (status.lastFailureAtMs != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    pendingDestinationUri?.let { encodedUri ->
        SnapshotRecoveryPassphraseDialog(
            onDismiss = { pendingDestinationUri = null },
            onConfirm = { passphrase ->
                pendingDestinationUri = null
                onDestinationSelected(Uri.parse(encodedUri), passphrase, enableAfterSelection)
            },
        )
    }
}

@Composable
private fun SnapshotRecoveryPassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    val longEnough = passphrase.length >= SNAPSHOT_MIN_PASSPHRASE_CHARS
    val matches = passphrase == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_snapshots_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.setup_snapshots_passphrase_body))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.setup_snapshots_passphrase_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = passphrase.isNotEmpty() && !longEnough,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text(stringResource(R.string.setup_snapshots_passphrase_confirm)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = confirmation.isNotEmpty() && !matches,
                )
                if ((passphrase.isNotEmpty() && !longEnough) || (confirmation.isNotEmpty() && !matches)) {
                    Text(
                        stringResource(R.string.setup_snapshots_passphrase_error, SNAPSHOT_MIN_PASSPHRASE_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val recoveryPassphrase = passphrase.toCharArray()
                    passphrase = ""
                    confirmation = ""
                    onConfirm(recoveryPassphrase)
                },
                enabled = longEnough && matches,
            ) {
                Text(stringResource(R.string.setup_snapshots_passphrase_save))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private val SNAPSHOT_RETENTION_CHOICES = listOf(3 to 7, 5 to 14, 10 to 30)
private const val SNAPSHOT_MIN_PASSPHRASE_CHARS = 12

private fun snapshotStatusLabel(context: Context, status: ConfigurationSnapshotStatus): String {
    val storage = android.text.format.Formatter.formatShortFileSize(context, status.storageBytes)
    val failure = status.lastFailureAtMs
    if (failure != null) {
        return context.getString(
            R.string.setup_snapshots_status_failed,
            formatSnapshotTimestamp(failure),
            status.lastFailureMessage ?: context.getString(R.string.setup_snapshots_status_unknown_error),
        )
    }
    val success = status.lastSuccessAtMs
        ?: return context.getString(R.string.setup_snapshots_status_pending)
    return context.getString(
        R.string.setup_snapshots_status_ok,
        formatSnapshotTimestamp(success),
        status.snapshotCount,
        storage,
    )
}

private fun formatSnapshotTimestamp(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.US))
