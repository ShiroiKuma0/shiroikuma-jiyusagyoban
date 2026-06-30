package com.opentasker.core.capabilities

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.power.ShizukuPowerBackend

/**
 * Live, per-device evaluation of a [CapabilityRequirement] — is the underlying permission / service
 * currently granted, what settings deep-link fixes it, and a short button label. Lets the action editor
 * colour a capability note red (not granted, with a fix button) vs. amber (granted, FYI only).
 */
object CapabilityState {

    /** True when the requirement is currently satisfied on this device. */
    fun isMet(req: CapabilityRequirement, context: Context): Boolean = when (req) {
        CapabilityRequirement.None -> true
        // The SAME checks the Setup tab uses (which already report these as Detected there).
        CapabilityRequirement.Accessibility -> ShiroiKumaAccessibilityService.isConnected
        CapabilityRequirement.Shizuku -> ShizukuPowerBackend.inspect(context).managerInstalled
        CapabilityRequirement.WriteSettings -> Settings.System.canWrite(context)
        CapabilityRequirement.Overlay -> Settings.canDrawOverlays(context)
        CapabilityRequirement.PostNotifications ->
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        CapabilityRequirement.NotificationListener ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        CapabilityRequirement.Dnd ->
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        CapabilityRequirement.AllFiles ->
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        CapabilityRequirement.DeviceAdmin -> {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            dpm?.isAdminActive(android.content.ComponentName(context, com.opentasker.core.admin.DeviceAdmin::class.java)) == true
        }
        CapabilityRequirement.Microphone ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /** The settings screen that grants [req], or null when there is nothing to deep-link (e.g. [CapabilityRequirement.None]). */
    fun settingsIntent(req: CapabilityRequirement, context: Context): Intent? {
        val intent = when (req) {
            CapabilityRequirement.None -> null
            CapabilityRequirement.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            CapabilityRequirement.WriteSettings ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName))
            CapabilityRequirement.Overlay ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            CapabilityRequirement.NotificationListener -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            CapabilityRequirement.Dnd -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            CapabilityRequirement.PostNotifications ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            CapabilityRequirement.Shizuku ->
                context.packageManager.getLaunchIntentForPackage(ShizukuPowerBackend.MANAGER_PACKAGE)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuPowerBackend.SETUP_URL))
            CapabilityRequirement.AllFiles ->
                if (Build.VERSION.SDK_INT >= 30)
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
                else
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            CapabilityRequirement.DeviceAdmin ->
                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    android.content.ComponentName(context, com.opentasker.core.admin.DeviceAdmin::class.java),
                )
            CapabilityRequirement.Microphone ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** A neutral one-line statement of what the action needs (no imperative "go enable it" tone). */
    fun requirementNote(req: CapabilityRequirement): String = when (req) {
        CapabilityRequirement.None -> ""
        CapabilityRequirement.Accessibility -> "Needs the accessibility service enabled in System Settings."
        CapabilityRequirement.Shizuku -> "Needs Shizuku installed and running."
        CapabilityRequirement.WriteSettings -> "Needs the Modify system settings permission."
        CapabilityRequirement.Overlay -> "Needs the display-over-other-apps permission."
        CapabilityRequirement.PostNotifications -> "Needs notification permission."
        CapabilityRequirement.NotificationListener -> "Needs notification access."
        CapabilityRequirement.Dnd -> "Needs Do Not Disturb access."
        CapabilityRequirement.AllFiles -> "Needs All files access to read files outside the app (e.g. tones in shared storage)."
        CapabilityRequirement.DeviceAdmin -> "Needs Device admin enabled."
        CapabilityRequirement.Microphone -> "Needs the Microphone permission."
    }

    /** Short status-pill text for the current state of [req] (granted vs. not). */
    fun statusLabel(req: CapabilityRequirement, met: Boolean): String = when (req) {
        CapabilityRequirement.None -> ""
        CapabilityRequirement.Accessibility -> if (met) "Accessibility enabled" else "Accessibility off"
        CapabilityRequirement.Shizuku -> if (met) "Shizuku detected" else "Shizuku not installed"
        CapabilityRequirement.WriteSettings -> if (met) "Modify settings allowed" else "Modify settings off"
        CapabilityRequirement.Overlay -> if (met) "Display over apps allowed" else "Display over apps off"
        CapabilityRequirement.PostNotifications -> if (met) "Notifications allowed" else "Notifications off"
        CapabilityRequirement.NotificationListener -> if (met) "Notification access on" else "Notification access off"
        CapabilityRequirement.Dnd -> if (met) "Do Not Disturb access on" else "Do Not Disturb access off"
        CapabilityRequirement.AllFiles -> if (met) "All files access on" else "All files access off"
        CapabilityRequirement.DeviceAdmin -> if (met) "Device admin on" else "Device admin off"
        CapabilityRequirement.Microphone -> if (met) "Microphone allowed" else "Microphone off"
    }

    /** Short button text for the fix action. */
    fun fixLabel(req: CapabilityRequirement): String = when (req) {
        CapabilityRequirement.None -> "Open settings"
        CapabilityRequirement.Accessibility -> "Enable accessibility"
        CapabilityRequirement.Shizuku -> "Set up Shizuku"
        CapabilityRequirement.WriteSettings -> "Allow modify settings"
        CapabilityRequirement.Overlay -> "Allow display over apps"
        CapabilityRequirement.NotificationListener -> "Enable notification access"
        CapabilityRequirement.Dnd -> "Grant Do Not Disturb access"
        CapabilityRequirement.PostNotifications -> "Grant notification access"
        CapabilityRequirement.AllFiles -> "Grant All files access"
        CapabilityRequirement.DeviceAdmin -> "Enable device admin"
        CapabilityRequirement.Microphone -> "Grant microphone"
    }

    /** Short noun for a permission, for the run-time block dialog (“needs: Accessibility”). */
    fun shortLabel(req: CapabilityRequirement): String = when (req) {
        CapabilityRequirement.None -> ""
        CapabilityRequirement.Accessibility -> "Accessibility"
        CapabilityRequirement.Shizuku -> "Shizuku"
        CapabilityRequirement.WriteSettings -> "Modify system settings"
        CapabilityRequirement.Overlay -> "Display over other apps"
        CapabilityRequirement.PostNotifications -> "Notifications"
        CapabilityRequirement.NotificationListener -> "Notification access"
        CapabilityRequirement.Dnd -> "Do Not Disturb access"
        CapabilityRequirement.AllFiles -> "All files access"
        CapabilityRequirement.DeviceAdmin -> "Device admin"
        CapabilityRequirement.Microphone -> "Microphone"
    }

    /** A missing, blocking permission and the action types in the task that need it. */
    data class MissingCapability(val requirement: CapabilityRequirement, val actionTypes: List<String>)

    /**
     * Permissions a task needs that are BLOCKING and not currently granted. Empty = the task may run.
     * Used as a pre-flight gate so a task never runs half-broken for lack of a permission.
     */
    fun missingForTask(task: com.opentasker.core.model.Task, context: Context): List<MissingCapability> {
        val byReq = LinkedHashMap<CapabilityRequirement, MutableList<String>>()
        for (action in task.actions) {
            val cap = ActionCapabilityRegistry.get(action.type)
            if (cap.requirement != CapabilityRequirement.None && cap.blocking && !isMet(cap.requirement, context)) {
                byReq.getOrPut(cap.requirement) { mutableListOf() }.add(action.type)
            }
        }
        return byReq.map { (req, types) -> MissingCapability(req, types.distinct()) }
    }
}
