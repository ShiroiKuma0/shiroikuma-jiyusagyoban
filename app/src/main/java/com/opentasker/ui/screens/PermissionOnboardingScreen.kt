package com.opentasker.ui.screens

import com.opentasker.i18n.tr
import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import com.opentasker.ui.components.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.opentasker.ui.components.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
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
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.opentasker.core.location.LocationPolicyDisclosures
import com.opentasker.core.permissions.OemBatteryGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.support.ProjectLinks
import com.opentasker.core.scripting.TermuxScriptState
import com.opentasker.core.shizuku.ShizukuShell
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import com.opentasker.core.icons.TaskIconStore

internal data class PermissionSetupItem(
    val title: String,
    val body: String,
    val granted: Boolean,
    val actionLabel: String,
    val action: PermissionAction,
    val requiredFor: String,
    val optional: Boolean = false,
)

internal sealed interface PermissionAction {
    /**
     * A runtime permission dialog for [permission], which decides the outcome. [requestedWith] is
     * asked for in the same dialog, because Android 12+ ignores a fine-location request that does
     * not also ask for coarse location.
     */
    data class RuntimePermission(
        val permission: String,
        val requestedWith: List<String> = emptyList(),
    ) : PermissionAction
    data class SettingsIntent(val intent: Intent) : PermissionAction
    /** Try each OEM settings component in order, falling back to a web guide URL. */
    data class OemSettings(
        val targets: List<OemBatteryGuidance.SettingsTarget>,
        val fallbackUrl: String,
    ) : PermissionAction
    /** Device-admin add screen — launched for-result so it doesn't blink shut. */
    data object DeviceAdmin : PermissionAction
    /** Fork: arbitrary side effect (e.g. pop Shizuku's own grant dialog) — refresh happens on resume. */
    data class Custom(val run: () -> Unit) : PermissionAction
    /**
     * Fork: a grant that has no dialog and no settings page — [command] is run through Shizuku or
     * root ([com.opentasker.core.shizuku.PrivilegedShell]) and the outcome is reported.
     */
    data class PrivilegedGrant(val command: String) : PermissionAction
    data object None : PermissionAction
}

@Composable
fun PermissionOnboardingScreen(
    contentPadding: PaddingValues,
    onMessage: (String) -> Unit,
    // Workspace health (from ActiveAutomationUi): tasks that cannot run right now, shown as a red card
    // at the very top so Setup and the red ❗ marks on the Tasks tab always tell the same story.
    blockedTasks: List<com.opentasker.core.capabilities.WorkspaceHealth.BlockedTask> = emptyList(),
    onOpenTasks: (() -> Unit)? = null,
    // Whether a task uses the locale switch — decides if its adb-level grant counts as required.
    localeSwitchUsed: Boolean = com.opentasker.core.capabilities.LocaleSwitchUse.used,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val runAction = rememberPermissionActionRunner(onMessage) { refreshTick++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val items = remember(context, refreshTick, localeSwitchUsed) { buildPermissionItems(context, localeSwitchUsed) }
    val orderedItems = remember(items) {
        items.sortedWith(compareBy<PermissionSetupItem> { it.optional }.thenBy { it.granted }.thenBy { it.title })
    }
    val requiredItems = remember(items) { items.filterNot { it.optional } }
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
        // Task health — the same blocked-task set that puts the red ❗ on the Tasks tab. Red card
        // listing each blocked task and what it's missing; green one-liner when everything can run.
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (blockedTasks.isEmpty()) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    },
                ),
                border = BorderStroke(
                    1.dp,
                    if (blockedTasks.isEmpty()) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.65f),
                ),
                shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (blockedTasks.isEmpty()) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null,
                            tint = if (blockedTasks.isEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            if (blockedTasks.isEmpty()) tr("Task health — all tasks can run")
                            else if (blockedTasks.size == 1) tr("Task health — 1 task blocked")
                            else tr("Task health — %1\$s tasks blocked", blockedTasks.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (blockedTasks.isNotEmpty()) {
                        blockedTasks.forEach { blocked ->
                            Text(
                                "• ${blocked.taskName} — ${blocked.problems.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (onOpenTasks != null) {
                            OutlinedButton(onClick = onOpenTasks) { Text(tr("Show in Tasks")) }
                        }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
                shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Setup checklist"), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                tr("白い熊 自由作業盤 can run with missing access, but affected automations stay gated until setup is complete."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PermissionStatusPill(
                            if (pendingCount == 0) tr("Ready") else tr("%1\$s pending", pendingCount),
                            if (pendingCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
                    )
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

        items(orderedItems, key = { it.title }) { item ->
            PermissionSetupCard(
                item = item,
                onRunAction = { runAction(item) },
            )
        }

    }
}

/**
 * Runs a setup item's shortcut — the permission dialog, the settings page, the device-admin screen.
 * Shared by the Setup page and the switched-off warning, so both open exactly the same places.
 * [onChanged] fires when a dialog launched for-result comes back.
 */
@Composable
internal fun rememberPermissionActionRunner(
    onMessage: (String) -> Unit,
    onChanged: () -> Unit,
): (PermissionSetupItem) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Android stops showing the permission dialog once it has been refused twice (and EMUI after it
    // has revoked the permission itself): the request then comes straight back refused and the
    // button looks dead. An answer faster than anyone can tap means no dialog was shown, so the
    // app's own permission page is opened instead.
    val askedAt = remember { longArrayOf(0L) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val refusedUnasked = result.values.any { !it } &&
            android.os.SystemClock.elapsedRealtime() - askedAt[0] < SILENT_REFUSAL_MS
        if (refusedUnasked) {
            onMessage(tr("Android no longer asks for this one — set it under 権限 (Permissions); off and on again if it already reads 許可."))
            openSettingsIntent(context, appDetailsIntent(context), onMessage)
        }
        onChanged()
    }
    // Device-admin add must be started for-result from the Activity (NOT as a new task, which makes the
    // system's translucent DeviceAdminAdd screen open and instantly finish).
    val deviceAdminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onChanged()
    }
    return { item ->
        when (val action = item.action) {
            PermissionAction.None -> onMessage(tr("%1\$s is already ready.", item.title))
            is PermissionAction.RuntimePermission ->
                // Not yet granted → ask. Already granted → open this app's details page so it
                // can be toggled off (re-requesting a granted runtime permission does nothing).
                if (item.granted) {
                    openSettingsIntent(
                        context,
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                        onMessage,
                    )
                } else {
                    askedAt[0] = android.os.SystemClock.elapsedRealtime()
                    permissionLauncher.launch((listOf(action.permission) + action.requestedWith).toTypedArray())
                }
            is PermissionAction.SettingsIntent -> openSettingsIntent(context, action.intent, onMessage)
            is PermissionAction.Custom -> action.run()
            is PermissionAction.PrivilegedGrant -> {
                onMessage(tr("%1\$s: granting…", item.title))
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        com.opentasker.core.shizuku.PrivilegedShell.run(action.command)
                    }
                    onMessage(
                        if (outcome.ok) tr("%1\$s: granted through %2\$s.", item.title, outcome.route)
                        else tr("%1\$s: not granted — %2\$s.", item.title, outcome.detail),
                    )
                    onChanged()
                }
            }
            is PermissionAction.OemSettings -> openOemSettings(context, action, onMessage)
            is PermissionAction.DeviceAdmin -> {
                val admin = android.content.ComponentName(context, com.opentasker.core.admin.DeviceAdmin::class.java)
                val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                if (dpm?.isAdminActive(admin) == true) {
                    // Already active → open the device-admin LIST to review / disable it. (EMUI buries
                    // it under Security → Advanced; ACTION_ADD_DEVICE_ADMIN no-ops once active.)
                    openDeviceAdminSettings(context, onMessage)
                } else {
                    // Not active → the direct "activate this admin?" screen (for-result so it doesn't
                    // blink shut); if the OEM won't honor it, fall back to the device-admin list.
                    try {
                        deviceAdminLauncher.launch(
                            Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                                .putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    tr("Enable so 物理鍵 lockdown (screen.lockdown) can lock the device."),
                                ),
                        )
                    } catch (ex: Exception) {
                        openDeviceAdminSettings(context, onMessage)
                    }
                }
            }
        }
    }
}

/**
 * The setup items that are switched off and that the warning lists: every required item, plus
 * Shizuku — optional on paper, but shell, wake, freeze and 物理鍵 all stand on it.
 */
internal fun switchedOffSetupItems(context: Context): List<PermissionSetupItem> =
    buildPermissionItems(context).filter { !it.granted && (!it.optional || it.title == "Shizuku") }

// Upstream's theme picker (ThemeSetupCard/ThemeChoice, a radio group over ThemeMode) is not part of
// this fork's Setup screen: appearance here comes from ThemeStore's ThemePrefs — the black-yellow
// palette, font family, weight and scale — not from upstream's light/dark/high-contrast/AMOLED/
// Material You modes. The card was left behind unreferenced through several syncs and had to be
// hand-extended every time upstream added a mode, so it is gone rather than dead.
// Upstream's backup card (BackupSetupCard/BackupStateBanner over a BackupSetupState, plus the
// snapshot-schedule controls that used to sit inside it) is not part of this fork's Setup screen
// either. An earlier sync removed the controls; the card itself was then left behind uncalled, and
// the ViewModel went on maintaining the state that fed it — enumerating the backup directory and
// checking for a pending restore off the main thread at every start, for a card no screen showed.
// This fork's backup story is its own: the app-state Export/Import, the sister-app backup window,
// and the adb workspace bridge. Upstream's ConfigurationSnapshot* storage classes are deliberately
// left in place unreferenced — deleting files upstream still develops would turn their clean
// auto-merges into a modify/delete conflict on every sync. (白い熊, 2026-08-14.)
@Composable
internal fun PermissionSetupCard(
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
                PermissionStatusPill(
                    stateLabel,
                    stateColor,
                )
            }
            Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PermissionRequirement(label = if (item.optional) stringResource(R.string.setup_optional_requirement, item.requiredFor) else item.requiredFor)
            if (!item.granted && item.action !is PermissionAction.None) {
                Button(onClick = onRunAction, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(item.actionLabel)
                }
            } else if (item.granted && item.action !is PermissionAction.None) {
                // Granted: keep a link to the relevant Settings page so it can be reviewed / toggled off.
                OutlinedButton(onClick = onRunAction, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.setup_review_settings))
                }
            }
        }
    }
}

@Composable
private fun PermissionMetric(value: String, label: String, modifier: Modifier = Modifier) {
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
private fun PermissionStatusPill(label: String, color: Color) {
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
        )
    }
}

@Composable
private fun PermissionRequirement(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

internal fun buildPermissionItems(
    context: Context,
    localeSwitchUsed: Boolean = com.opentasker.core.capabilities.LocaleSwitchUse.used,
): List<PermissionSetupItem> {
    val changeConfigurationGranted = hasPermission(context, Manifest.permission.CHANGE_CONFIGURATION)
    val shizukuStatus = ShizukuPowerBackend.inspect(context)
    val shizukuRunning = ShizukuShell.isRunning()
    val shizukuReady = shizukuRunning && ShizukuShell.hasPermission()
    val termuxStatus = TermuxScriptBackend.inspect(context)
    val oem = OemBatteryGuidance.forDevice(Build.MANUFACTURER, Build.BRAND)
    return listOfNotNull(
        PermissionSetupItem(
            title = tr("Notifications"),
            body = tr("Required for foreground-service visibility and user-facing notification actions on Android 13 and newer."),
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            actionLabel = tr("Request"),
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionAction.None
            },
            requiredFor = tr("Foreground service, notification actions"),
        ),
        PermissionSetupItem(
            title = tr("Microphone"),
            body = tr("Required for the voice-recording actions (e.g. the 物理鍵 record-on-keypress task)."),
            granted = hasPermission(context, Manifest.permission.RECORD_AUDIO),
            actionLabel = tr("Request"),
            action = PermissionAction.RuntimePermission(Manifest.permission.RECORD_AUDIO),
            requiredFor = tr("Voice recording (audio.record.*)"),
        ),
        PermissionSetupItem(
            title = tr("Device admin (lockdown)"),
            body = tr("Lets the 物理鍵 lockdown action lock the device and require your PIN/password (biometrics disabled until the next credential entry)."),
            granted = run {
                val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                val admin = android.content.ComponentName(context, com.opentasker.core.admin.DeviceAdmin::class.java)
                dpm?.isAdminActive(admin) == true
            },
            actionLabel = tr("Enable"),
            action = PermissionAction.DeviceAdmin,
            requiredFor = tr("物理鍵 システムロック (screen.lockdown)"),
        ),
        PermissionSetupItem(
            title = tr("Exact alarms"),
            body = tr("Allows precise scheduled automations. If denied, 白い熊 自由作業盤 falls back to inexact delivery windows."),
            granted = ExactAlarmSupport.canScheduleExactAlarms(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(ExactAlarmSupport.settingsIntent(context)),
            requiredFor = tr("Time triggers, schedules"),
        ),
        PermissionSetupItem(
            title = tr("Battery optimization"),
            body = tr(
                "OEM and Android battery managers can stop background automation. Exempting 白い熊 自由作業盤 improves reliability. %1\$s",
                tr(oem.summary),
            ),
            granted = ignoresBatteryOptimizations(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            requiredFor = tr("Long-running automation service"),
        ),
        if (oem.needsExtraSteps) PermissionSetupItem(
            title = tr("%1\$s background guidance", oem.oemName),
            body = buildString {
                append(
                    tr(
                        "Detected %1\$s (reliability risk: %2\$s). Battery-optimization exemption alone is often not enough on this OEM.",
                        oem.oemName,
                        oem.riskLevel.label(),
                    ),
                )
                append("\n\n")
                oem.steps.forEachIndexed { index, step -> append("${index + 1}. ${tr(step)}\n") }
                append("\n")
                append(tr("For more help, see %1\$s", oem.dontKillMyAppUrl))
            }.trim(),
            granted = false,
            // Informational only — no action button; the steps above are followed by hand in OEM settings.
            actionLabel = "",
            action = PermissionAction.None,
            requiredFor = tr("Reliable background automation on %1\$s", oem.oemName),
            optional = true,
        ) else null,
        PermissionSetupItem(
            title = tr("Install unknown apps"),
            body = tr(
                "Lets a task install or update an app from an APK file — the remote update of a 携帯 relay phone (“/install” in a SIM's Jami group). An update of 白い熊 自由作業盤 itself, or of an app it installed before, then needs no tap. On Samsung, “Auto Blocker” must be off as well.",
            ),
            granted = context.packageManager.canRequestPackageInstalls(),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = tr("Remote updates (package.install)"),
            optional = true,
        ),
        PermissionSetupItem(
            title = tr("Usage access"),
            body = tr("Needed to detect foreground apps without an accessibility service."),
            granted = UsageAccess.hasUsageStatsAccess(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            requiredFor = tr("Application contexts"),
        ),
        PermissionSetupItem(
            title = tr("Notification access"),
            body = tr("Needed for notification-text triggers and rich notification matching."),
            granted = hasNotificationListenerAccess(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            requiredFor = tr("Notification triggers"),
        ),
        PermissionSetupItem(
            title = tr("Calendar access"),
            body = tr("Needed for local calendar-window triggers. 白い熊 自由作業盤 only emits redacted calendar metadata to matching."),
            granted = hasPermission(context, Manifest.permission.READ_CALENDAR),
            actionLabel = tr("Request"),
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CALENDAR),
            requiredFor = tr("Calendar triggers"),
        ),
        // Fork: the SIM actions (Read SIMs, SIM Status, Run USSD) and the call-state trigger read the
        // phone state, and SIM Status also the card's own number — 携帯's settings show which SIM sits
        // in which slot by it. Both were declared but nothing asked for them, so a new phone had them off.
        PermissionSetupItem(
            title = tr("Phone (SIMs and numbers)"),
            // The second paragraph is for the case 白い熊 met on 2026-11-16: the numbers half was gone,
            // Android would not ask for it, and the permission page showed the permission as on.
            // Dialing joined on 2026-11-17: Run USSD needs CALL_PHONE, which the manifest declared and
            // nothing requested — 携帯's /balance, /forward and /ussd failed on every freshly set-up relay.
            body = tr("Needed to read the SIM slots, their service state and their numbers, and the call state, and to dial — a USSD code is dialled. 携帯 uses it to tell which SIM is in which slot, and for /balance, /forward and /ussd.") +
                "\n\n" +
                tr(
                    "If Request shows no dialog, the app's page in Settings (アプリ情報) opens instead. Under 権限, 電話 (Phone) covers the phone state, the phone numbers and dialing: if it already reads 許可, set it to 許可しない and back to 許可.",
                ),
            granted = hasPermission(context, Manifest.permission.READ_PHONE_STATE) &&
                hasPermission(context, Manifest.permission.READ_PHONE_NUMBERS) &&
                hasPermission(context, Manifest.permission.CALL_PHONE),
            actionLabel = tr("Request"),
            action = PermissionAction.RuntimePermission(
                Manifest.permission.READ_PHONE_STATE,
                requestedWith = listOf(Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE),
            ),
            requiredFor = tr("SIM actions, USSD, call-state trigger, 携帯"),
        ),
        PermissionSetupItem(
            title = tr("Overlay access"),
            body = tr("Needed for scene overlays, freeze bubbles, and other controls displayed over other apps."),
            granted = Settings.canDrawOverlays(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = tr("Scenes, freeze bubbles, overlay UI"),
        ),
        PermissionSetupItem(
            title = tr("Modify system settings"),
            body = tr(
                "Write Settings special access. The locale switch (system.set_locale) needs it — updatePersistentConfiguration enforces this appop on top of CHANGE_CONFIGURATION.",
            ),
            granted = Settings.System.canWrite(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = tr("Locale switch (system.set_locale), brightness, setting.put"),
        ),
        PermissionSetupItem(
            title = tr("Change configuration (locale)"),
            body = tr(
                "System permission for the locale switch. Android has no dialog and no settings page for it: Grant runs the command below through Shizuku when it is running, otherwise through root. Without either, run it once over adb. The grant survives reboots and updates.",
            ) +
                "\npm grant ${context.packageName} android.permission.CHANGE_CONFIGURATION",
            granted = changeConfigurationGranted,
            actionLabel = tr("Grant (Shizuku or root)"),
            // Once granted there is nothing to review — no settings page shows this permission.
            action = if (changeConfigurationGranted) {
                PermissionAction.None
            } else {
                PermissionAction.PrivilegedGrant(
                    "pm grant ${context.packageName} android.permission.CHANGE_CONFIGURATION",
                )
            },
            requiredFor = tr("Locale switch (system.set_locale)"),
            // Required only on a phone whose tasks switch the locale.
            optional = !localeSwitchUsed,
        ),
        PermissionSetupItem(
            title = tr("All files access"),
            body = tr("Needed to read files outside the app — e.g. custom notification tones stored in shared storage (the 通知明滅 Jami tone)."),
            granted = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                },
            ),
            requiredFor = tr("Custom tones, file actions on shared storage"),
        ),
        PermissionSetupItem(
            title = tr("Accessibility service"),
            body = tr("Lets 白い熊 自由作業盤 perform global navigation — Back, Home, Recents, notification/quick-settings panels — used by the edge-bar gestures."),
            granted = ShiroiKumaAccessibilityService.isConnected,
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            requiredFor = tr("Back / Home / Recents actions"),
        ),
        PermissionSetupItem(
            title = tr("Foreground location"),
            body = if (!hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                tr(LocationPolicyDisclosures.foregroundSetupBodyApproximate)
            } else {
                tr(LocationPolicyDisclosures.foregroundSetupBody)
            },
            // Precise only: approximate location cannot read a WiFi name or hold a small geofence, and
            // counting it as ready left those contexts failing behind a green row while the Inspector
            // (LocationPolicyDisclosures.sourceReady) still called location missing.
            granted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION),
            actionLabel = tr("Request"),
            // Android 12+ ignores a request for fine location that does not also ask for coarse.
            action = PermissionAction.RuntimePermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                requestedWith = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
            ),
            requiredFor = tr("Location and WiFi contexts"),
        ),
        PermissionSetupItem(
            title = tr("Nearby WiFi devices"),
            body = tr("Needed on Android 13 and newer for WiFi-aware automations. SSID visibility can still require location access."),
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES),
            actionLabel = tr("Request"),
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                PermissionAction.None
            },
            requiredFor = tr("WiFi contexts"),
        ),
        PermissionSetupItem(
            title = tr("Background location"),
            body = LocationPolicyDisclosures.backgroundSetupBody(Build.VERSION.SDK_INT),
            granted = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            actionLabel = tr("Open app settings"),
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = tr("Background location radius evaluation"),
        ),
        PermissionSetupItem(
            title = tr("Bluetooth connect"),
            body = tr("Needed on Android 12 and newer for Bluetooth device actions and context checks."),
            granted = Build.VERSION.SDK_INT < 31 || hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT),
            actionLabel = tr("Request"),
            action = if (Build.VERSION.SDK_INT >= 31) {
                PermissionAction.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                PermissionAction.None
            },
            requiredFor = tr("Bluetooth actions"),
        ),
        if (Build.VERSION.SDK_INT >= ANDROID_17_API) PermissionSetupItem(
            title = tr("Local network access"),
            body = tr("Android 17+ requires this permission for any LAN device communication (HTTP, Ping, Wake-on-LAN, MQTT, mDNS). Without it, network actions targeting local addresses will fail."),
            granted = hasPermission(context, "android.permission.ACCESS_LOCAL_NETWORK"),
            actionLabel = tr("Request"),
            action = PermissionAction.RuntimePermission("android.permission.ACCESS_LOCAL_NETWORK"),
            requiredFor = tr("LAN network actions"),
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = tr("SMS send"),
            body = tr("Needed before SMS actions can send messages. Keep SMS automations explicit and user-authored."),
            granted = hasPermission(context, Manifest.permission.SEND_SMS),
            actionLabel = tr("Request"),
            action = PermissionAction.RuntimePermission(Manifest.permission.SEND_SMS),
            requiredFor = tr("SMS actions"),
        ) else null,
        PermissionSetupItem(
            title = tr("Do Not Disturb access"),
            body = tr("Needed before 白い熊 自由作業盤 can change interruption filters or DND-related settings."),
            granted = hasNotificationPolicyAccess(context),
            actionLabel = tr("Open settings"),
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            requiredFor = tr("DND actions"),
        ),
        // Fork: this card reports the state ShizukuShell-backed actions REALLY need (binder up +
        // access granted) — shell.run, wake, screenshot, app freeze/unfreeze, the 物理鍵 grabber.
        // Upstream's "power mode" card green-checked on mere installation while its body said
        // "blocked by the kill switch" — a contradiction, and about a backend this fork never uses.
        PermissionSetupItem(
            title = tr("Shizuku"),
            body = when {
                shizukuReady -> tr("Shizuku is running and access is granted — shell commands, screen wake, screenshot, app freeze/unfreeze and the 物理鍵 key grabber are ready.")
                shizukuRunning -> tr("Shizuku is running but 白い熊 自由作業盤 has not been granted access — tap Grant to pop Shizuku's permission dialog.")
                shizukuStatus.managerInstalled -> tr("Shizuku is installed but not running — start it from the Shizuku app.")
                else -> tr("Shizuku is not installed. It powers shell commands, screen wake, screenshot, app freeze/unfreeze and the 物理鍵 key grabber. The button opens 白い熊 Shizuku's GitHub page.")
            },
            granted = shizukuReady,
            actionLabel = when {
                shizukuReady -> tr("Open Shizuku")
                shizukuRunning -> tr("Grant access")
                shizukuStatus.managerInstalled -> tr("Open Shizuku")
                else -> tr("Get 白い熊 Shizuku")
            },
            action = if (shizukuRunning && !shizukuReady) {
                PermissionAction.Custom { ShizukuShell.requestPermission() }
            } else {
                PermissionAction.SettingsIntent(ShizukuPowerBackend.openManagerIntent(context))
            },
            requiredFor = tr("Shell, wake, screenshot, freeze, 物理鍵"),
            optional = true,
        ),
        PermissionSetupItem(
            title = tr("Termux script bridge"),
            // Green only when scripts can actually dispatch (installed + version + RUN_COMMAND granted);
            // the old card green-checked on installation while claiming the feature was unimplemented.
            body = tr("%1\$s Script actions dispatch through Termux's RUN_COMMAND intent.", tr(termuxStatus.summary)),
            granted = termuxStatus.isReady,
            actionLabel = when {
                !termuxStatus.termuxInstalled -> tr("Open setup guide")
                termuxStatus.state == TermuxScriptState.PermissionRequired -> tr("Grant RUN_COMMAND")
                else -> tr("Open app settings")
            },
            action = when {
                !termuxStatus.termuxInstalled ->
                    PermissionAction.SettingsIntent(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxScriptBackend.SETUP_URL)))
                termuxStatus.state == TermuxScriptState.PermissionRequired ->
                    PermissionAction.RuntimePermission(TermuxScriptBackend.RUN_COMMAND_PERMISSION)
                else ->
                    PermissionAction.SettingsIntent(packageDetailsIntent(TermuxScriptBackend.TERMUX_PACKAGE))
            },
            requiredFor = tr("Script actions"),
            optional = true,
        ),
        PermissionSetupItem(
            title = tr("App visibility"),
            body = tr("Android package visibility limits app lookup. If app selection fails, review app-info permissions and future query filters."),
            granted = true,
            actionLabel = tr("Ready"),
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = tr("App launch and app context selection"),
        ),
    )
}

private const val ANDROID_17_API = 37

/** A permission answer sooner than this was given by the system, not by a tap on a dialog. */
private const val SILENT_REFUSAL_MS = 700L

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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

/**
 * Open the device-admin apps list. There's no universal action, so try the AOSP DeviceAdminSettings
 * activity (+ aliases / a common action); if none resolve, fall back to Security settings with a
 * breadcrumb — EMUI hides the list under Security → Advanced → Device admin apps (詳細設定 → 端末管理アプリ).
 */
private fun openDeviceAdminSettings(context: Context, onMessage: (String) -> Unit) {
    val pm = context.packageManager
    val candidates = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DeviceAdminSettingsActivity"),
        Intent().setClassName("com.android.settings", "com.android.settings.DeviceAdminSettings"),
        Intent("android.settings.DEVICE_ADMIN_SETTINGS"),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(pm) != null && runCatching { context.startActivity(intent) }.isSuccess) return
    }
    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    onMessage(tr("Find it under Security → Advanced → Device admin apps (詳細設定 → 端末管理アプリ)."))
}

/**
 * Opens a public project link in a browser.
 *
 * Deliberately not [openSettingsIntent]: that reports a missing handler as "Settings screen is
 * unavailable on this device", which is the wrong sentence entirely when what failed was a link
 * to the repository.
 */
private fun openExternalLink(context: Context, url: String, onMessage: (String) -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ex: ActivityNotFoundException) {
        AppLogger.warn("OpenTasker.Setup", "No activity can open an external link", ex)
        onMessage(context.getString(R.string.setup_link_unavailable))
    } catch (ex: SecurityException) {
        AppLogger.warn("OpenTasker.Setup", "Opening an external link was denied", ex)
        onMessage(context.getString(R.string.setup_link_unavailable))
    }
}

private fun openSettingsIntent(context: Context, intent: Intent, onMessage: (String) -> Unit) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ex: ActivityNotFoundException) {
        onMessage(tr("Settings screen is unavailable on this device: %1\$s", ex.message ?: tr("no handler")))
    } catch (ex: SecurityException) {
        onMessage(tr("Settings screen could not be opened: %1\$s", ex.message ?: tr("permission denied")))
    }
}

private fun OemBatteryGuidance.RiskLevel.label(): String = when (this) {
    OemBatteryGuidance.RiskLevel.LOW -> tr("low")
    OemBatteryGuidance.RiskLevel.MEDIUM -> tr("medium")
    OemBatteryGuidance.RiskLevel.HIGH -> tr("high")
    OemBatteryGuidance.RiskLevel.SEVERE -> tr("severe")
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
            onMessage(tr("Could not open the OEM settings screen directly; opened the online guide instead."))
        }
    } catch (ex: ActivityNotFoundException) {
        onMessage(tr("No app can open the guidance page: %1\$s", ex.message ?: tr("no handler")))
    }
}
