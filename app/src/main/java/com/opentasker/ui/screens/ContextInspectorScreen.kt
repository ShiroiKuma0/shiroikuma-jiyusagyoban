package com.opentasker.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.contexts.ContextEventObservation
import com.opentasker.core.contexts.ContextInspectionSnapshot
import com.opentasker.core.contexts.ContextObservationStatus
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.ContextSourceSnapshot
import com.opentasker.core.contexts.ContextSourceStatus
import com.opentasker.core.contexts.ProfileInspection
import com.opentasker.core.contexts.inspectProfiles
import com.opentasker.core.contexts.observationStatus
import com.opentasker.core.engine.CausalLoopDiagnostics
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.location.LocationPolicyDisclosures
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.permissions.OemBatteryGuidance
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.StorageDecodeIssue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContextInspectorViewModel(
    db: AppDatabase,
    private val appContext: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private val latestEvents = MutableStateFlow<Map<String, ContextEventObservation>>(emptyMap())
    private val sourceErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val refreshTick = MutableStateFlow(clock())
    private val sourceCollectorJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private val locationDwellStateStore = LocationDwellStateStore(appContext, clock)

    private val profileDecodeResults = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val profiles: StateFlow<List<Profile>> = profileDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase(Locale.US) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storageDecodeIssues: StateFlow<List<StorageDecodeIssue>> = profileDecodeResults
        .map { results -> results.mapNotNull { it.issue } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val snapshot: StateFlow<ContextInspectionSnapshot> = combine(
        profiles,
        latestEvents,
        sourceErrors,
        refreshTick,
        CausalLoopDiagnostics.latest,
    ) { profiles, observations, errors, now, causalLoop ->
        val sources = buildContextSourceSnapshots(appContext, observations, errors)
        ContextInspectionSnapshot(
            generatedAtMs = now,
            sources = sources,
            profiles = inspectProfiles(profiles, sources) { profile, index, spec, observation ->
                if (spec.type == ContextType.LOCATION) {
                    // observe() is read-only: the Inspector must never persist or clear the
                    // engine's dwell timers from its own independent location stream.
                    observation.copy(event = locationDwellStateStore.observe(profile.id, index, spec, observation.event))
                } else {
                    observation
                }
            },
            causalLoop = causalLoop,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyContextInspectionSnapshot(clock()))

    fun refresh() {
        refreshTick.value = clock()
    }

    /** Start only while the inspector is visible; the shared event bus then has real demand. */
    fun startObserving() {
        if (sourceCollectorJobs.values.any(Job::isActive)) return
        requiredContextSourceKeys().forEach { key ->
            val source = ContextSourceRegistry.get(key) ?: return@forEach
            sourceCollectorJobs[key] = viewModelScope.launch {
                source.events(appContext)
                    .catch { error ->
                        sourceErrors.update { current ->
                            current + (key to (error.message ?: error::class.java.simpleName))
                        }
                    }
                    .collect { event ->
                        sourceErrors.update { current -> current - key }
                        latestEvents.update { current ->
                            current + (key to ContextEventObservation(event, clock()))
                        }
                        refresh()
                    }
            }
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refresh()
            }
        }
    }

    fun stopObserving() {
        sourceCollectorJobs.values.forEach(Job::cancel)
        sourceCollectorJobs.clear()
        refreshJob?.cancel()
        refreshJob = null
        latestEvents.value = emptyMap()
        sourceErrors.value = emptyMap()
        refresh()
    }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }

}

class ContextInspectorViewModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ContextInspectorViewModel::class.java)) {
            return ContextInspectorViewModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
fun ContextInspectorScreen(
    db: AppDatabase,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val factory = remember(db, context) { ContextInspectorViewModelFactory(db, context) }
    val viewModel: ContextInspectorViewModel = viewModel(factory = factory)
    val snapshot by viewModel.snapshot.collectAsState()
    val storageDecodeIssues by viewModel.storageDecodeIssues.collectAsState()

    DisposableEffect(viewModel) {
        viewModel.startObserving()
        onDispose { viewModel.stopObserving() }
    }

    if (snapshot.sources.isEmpty() && snapshot.profiles.isEmpty() && storageDecodeIssues.isEmpty()) {
        InspectorEmptyState(contentPadding)
        return
    }

    val oem = remember { OemBatteryGuidance.forDevice(Build.MANUFACTURER, Build.BRAND) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        item {
            ContextInspectorSummaryCard(snapshot = snapshot, onRefresh = viewModel::refresh)
        }
        snapshot.causalLoop?.let { causalLoop ->
            item {
                InspectorNotice(
                    title = stringResource(R.string.inspector_causal_loop_title),
                    body = stringResource(
                        R.string.inspector_causal_loop_body,
                        causalLoop.profileChain.joinToString(" -> "),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        if (oem.needsExtraSteps) {
            item {
                OemRiskNotice(oem)
            }
        }
        item {
            Text(
                stringResource(R.string.inspector_sources_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(snapshot.sources, key = { it.key }) { source ->
            ContextSourceCard(source = source, nowMs = snapshot.generatedAtMs)
        }
        item {
            Text(
                stringResource(R.string.inspector_match_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.profiles.isEmpty()) {
            item {
                InspectorNotice(
                    title = stringResource(R.string.empty_profiles_inspector),
                    body = stringResource(R.string.inspector_no_profiles_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            items(snapshot.profiles, key = { it.profileId }) { profile ->
                ProfileInspectorCard(profile = profile, nowMs = snapshot.generatedAtMs)
            }
        }
    }
}

@Composable
private fun ContextInspectorSummaryCard(
    snapshot: ContextInspectionSnapshot,
    onRefresh: () -> Unit,
) {
    val activeSources = snapshot.sources.count { it.status == ContextSourceStatus.Active }
    val attentionSources = snapshot.sources.count {
        it.status == ContextSourceStatus.NeedsSetup ||
            it.status == ContextSourceStatus.Missing ||
            it.status == ContextSourceStatus.Error
    }
    val enabledProfiles = snapshot.profiles.count { it.enabled }
    val matchingProfiles = snapshot.profiles.count { it.matching }
    val healthColor = if (attentionSources == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_context_inspector), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.inspector_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InspectorStatusPill(
                    label = if (attentionSources == 0) {
                        stringResource(R.string.inspector_ready)
                    } else {
                        stringResource(R.string.inspector_attention, attentionSources)
                    },
                    color = healthColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                InspectorMetric("$activeSources", stringResource(R.string.inspector_active_sources), Modifier.weight(1f))
                InspectorMetric("$matchingProfiles", stringResource(R.string.inspector_matching), Modifier.weight(1f))
                InspectorMetric("$enabledProfiles", stringResource(R.string.inspector_enabled), Modifier.weight(1f))
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.inspector_refresh), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.inspector_refresh))
            }
        }
    }
}

@Composable
private fun ContextSourceCard(source: ContextSourceSnapshot, nowMs: Long) {
    val color = sourceStatusColor(source.status)
    val observationStatus = source.observationStatus(nowMs)
    val observation = source.lastObservation
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (source.status) {
                ContextSourceStatus.NeedsSetup,
                ContextSourceStatus.Missing,
                ContextSourceStatus.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
            },
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Icon(sourceStatusIcon(source.status), contentDescription = stringResource(source.status.resourceId), tint = color, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val lastUpdateLabel = stringResource(R.string.inspector_last_update)
                    val noValueLabel = stringResource(R.string.inspector_no_value)
                    Text(
                        observation?.let {
                            stringResource(
                                R.string.inspector_last_update_value,
                                lastUpdateLabel,
                                formatRelativeTime(LocalContext.current, it.observedAtMs, nowMs),
                            )
                        } ?: noValueLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InspectorStatusPill(stringResource(source.status.resourceId), color)
                InspectorStatusPill(
                    stringResource(observationStatus.resourceId),
                    observationStatusColor(observationStatus),
                )
            }
            source.setupDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            source.error?.let {
                InspectorNotice(stringResource(R.string.inspector_source_error), it, MaterialTheme.colorScheme.error)
            }
            observation?.let {
                ContextMetadataBlock(event = it, nowMs = nowMs)
            }
        }
    }
}

@Composable
private fun ProfileInspectorCard(profile: ProfileInspection, nowMs: Long) {
    val color = when {
        !profile.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        profile.matching -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.matching) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
            },
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Icon(
                    if (profile.matching) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = if (profile.matching) stringResource(R.string.status_matching) else stringResource(R.string.inspector_not_matching),
                    tint = color,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(profile.profileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InspectorStatusPill(
                    label = when {
                        !profile.enabled -> stringResource(R.string.status_disabled)
                        profile.matching -> stringResource(R.string.status_matching)
                        else -> stringResource(R.string.status_blocked)
                    },
                    color = color,
                )
            }
            if (profile.logicExplanation.isNotBlank()) {
                Text(
                    profile.logicExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (profile.contexts.isEmpty()) {
                InspectorNotice(
                    title = stringResource(R.string.inspector_no_contexts),
                    body = stringResource(R.string.inspector_no_contexts_body),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                profile.contexts.forEach { check ->
                    ContextCheckRow(check = check, nowMs = nowMs)
                }
            }
        }
    }
}

@Composable
private fun ContextCheckRow(
    check: com.opentasker.core.contexts.ContextCheck,
    nowMs: Long,
) {
    val context = LocalContext.current
    val color = if (check.effectiveMatched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                InspectorStatusPill(
                    stringResource(R.string.inspector_context_number, check.index + 1),
                    MaterialTheme.colorScheme.secondary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.inspector_context_source,
                            stringResource(contextTitleRes(check.spec.type)),
                            check.sourceLabel,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        check.configSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InspectorStatusPill(
                    if (check.effectiveMatched) stringResource(R.string.inspector_match) else stringResource(R.string.inspector_no_match),
                    color,
                )
            }
            Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            locationDwellDetail(context, check, nowMs)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            check.lastObservation?.let {
                Text(
                    stringResource(R.string.inspector_observed, formatRelativeTime(context, it.observedAtMs, nowMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContextMetadataBlock(event: ContextEventObservation, nowMs: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.inspector_latest_value), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.inspector_matched, event.event.matched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.event.metadata["component"]?.let { component ->
                Text(
                    if (component.isBlank()) {
                        stringResource(R.string.inspector_component_unavailable)
                    } else {
                        stringResource(R.string.inspector_observed_component, component)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            event.event.metadata.entries
                .filterNot { it.key == "component" || it.key == "component_status" }
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    Text(
                        stringResource(R.string.inspector_metadata, key, value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            Text(
                formatAbsoluteTime(LocalContext.current, event.observedAtMs, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InspectorMetric(value: String, label: String, modifier: Modifier = Modifier) {
    SummaryMetric(value = value, label = label, modifier = modifier)
}

@Composable
private fun InspectorStatusPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OemRiskNotice(oem: OemBatteryGuidance.Guidance) {
    val color = when (oem.riskLevel) {
        OemBatteryGuidance.RiskLevel.SEVERE, OemBatteryGuidance.RiskLevel.HIGH -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Error, contentDescription = stringResource(R.string.inspector_warning), tint = color, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        R.string.inspector_oem_risk,
                        oem.oemName,
                        oem.riskLevel.name.lowercase(Locale.US),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.inspector_oem_summary, oem.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InspectorNotice(title: String, body: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InspectorEmptyState(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
            shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.inspector_unavailable_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(R.string.inspector_unavailable_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.inspector_unavailable_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                InspectorStatusPill(
                    label = "Waiting for sources",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun sourceStatusColor(status: ContextSourceStatus): Color = when (status) {
    ContextSourceStatus.Active -> MaterialTheme.colorScheme.tertiary
    ContextSourceStatus.Waiting -> MaterialTheme.colorScheme.secondary
    ContextSourceStatus.NeedsSetup,
    ContextSourceStatus.Missing,
    ContextSourceStatus.Error -> MaterialTheme.colorScheme.error
}

@Composable
private fun observationStatusColor(status: ContextObservationStatus): Color = when (status) {
    ContextObservationStatus.Ready -> MaterialTheme.colorScheme.tertiary
    ContextObservationStatus.Loading -> MaterialTheme.colorScheme.secondary
    ContextObservationStatus.Stale -> MaterialTheme.colorScheme.onSurfaceVariant
    ContextObservationStatus.Error -> MaterialTheme.colorScheme.error
}

private fun sourceStatusIcon(status: ContextSourceStatus) = when (status) {
    ContextSourceStatus.Active -> Icons.Filled.CheckCircle
    ContextSourceStatus.Waiting -> Icons.Filled.Info
    ContextSourceStatus.NeedsSetup,
    ContextSourceStatus.Missing,
    ContextSourceStatus.Error -> Icons.Filled.Error
}

private fun buildContextSourceSnapshots(
    context: Context,
    observations: Map<String, ContextEventObservation>,
    errors: Map<String, String>,
): List<ContextSourceSnapshot> {
    val registeredKeys = ContextSourceRegistry.all().map { it.type }.toSet()
    val keys = (requiredContextSourceKeys() + registeredKeys).sorted()
    return keys.map { key ->
        val setup = contextSourceSetup(context, key)
        ContextSourceSnapshot(
            key = key,
            label = context.getString(sourceLabelResource(key)),
            registered = key in registeredKeys,
            setupReady = setup.ready,
            setupDetail = setup.detail,
            error = errors[key],
            lastObservation = observations[key],
        )
    }
}

private fun sourceLabelResource(key: String): Int = when (key) {
    "app" -> R.string.context_source_application
    "time" -> R.string.context_source_time
    "state" -> R.string.context_source_state
    "event" -> R.string.context_source_event
    "location" -> R.string.context_source_location
    "plugin" -> R.string.context_source_plugin
    else -> R.string.context_source_unknown
}

private data class ContextSourceSetup(val ready: Boolean, val detail: String)

private fun contextSourceSetup(context: Context, key: String): ContextSourceSetup = when (key) {
    "app" -> {
        val granted = UsageAccess.hasUsageStatsAccess(context)
        ContextSourceSetup(
            ready = granted,
            detail = if (granted) {
                context.getString(R.string.inspector_setup_usage_granted)
            } else {
                context.getString(R.string.inspector_setup_usage_missing)
            },
        )
    }
    "time" -> {
        val exactReady = ExactAlarmSupport.canScheduleExactAlarms(context)
        ContextSourceSetup(
            ready = true,
            detail = if (exactReady) {
                context.getString(R.string.inspector_setup_clock_exact)
            } else {
                context.getString(R.string.inspector_setup_clock_inexact)
            },
        )
    }
    "state" -> {
        val wifiReady = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        val locationReady = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        ContextSourceSetup(
            ready = true,
            detail = if (wifiReady && locationReady) {
                context.getString(R.string.inspector_setup_state_ready)
            } else {
                context.getString(R.string.inspector_setup_state_partial)
            },
        )
    }
    "event" -> {
        val notificationReady = hasNotificationListenerAccess(context)
        val calendarReady = hasPermission(context, Manifest.permission.READ_CALENDAR)
        val calendarDetail = if (calendarReady) {
            context.getString(R.string.inspector_setup_calendar_ready)
        } else {
            context.getString(R.string.inspector_setup_calendar_missing)
        }
        ContextSourceSetup(
            ready = true,
            detail = if (notificationReady) {
                context.getString(R.string.inspector_setup_event_ready, calendarDetail)
            } else {
                context.getString(R.string.inspector_setup_event_notification_missing, calendarDetail)
            },
        )
    }
    "location" -> {
        val foreground = hasAnyLocationPermission(context)
        val precise = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val providerEnabled = hasEnabledLocationProvider(context)
        ContextSourceSetup(
            ready = foreground && providerEnabled,
            detail = LocationPolicyDisclosures.sourceSetupDetail(
                foreground = foreground,
                precise = precise,
                background = background,
                providerEnabled = providerEnabled,
                apiLevel = Build.VERSION.SDK_INT,
            ),
        )
    }
    else -> ContextSourceSetup(ready = true, detail = context.getString(R.string.inspector_setup_source_generic))
}

private fun requiredContextSourceKeys(): Set<String> =
    ContextType.entries.mapNotNull { com.opentasker.core.contexts.ContextMatchEvaluator.sourceKey(it) }.toSet()

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasAnyLocationPermission(context: Context): Boolean =
    hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun hasEnabledLocationProvider(context: Context): Boolean {
    val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
    return runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
        runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
}

private fun hasNotificationListenerAccess(context: Context): Boolean {
    val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName, ignoreCase = true) == true
}

private val ContextSourceStatus.resourceId: Int
    get() = when (this) {
        ContextSourceStatus.Active -> R.string.inspector_status_active
        ContextSourceStatus.Waiting -> R.string.inspector_status_waiting
        ContextSourceStatus.NeedsSetup -> R.string.inspector_status_needs_setup
        ContextSourceStatus.Missing -> R.string.inspector_status_missing
        ContextSourceStatus.Error -> R.string.inspector_status_error
    }

private val ContextObservationStatus.resourceId: Int
    get() = when (this) {
        ContextObservationStatus.Loading -> R.string.inspector_observation_loading
        ContextObservationStatus.Ready -> R.string.inspector_observation_ready
        ContextObservationStatus.Stale -> R.string.inspector_observation_stale
        ContextObservationStatus.Error -> R.string.inspector_observation_error
    }

private fun emptyContextInspectionSnapshot(nowMs: Long): ContextInspectionSnapshot =
    ContextInspectionSnapshot(generatedAtMs = nowMs, sources = emptyList(), profiles = emptyList())

private fun formatRelativeTime(context: Context, observedAtMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - observedAtMs) / 1000L).coerceAtLeast(0)
    return when {
        seconds < 5 -> context.getString(R.string.inspector_time_just_now)
        seconds < 60 -> context.getString(R.string.inspector_time_seconds, seconds)
        seconds < 3_600 -> context.getString(R.string.inspector_time_minutes, seconds / 60)
        else -> context.getString(R.string.inspector_time_hours, seconds / 3_600)
    }
}

private fun formatAbsoluteTime(context: Context, observedAtMs: Long, nowMs: Long): String {
    val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(observedAtMs))
    return context.getString(R.string.inspector_time_absolute, formatted, formatRelativeTime(context, observedAtMs, nowMs))
}

private fun locationDwellDetail(context: Context, check: com.opentasker.core.contexts.ContextCheck, nowMs: Long): String? {
    if (check.spec.type != ContextType.LOCATION) return null
    val observation = check.lastObservation ?: return null
    val metadata = observation.event.metadata
    val state = metadata["dwellState"] ?: return null
    val dwellMillis = parseDwellMillis(check.spec.config)
    val target = dwellMillis.takeIf { it > 0L }?.let { context.getString(R.string.inspector_dwell_target, formatDuration(context, it)) }.orEmpty()
    val observedAt = metadata["observedAtEpochMs"]?.toLongOrNull() ?: observation.observedAtMs
    val insideSince = metadata["insideSinceEpochMs"]?.toLongOrNull()
    val insideFor = insideSince?.let { formatDuration(context, (observedAt - it).coerceAtLeast(0L)) }

    return when (state) {
        "inside" -> insideFor?.let { context.getString(R.string.inspector_dwell_inside, it, target) }
            ?: context.getString(R.string.inspector_dwell_inside_waiting)
        "accuracy_blocked" -> insideFor?.let {
            context.getString(R.string.inspector_dwell_accuracy_blocked, it, target)
        } ?: context.getString(R.string.inspector_dwell_accuracy_blocked_no_timer)
        "outside" -> context.getString(R.string.inspector_dwell_outside)
        "unknown" -> context.getString(R.string.inspector_dwell_unknown)
        else -> null
    }
}

private fun parseDwellMillis(config: Map<String, String>): Long {
    val millis = firstConfig(config, "dwellMillis", "dwellMs").toLongOrNull()
    if (millis != null) return millis.coerceAtLeast(0L)
    val seconds = firstConfig(config, "dwellSeconds", "dwellSec").toLongOrNull()
    return seconds?.coerceAtLeast(0L)?.times(1_000L) ?: 0L
}

private fun firstConfig(config: Map<String, String>, vararg keys: String): String =
    keys.firstNotNullOfOrNull { config[it]?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

private fun formatDuration(context: Context, ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60 -> context.getString(R.string.inspector_duration_seconds, seconds)
        seconds < 3_600 -> context.getString(R.string.inspector_duration_minutes_seconds, seconds / 60, seconds % 60)
        else -> context.getString(R.string.inspector_duration_hours_minutes, seconds / 3_600, (seconds % 3_600) / 60)
    }
}
