package com.opentasker.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.opentasker.core.permissions.UsageAccess
import com.opentasker.core.scheduling.ExactAlarmSupport

private data class PermissionSetupItem(
    val title: String,
    val body: String,
    val granted: Boolean,
    val actionLabel: String,
    val action: PermissionAction,
    val requiredFor: String,
)

private sealed interface PermissionAction {
    data class RuntimePermission(val permission: String) : PermissionAction
    data class SettingsIntent(val intent: Intent) : PermissionAction
    data object None : PermissionAction
}

@Composable
fun PermissionOnboardingScreen(
    contentPadding: PaddingValues,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
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
    val grantedCount = items.count { it.granted }

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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup checklist", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "$grantedCount of ${items.size} access checks are ready. OpenTasker degrades safely when access is missing; affected actions and contexts may not run until configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items, key = { it.title }) { item ->
            PermissionSetupCard(
                item = item,
                onRunAction = {
                    when (val action = item.action) {
                        PermissionAction.None -> onMessage("${item.title} is already ready.")
                        is PermissionAction.RuntimePermission -> permissionLauncher.launch(action.permission)
                        is PermissionAction.SettingsIntent -> openSettingsIntent(context, action.intent, onMessage)
                    }
                },
            )
        }
    }
}

@Composable
private fun PermissionSetupCard(
    item: PermissionSetupItem,
    onRunAction: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.granted) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f)
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (item.granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (item.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.granted) "Ready" else "Needs setup",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(onClick = {}, label = { Text(item.requiredFor) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.granted) {
                    OutlinedButton(onClick = onRunAction) {
                        Text("Ready")
                    }
                } else {
                    Button(onClick = onRunAction) {
                        Text(item.actionLabel)
                    }
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}

private fun buildPermissionItems(context: Context): List<PermissionSetupItem> {
    return listOf(
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
            title = "Exact alarms",
            body = "Allows precise scheduled automations. If denied, OpenTasker falls back to inexact delivery windows.",
            granted = ExactAlarmSupport.canScheduleExactAlarms(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(ExactAlarmSupport.settingsIntent(context)),
            requiredFor = "Time triggers, schedules",
        ),
        PermissionSetupItem(
            title = "Battery optimization",
            body = "OEM and Android battery managers can stop background automation. Exempting OpenTasker improves reliability.",
            granted = ignoresBatteryOptimizations(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            requiredFor = "Long-running automation service",
        ),
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
            body = "Needed for future notification-text triggers and rich notification matching.",
            granted = hasNotificationListenerAccess(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
            requiredFor = "Notification triggers",
        ),
        PermissionSetupItem(
            title = "Overlay access",
            body = "Needed for scene overlays and controls displayed over other apps.",
            granted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            ),
            requiredFor = "Scenes, overlay UI",
        ),
        PermissionSetupItem(
            title = "Foreground location",
            body = "Needed for location contexts and WiFi SSID visibility on modern Android.",
            granted = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION),
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
            body = "Needed only when geofence automations must work while OpenTasker is not visible.",
            granted = Build.VERSION.SDK_INT < 29 || hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            actionLabel = "Open app settings",
            action = PermissionAction.SettingsIntent(appDetailsIntent(context)),
            requiredFor = "Background geofences",
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
        PermissionSetupItem(
            title = "SMS send",
            body = "Needed before SMS actions can send messages. Keep SMS automations explicit and user-authored.",
            granted = hasPermission(context, Manifest.permission.SEND_SMS),
            actionLabel = "Request",
            action = PermissionAction.RuntimePermission(Manifest.permission.SEND_SMS),
            requiredFor = "SMS actions",
        ),
        PermissionSetupItem(
            title = "Do Not Disturb access",
            body = "Needed before OpenTasker can change interruption filters or DND-related settings.",
            granted = hasNotificationPolicyAccess(context),
            actionLabel = "Open settings",
            action = PermissionAction.SettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)),
            requiredFor = "DND actions",
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
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun openSettingsIntent(context: Context, intent: Intent, onMessage: (String) -> Unit) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (ex: ActivityNotFoundException) {
        onMessage("Settings screen is unavailable on this device: ${ex.message ?: "no handler"}")
    } catch (ex: SecurityException) {
        onMessage("Settings screen could not be opened: ${ex.message ?: "permission denied"}")
    }
}
