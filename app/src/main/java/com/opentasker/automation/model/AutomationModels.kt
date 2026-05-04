package com.opentasker.automation.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.UUID

/**
 * Core automation models for OpenTasker v0.5.0+
 * Inspired by orkestr's architecture but tailored for OpenTasker
 */

// ============================================================================
// EVENTS - Sealed class hierarchy for all automation events
// ============================================================================

sealed class AutomationEvent {
    abstract val timestamp: Long

    data class TimeEvent(override val timestamp: Long) : AutomationEvent()

    data class GeofenceEvent(
        val latitude: Double,
        val longitude: Double,
        val eventType: String, // "enter" or "exit"
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class WiFiEvent(
        val ssid: String,
        val connected: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class BatteryEvent(
        val level: Int, // 0-100
        val status: String, // "charging", "discharging", "full"
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class AppEvent(
        val packageName: String,
        val opened: Boolean, // true = app opened, false = app closed
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class NotificationEvent(
        val packageName: String,
        val title: String,
        val text: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class SMSEvent(
        val fromNumber: String,
        val messageText: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class CallEvent(
        val fromNumber: String,
        val callType: String, // "incoming", "outgoing", "missed"
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class BootEvent(
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class NFCEvent(
        val tagId: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class BluetoothEvent(
        val deviceAddress: String,
        val deviceName: String,
        val connected: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()

    data class LocaleChangeEvent(
        val locale: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AutomationEvent()
}

// ============================================================================
// CONFIGURATION MODELS - What users configure for triggers/constraints/actions
// ============================================================================

@Parcelize
data class TriggerConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "time", "geofence", "wifi", "battery", etc.
    val displayName: String = "", // User-friendly name
    val enabled: Boolean = true,
    @Transient
    val config: Map<String, Any> = emptyMap() // Runtime configuration map (not serialized)
) : Parcelable

@Parcelize
data class ConstraintConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "battery", "time_range", "location", "wifi", etc.
    val displayName: String = "",
    val enabled: Boolean = true,
    @Transient
    val config: Map<String, Any> = emptyMap()
) : Parcelable

@Parcelize
data class ActionConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "notification", "intent", "toggle_wifi", etc.
    val displayName: String = "",
    val enabled: Boolean = true,
    @Transient
    val config: Map<String, Any> = emptyMap()
) : Parcelable

// ============================================================================
// CONSTRAINT GROUPS - Boolean logic for constraints (AND/OR)
// ============================================================================

enum class LogicalOperator {
    AND,  // All constraints must be satisfied
    OR    // At least one constraint must be satisfied
}

@Parcelize
data class ConstraintGroup(
    val id: String = UUID.randomUUID().toString(),
    val operator: LogicalOperator = LogicalOperator.AND,
    val constraints: List<ConstraintConfig> = emptyList(),
    val subgroups: List<ConstraintGroup> = emptyList()
) : Parcelable

// ============================================================================
// EXECUTION MODE - How actions are executed
// ============================================================================

enum class ExecutionMode {
    PARALLEL,      // Execute all actions concurrently
    SEQUENTIAL     // Execute actions in order, waiting for each to complete
}

// ============================================================================
// AUTOMATION RULE - The main entity: triggers + constraints + actions
// ============================================================================

@Parcelize
data class AutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val profileId: String, // Which profile this rule belongs to
    val triggers: List<TriggerConfig>, // 1+ triggers (any can trigger the rule)
    val constraints: List<ConstraintConfig> = emptyList(), // 0+ constraints (all must be satisfied)
    val constraintGroups: List<ConstraintGroup> = emptyList(), // Advanced: AND/OR logic
    val actions: List<ActionConfig>, // 1+ actions
    val executionMode: ExecutionMode = ExecutionMode.PARALLEL,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

// ============================================================================
// TEMPLATES - Reusable groups of triggers/constraints/actions
// ============================================================================

@Parcelize
data class TriggerGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val triggers: List<TriggerConfig>,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class ConstraintGroupTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val constraints: List<ConstraintConfig>,
    val operator: LogicalOperator = LogicalOperator.AND,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

@Parcelize
data class ActionGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val actions: List<ActionConfig>,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable

// ============================================================================
// EXECUTION RESULT - Result of executing a single action
// ============================================================================

data class ActionResult(
    val success: Boolean,
    val message: String,
    val executionTimeMs: Long,
    val stackTrace: String? = null,
    val details: Map<String, Any>? = null
)

// ============================================================================
// EXECUTION LOG - Persisted record of rule executions
// ============================================================================

enum class ExecutionStatus {
    SUCCESS,  // Rule executed successfully
    SKIPPED,  // Rule matched but constraints not satisfied
    FAILURE   // Rule tried to execute but action(s) failed
}

data class ExecutionLog(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val ruleName: String,
    val triggerId: String? = null,
    val triggerType: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val status: ExecutionStatus,
    val message: String, // Summary of what happened
    val executionTimeMs: Long,
    val actionResults: List<Pair<String, ActionResult>> = emptyList() // action ID -> result
)
