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
        // Coarse is enough to satisfy the action: it falls back to whatever fix it can get, and a
        // town-level position is all the history needs.
        CapabilityRequirement.Location ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // BLUETOOTH_CONNECT only exists from API 31; below that the legacy manifest permissions are
        // install-time and always held, so there is nothing for the user to grant.
        CapabilityRequirement.Bluetooth ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Badge-truth variant of [isMet]: for Shizuku it verifies the binder is actually up AND access is
     * granted (what ShizukuShell-backed actions really need), not merely that the manager app is
     * installed. [isMet] stays lenient on purpose — it also gates the run-time pre-flight dialog, and a
     * boot-race (task fires before Shizuku's binder is up) must degrade to a run-log failure, not a
     * dialog storm. UI badges have no such constraint and must tell the truth.
     */
    fun isMetLive(req: CapabilityRequirement, context: Context): Boolean = when (req) {
        CapabilityRequirement.Shizuku -> com.opentasker.core.shizuku.ShizukuShell.available()
        else -> isMet(req, context)
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
            CapabilityRequirement.Shizuku -> ShizukuPowerBackend.openManagerIntent(context)
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
            CapabilityRequirement.Location ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            CapabilityRequirement.Bluetooth ->
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
        CapabilityRequirement.Location -> "Needs the Location permission."
        CapabilityRequirement.Bluetooth -> "Needs the Nearby devices (Bluetooth) permission."
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
        CapabilityRequirement.Location -> if (met) "Location allowed" else "Location off"
        CapabilityRequirement.Bluetooth -> if (met) "Bluetooth allowed" else "Bluetooth off"
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
        CapabilityRequirement.Location -> "Grant location"
        CapabilityRequirement.Bluetooth -> "Grant Bluetooth"
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
        CapabilityRequirement.Location -> "Location"
        CapabilityRequirement.Bluetooth -> "Bluetooth"
    }

    /**
     * The extra sentence a block dialog needs when "you have not granted it" is simply untrue.
     *
     * Accessibility is the case that forced this (白い熊, 2026-08-08). The service was ON in system
     * settings and the framework had it under `Crashed services` with nothing bound, so the app was
     * right to block — and the dialog told 白い熊 to go and enable a thing they had already enabled,
     * which sent them looking for a fault that was not there. Null when the ordinary story is correct.
     */
    fun blockedDetail(req: CapabilityRequirement, context: Context): String? = when (req) {
        CapabilityRequirement.Accessibility ->
            if (ShiroiKumaAccessibilityService.isEnabledButNotRunning(context)) {
                "It IS switched on in System Settings — but Android is not running it. That happens " +
                    "when the service is killed and the system marks it crashed; it will not start " +
                    "again on its own.\n\nFix: open Accessibility settings below, turn 白い熊 自由作業盤 " +
                    "OFF and then ON again."
            } else {
                null
            }
        else -> null
    }

    /** A missing, blocking permission and the action types in the task that need it. */
    data class MissingCapability(val requirement: CapabilityRequirement, val actionTypes: List<String>)

    /**
     * Permissions a task needs that are BLOCKING and not currently granted. Empty = the task may run.
     * Used as a pre-flight gate so a task never runs half-broken for lack of a permission.
     */
    suspend fun missingForTask(task: com.opentasker.core.model.Task, context: Context): List<MissingCapability> {
        val byReq = LinkedHashMap<CapabilityRequirement, MutableList<String>>()
        for (action in task.actions) {
            val cap = ActionCapabilityRegistry.get(action.type)
            if (cap.requirement != CapabilityRequirement.None && cap.blocking && !isMet(cap.requirement, context)) {
                byReq.getOrPut(cap.requirement) { mutableListOf() }.add(action.type)
            }
        }
        // Tolerate the accessibility unbind→rebind transient: EMUI drops the live binding across a
        // configuration change (a locale switch) or a memory-pressure reap while the system toggle stays
        // on, so a task firing in that ~1–2 s window would otherwise be blocked spuriously. Wait for the
        // rebind and drop the requirement if it comes back; a genuinely-off toggle returns at once.
        if (byReq.containsKey(CapabilityRequirement.Accessibility) &&
            ShiroiKumaAccessibilityService.awaitConnected(context)
        ) {
            byReq.remove(CapabilityRequirement.Accessibility)
        }
        return byReq.map { (req, types) -> MissingCapability(req, types.distinct()) }
    }
}
