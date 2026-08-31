package com.opentasker.ui.screens

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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.opentasker.core.location.LocationPolicyDisclosures
import com.opentasker.core.permissions.OemBatteryGuidance
import kotlinx.coroutines.launch
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.power.ShizukuPowerBackend
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scripting.TermuxScriptBackend
import com.opentasker.core.support.ProjectLinks
import com.opentasker.core.scripting.TermuxScriptState
import com.opentasker.core.shizuku.ShizukuShell
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import com.opentasker.core.icons.TaskIconStore

private data class PermissionSetupItem(
    val title: String,
    val body: String,
    val granted: Boolean,
    val actionLabel: String,
    val action: PermissionAction,
    val requiredFor: String,
    val optional: Boolean = false,
)

private sealed interface PermissionAction {
    data class RuntimePermission(val permission: String) : PermissionAction
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshTick++
    }
    // Device-admin add must be started for-result from the Activity (NOT as a new task, which makes the
    // system's translucent DeviceAdminAdd screen open and instantly finish).
    val deviceAdminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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

    val items = remember(context, refreshTick) { buildPermissionItems(context) }
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
                            if (blockedTasks.isEmpty()) "Task health — all tasks can run"
                            else "Task health — ${blockedTasks.size} task${if (blockedTasks.size == 1) "" else "s"} blocked",
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
                            OutlinedButton(onClick = onOpenTasks) { Text("Show in Tasks") }
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
                            Text("Setup checklist", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "白い熊 自由作業盤 can run with missing access, but affected automations stay gated until setup is complete.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PermissionStatusPill(
                            if (pendingCount == 0) "Ready" else "$pendingCount pending",
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
                onRunAction = {
                    when (val action = item.action) {
                        PermissionAction.None -> onMessage("${item.title} is already ready.")
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
                                permissionLauncher.launch(action.permission)
                            }
                        is PermissionAction.SettingsIntent -> openSettingsIntent(context, action.intent, onMessage)
                        is PermissionAction.Custom -> action.run()
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
                                                "Enable so 物理鍵 lockdown (screen.lockdown) can lock the device.",
                                            ),
                                    )
                                } catch (ex: Exception) {
                                    openDeviceAdminSettings(context, onMessage)
                                }
                            }
                        }
                    }
                },
            )
        }

    }
}

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

private fun buildPermissionItems(context: Context): List<PermissionSetupItem> {
    val shizukuStatus = ShizukuPowerBackend.inspect(context)
    val shizukuRunning = ShizukuShell.isRunning()
    val shizukuReady = shizukuRunning && ShizukuShell.hasPermission()
    val termuxStatus = TermuxScriptBackend.inspect(context)
    val oem = OemBatteryGuidance.forDevice(Build.MANUFACTURER, Build.BRAND)
    return listOfNotNull(
        PermissionSetupItem(
            title = "Notifications",
            body = "Required for foreground-service visibility and user-facing notification actions on Android 13 and newer.",
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            actionLabel = "Request",
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PermissionAction.None
            },
            requiredFor = "Foreground service, notification actions",
        ),
        PermissionSetupItem(
            title = "Microphone",
            body = "Required for the voice-recording actions (e.g. the 物理鍵 record-on-keypress task).",
            granted = hasPermission(context, Manifest.permission.RECORD_AUDIO),
            actionLabel = "Request",
            action = PermissionAction.RuntimePermission(Manifest.permission.RECORD_AUDIO),
            requiredFor = "Voice recording (audio.record.*)",
        ),
        PermissionSetupItem(
            title = "Device admin (lockdown)",
            body = "Lets the 物理鍵 lockdown action lock the device and require your PIN/password (biometrics disabled until the next credential entry).",
            granted = run {
                val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                val admin = android.content.ComponentName(context, com.opentasker.core.admin.DeviceAdmin::class.java)
                dpm?.isAdminActive(admin) == true
            },
            actionLabel = "Enable",
            action = PermissionAction.DeviceAdmin,
            requiredFor = "物理鍵 システムロック (screen.lockdown)",
        ),
        PermissionSetupItem(
            title = "Exact alarms",
            body = "Allows precise scheduled automations. If denied, 白い熊 自由作業盤 falls back to inexact delivery windows.",
            granted = ExactAlarmSupport.canScheduleExactAlarms(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(ExactAlarmSupport.settingsIntent(context)),
            requiredFor = "Time triggers, schedules",
        ),
        PermissionSetupItem(
            title = "Battery optimization",
            body = "OEM and Android battery managers can stop background automation. Exempting 白い熊 自由作業盤 improves reliability. " +
                oem.summary,
            granted = ignoresBatteryOptimizations(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            requiredFor = "Long-running automation service",
        ),
        if (oem.needsExtraSteps) PermissionSetupItem(
            title = "${oem.oemName} background guidance",
            body = buildString {
                append("Detected ${oem.oemName} (reliability risk: ${oem.riskLevel.label()}). ")
                append("Battery-optimization exemption alone is often not enough on this OEM.\n\n")
                oem.steps.forEachIndexed { index, step -> append("${index + 1}. $step\n") }
                append("\nFor more help, see ${oem.dontKillMyAppUrl}")
            }.trim(),
            granted = false,
            // Informational only — no action button; the steps above are followed by hand in OEM settings.
            actionLabel = "",
            action = PermissionAction.None,
            requiredFor = "Reliable background automation on ${oem.oemName}",
            optional = true,
        ) else null,
        PermissionSetupItem(
            title = "Usage access",
            body = "Needed to detect foreground apps without an accessibility service.",
            granted = UsageAccess.hasUsageStatsAccess(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)),
            requiredFor = "Application contexts",
        ),
        PermissionSetupItem(
            title = "Notification access",
            body = "Needed for notification-text triggers and rich notification matching.",
            granted = hasNotificationListenerAccess(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            requiredFor = "Notification triggers",
        ),
        PermissionSetupItem(
            title = "Calendar access",
            body = "Needed for local calendar-window triggers. 白い熊 自由作業盤 only emits redacted calendar metadata to matching.",
            granted = hasPermission(context, Manifest.permission.READ_CALENDAR),
            actionLabel = "Request",
            action = PermissionAction.RuntimePermission(Manifest.permission.READ_CALENDAR),
            requiredFor = "Calendar triggers",
        ),
        PermissionSetupItem(
            title = "Overlay access",
            body = "Needed for scene overlays, freeze bubbles, and other controls displayed over other apps.",
            granted = Settings.canDrawOverlays(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = "Scenes, freeze bubbles, overlay UI",
        ),
        PermissionSetupItem(
            title = "Modify system settings",
            body = "Write Settings special access. The locale switch (system.set_locale) needs it — " +
                "updatePersistentConfiguration enforces this appop on top of CHANGE_CONFIGURATION.",
            granted = Settings.System.canWrite(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = "Locale switch (system.set_locale), brightness, setting.put",
        ),
        PermissionSetupItem(
            title = "Change configuration (locale)",
            body = "Dev-flagged system permission for the locale switch; only adb can grant it, once, surviving reboots:\n" +
                "pm grant ${context.packageName} android.permission.CHANGE_CONFIGURATION",
            granted = hasPermission(context, Manifest.permission.CHANGE_CONFIGURATION),
            // adb-only — no settings page to deep-link.
            actionLabel = "",
            action = PermissionAction.None,
            requiredFor = "Locale switch (system.set_locale)",
        ),
        PermissionSetupItem(
            title = "All files access",
            body = "Needed to read files outside the app — e.g. custom notification tones stored in shared storage (the 通知明滅 Jami tone).",
            granted = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                },
            ),
            requiredFor = "Custom tones, file actions on shared storage",
        ),
        PermissionSetupItem(
            title = "Accessibility service",
            body = "Lets 白い熊 自由作業盤 perform global navigation — Back, Home, Recents, notification/quick-settings panels — used by the edge-bar gestures.",
            granted = ShiroiKumaAccessibilityService.isConnected,
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            requiredFor = "Back / Home / Recents actions",
        ),
        PermissionSetupItem(
            title = "Foreground location",
            body = LocationPolicyDisclosures.foregroundSetupBody,
            granted = hasAnyLocationPermission(context),
            actionLabel = "Request",
            action = PermissionAction.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
            requiredFor = "Location and WiFi contexts",
        ),
        PermissionSetupItem(
            title = "Nearby WiFi devices",
            body = "Needed on Android 13 and newer for WiFi-aware automations. SSID visibility can still require location access.",
            granted = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES),
            actionLabel = "Request",
            action = if (Build.VERSION.SDK_INT >= 33) {
                PermissionAction.RuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                PermissionAction.None
            },
            requiredFor = "WiFi contexts",
        ),
        PermissionSetupItem(
            title = "Background location",
            body = LocationPolicyDisclosures.backgroundSetupBody(Build.VERSION.SDK_INT),
            granted = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            actionLabel = "Open app settings",
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = "Background location radius evaluation",
        ),
        PermissionSetupItem(
            title = "Bluetooth connect",
            body = "Needed on Android 12 and newer for Bluetooth device actions and context checks.",
            granted = Build.VERSION.SDK_INT < 31 || hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT),
            actionLabel = "Request",
            action = if (Build.VERSION.SDK_INT >= 31) {
                PermissionAction.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                PermissionAction.None
            },
            requiredFor = "Bluetooth actions",
        ),
        if (Build.VERSION.SDK_INT >= ANDROID_17_API) PermissionSetupItem(
            title = "Local network access",
            body = "Android 17+ requires this permission for any LAN device communication (HTTP, Ping, Wake-on-LAN, MQTT, mDNS). Without it, network actions targeting local addresses will fail.",
            granted = hasPermission(context, "android.permission.ACCESS_LOCAL_NETWORK"),
            actionLabel = "Request",
            action = PermissionAction.RuntimePermission("android.permission.ACCESS_LOCAL_NETWORK"),
            requiredFor = "LAN network actions",
        ) else null,
        if (BuildConfig.SMS_ACTION_AVAILABLE) PermissionSetupItem(
            title = "SMS send",
            body = "Needed before SMS actions can send messages. Keep SMS automations explicit and user-authored.",
            granted = hasPermission(context, Manifest.permission.SEND_SMS),
            actionLabel = "Request",
            action = PermissionAction.RuntimePermission(Manifest.permission.SEND_SMS),
            requiredFor = "SMS actions",
        ) else null,
        PermissionSetupItem(
            title = "Do Not Disturb access",
            body = "Needed before 白い熊 自由作業盤 can change interruption filters or DND-related settings.",
            granted = hasNotificationPolicyAccess(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            requiredFor = "DND actions",
        ),
        // Fork: this card reports the state ShizukuShell-backed actions REALLY need (binder up +
        // access granted) — shell.run, wake, screenshot, app freeze/unfreeze, the 物理鍵 grabber.
        // Upstream's "power mode" card green-checked on mere installation while its body said
        // "blocked by the kill switch" — a contradiction, and about a backend this fork never uses.
        PermissionSetupItem(
            title = "Shizuku",
            body = when {
                shizukuReady -> "Shizuku is running and access is granted — shell commands, screen wake, screenshot, app freeze/unfreeze and the 物理鍵 key grabber are ready."
                shizukuRunning -> "Shizuku is running but 白い熊 自由作業盤 has not been granted access — tap Grant to pop Shizuku's permission dialog."
                shizukuStatus.managerInstalled -> "Shizuku is installed but not running — start it from the Shizuku app."
                else -> "Shizuku is not installed. It powers shell commands, screen wake, screenshot, app freeze/unfreeze and the 物理鍵 key grabber. The button opens 白い熊 Shizuku's GitHub page."
            },
            granted = shizukuReady,
            actionLabel = when {
                shizukuReady -> "Open Shizuku"
                shizukuRunning -> "Grant access"
                shizukuStatus.managerInstalled -> "Open Shizuku"
                else -> "Get 白い熊 Shizuku"
            },
            action = if (shizukuRunning && !shizukuReady) {
                PermissionAction.Custom { ShizukuShell.requestPermission() }
            } else {
                PermissionAction.SettingsIntent(ShizukuPowerBackend.openManagerIntent(context))
            },
            requiredFor = "Shell, wake, screenshot, freeze, 物理鍵",
            optional = true,
        ),
        PermissionSetupItem(
            title = "Termux script bridge",
            // Green only when scripts can actually dispatch (installed + version + RUN_COMMAND granted);
            // the old card green-checked on installation while claiming the feature was unimplemented.
            body = "${termuxStatus.summary} Script actions dispatch through Termux's RUN_COMMAND intent.",
            granted = termuxStatus.isReady,
            actionLabel = when {
                !termuxStatus.termuxInstalled -> "Open setup guide"
                termuxStatus.state == TermuxScriptState.PermissionRequired -> "Grant RUN_COMMAND"
                else -> "Open app settings"
            },
            action = when {
                !termuxStatus.termuxInstalled ->
                    PermissionAction.SettingsIntent(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxScriptBackend.SETUP_URL)))
                termuxStatus.state == TermuxScriptState.PermissionRequired ->
                    PermissionAction.RuntimePermission(TermuxScriptBackend.RUN_COMMAND_PERMISSION)
                else ->
                    PermissionAction.SettingsIntent(packageDetailsIntent(TermuxScriptBackend.TERMUX_PACKAGE))
            },
            requiredFor = "Script actions",
            optional = true,
        ),
        PermissionSetupItem(
            title = "App visibility",
            body = "Android package visibility limits app lookup. If app selection fails, review app-info permissions and future query filters.",
            granted = true,
            actionLabel = "Ready",
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = "App launch and app context selection",
        ),
    )
}

private const val ANDROID_17_API = 37

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
    onMessage("Find it under Security → Advanced → Device admin apps (詳細設定 → 端末管理アプリ).")
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
        onMessage("Settings screen is unavailable on this device: ${ex.message ?: "no handler"}")
    } catch (ex: SecurityException) {
        onMessage("Settings screen could not be opened: ${ex.message ?: "permission denied"}")
    }
}

private fun OemBatteryGuidance.RiskLevel.label(): String = when (this) {
    OemBatteryGuidance.RiskLevel.LOW -> "low"
    OemBatteryGuidance.RiskLevel.MEDIUM -> "medium"
    OemBatteryGuidance.RiskLevel.HIGH -> "high"
    OemBatteryGuidance.RiskLevel.SEVERE -> "severe"
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
            onMessage("Could not open the OEM settings screen directly; opened the online guide instead.")
        }
    } catch (ex: ActivityNotFoundException) {
        onMessage("No app can open the guidance page: ${ex.message ?: "no handler"}")
    }
}
