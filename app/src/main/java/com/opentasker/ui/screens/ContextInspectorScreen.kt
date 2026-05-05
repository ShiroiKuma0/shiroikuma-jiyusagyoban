package com.opentasker.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.contexts.ContextEventObservation
import com.opentasker.core.contexts.ContextInspectionSnapshot
import com.opentasker.core.contexts.ContextSourceRegistry
import com.opentasker.core.contexts.ContextSourceSnapshot
import com.opentasker.core.contexts.ContextSourceStatus
import com.opentasker.core.contexts.ProfileInspection
import com.opentasker.core.contexts.inspectProfiles
import com.opentasker.core.contexts.toContextSourceLabel
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.delay
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

    private val profiles: StateFlow<List<Profile>> = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase(Locale.US) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val snapshot: StateFlow<ContextInspectionSnapshot> = combine(
        profiles,
        latestEvents,
        sourceErrors,
        refreshTick,
    ) { profiles, observations, errors, now ->
        val sources = buildContextSourceSnapshots(appContext, observations, errors)
        ContextInspectionSnapshot(
            generatedAtMs = now,
            sources = sources,
            profiles = inspectProfiles(profiles, sources),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyContextInspectionSnapshot(clock()))

    init {
        startSourceCollectors()
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                refresh()
            }
        }
    }

    fun refresh() {
        refreshTick.value = clock()
    }

    private fun startSourceCollectors() {
        requiredContextSourceKeys().forEach { key ->
            val source = ContextSourceRegistry.get(key) ?: return@forEach
            viewModelScope.launch {
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

    if (snapshot.sources.isEmpty() && snapshot.profiles.isEmpty()) {
        InspectorEmptyState(contentPadding)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ContextInspectorSummaryCard(snapshot = snapshot, onRefresh = viewModel::refresh)
        }
        item {
            Text(
                "Context sources",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(snapshot.sources, key = { it.key }) { source ->
            ContextSourceCard(source = source, nowMs = snapshot.generatedAtMs)
        }
        item {
            Text(
                "Profile match state",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.profiles.isEmpty()) {
            item {
                InspectorNotice(
                    title = "No profiles",
                    body = "Create a profile before reviewing match explanations.",
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
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Context inspector", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Live source values and profile match explanations.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InspectorStatusPill(
                    label = if (attentionSources == 0) "Ready" else "$attentionSources attention",
                    color = healthColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InspectorMetric("$activeSources", "Active sources", Modifier.weight(1f))
                InspectorMetric("$matchingProfiles", "Matching", Modifier.weight(1f))
                InspectorMetric("$enabledProfiles", "Enabled", Modifier.weight(1f))
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh Status")
            }
        }
    }
}

@Composable
private fun ContextSourceCard(source: ContextSourceSnapshot, nowMs: Long) {
    val color = sourceStatusColor(source.status)
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(sourceStatusIcon(source.status), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(source.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        observation?.let { "Last update ${formatRelativeTime(it.observedAtMs, nowMs)}" } ?: "No value observed yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InspectorStatusPill(source.status.label, color)
            }
            source.setupDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            source.error?.let {
                InspectorNotice("Source error", it, MaterialTheme.colorScheme.error)
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (profile.matching) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(profile.profileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InspectorStatusPill(
                    label = when {
                        !profile.enabled -> "Disabled"
                        profile.matching -> "Matching"
                        else -> "Blocked"
                    },
                    color = color,
                )
            }
            if (profile.contexts.isEmpty()) {
                InspectorNotice(
                    title = "No contexts",
                    body = "This profile cannot match until at least one context is attached.",
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
    val color = if (check.effectiveMatched) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectorStatusPill("#${check.index + 1}", MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f)) {
                    Text(
                        "${check.spec.type.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }} via ${check.sourceLabel}",
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
                InspectorStatusPill(if (check.effectiveMatched) "Match" else "No match", color)
            }
            Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            check.lastObservation?.let {
                Text(
                    "Observed ${formatRelativeTime(it.observedAtMs, nowMs)}",
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
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Latest value", style = MaterialTheme.typography.labelLarge)
            Text(
                "matched=${event.event.matched}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.event.metadata.entries.sortedBy { it.key }.forEach { (key, value) ->
                Text(
                    "$key=$value",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                formatAbsoluteTime(event.observedAtMs, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InspectorMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InspectorStatusPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
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
private fun InspectorNotice(title: String, body: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
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
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text("Context inspector unavailable", style = MaterialTheme.typography.titleLarge)
        Text(
            "Runtime context sources have not registered yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            label = key.toContextSourceLabel(),
            registered = key in registeredKeys,
            setupReady = setup.ready,
            setupDetail = setup.detail,
            error = errors[key],
            lastObservation = observations[key],
        )
    }
}

private data class ContextSourceSetup(val ready: Boolean, val detail: String)

private fun contextSourceSetup(context: Context, key: String): ContextSourceSetup = when (key) {
    "app" -> {
        val granted = UsageAccess.hasUsageStatsAccess(context)
        ContextSourceSetup(
            ready = granted,
            detail = if (granted) {
                "Usage access is granted for foreground-app context checks."
            } else {
                "Usage access is missing; application contexts cannot report foreground packages."
            },
        )
    }
    "time" -> {
        val exactReady = ExactAlarmSupport.canScheduleExactAlarms(context)
        ContextSourceSetup(
            ready = true,
            detail = if (exactReady) {
                "Clock source is registered and exact alarms are available for scheduled engine ticks."
            } else {
                "Clock source is registered; exact alarms are denied so scheduled engine ticks use the inexact fallback."
            },
        )
    }
    "state" -> {
        val wifiReady = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        val locationReady = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        ContextSourceSetup(
            ready = true,
            detail = if (wifiReady && locationReady) {
                "Battery, charging, screen, headset, and WiFi-related state checks have required runtime access."
            } else {
                "Battery, charging, screen, and headset checks are available; WiFi state may need location or nearby WiFi setup."
            },
        )
    }
    "event" -> {
        val notificationReady = hasNotificationListenerAccess(context)
        ContextSourceSetup(
            ready = true,
            detail = if (notificationReady) {
                "Boot, system, and notification events are registered. Notification text is kept in-memory for matching and is not written to run logs."
            } else {
                "Boot and system events are registered. Notification events need Notification Access in Setup before Android will bind the listener."
            },
        )
    }
    "location" -> {
        val foreground = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ContextSourceSetup(
            ready = foreground && background,
            detail = when {
                foreground && background -> "Location permissions are granted, but no live location source is registered yet."
                foreground -> "Foreground location is granted; background location is still missing and no live location source is registered yet."
                else -> "Location permissions are missing and no live location source is registered yet."
            },
        )
    }
    else -> ContextSourceSetup(ready = true, detail = "Source setup status is not specialized yet.")
}

private fun requiredContextSourceKeys(): Set<String> =
    ContextType.entries.mapNotNull { com.opentasker.core.contexts.ContextMatchEvaluator.sourceKey(it) }.toSet()

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationListenerAccess(context: Context): Boolean {
    val enabledListeners = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabledListeners?.contains(context.packageName, ignoreCase = true) == true
}

private fun emptyContextInspectionSnapshot(nowMs: Long): ContextInspectionSnapshot =
    ContextInspectionSnapshot(generatedAtMs = nowMs, sources = emptyList(), profiles = emptyList())

private fun formatRelativeTime(observedAtMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - observedAtMs) / 1000L).coerceAtLeast(0)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3_600}h ago"
    }
}

private fun formatAbsoluteTime(observedAtMs: Long, nowMs: Long): String {
    val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(observedAtMs))
    return "$formatted - ${formatRelativeTime(observedAtMs, nowMs)}"
}
