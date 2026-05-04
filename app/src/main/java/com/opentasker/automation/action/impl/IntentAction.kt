package com.opentasker.automation.action.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.opentasker.automation.core.ActionDefinition
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult

/**
 * Intent action that fires Android intents.
 * Supports launching apps, services, or sending broadcasts.
 */
class IntentAction(private val context: Context) : ActionDefinition {
    override val id = "intent"
    override val displayName = "Fire Intent"

    override suspend fun execute(config: ActionConfig): ActionResult {
        return try {
            val startTime = System.currentTimeMillis()
            val action = config.config["action"] as String?
            val packageName = config.config["packageName"] as String?
            val className = config.config["className"] as String?
            val intentType = config.config["type"] as String? ?: "activity" // activity, service, broadcast
            if (action.isNullOrBlank() && packageName.isNullOrBlank()) {
                return ActionResult(
                    success = false,
                    message = "Intent action or package must be specified",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
            if (!packageName.isNullOrBlank() && !PACKAGE_PATTERN.matches(packageName)) {
                return ActionResult(false, "Invalid package name", System.currentTimeMillis() - startTime)
            }
            if (!className.isNullOrBlank() && !CLASS_PATTERN.matches(className)) {
                return ActionResult(false, "Invalid class name", System.currentTimeMillis() - startTime)
            }

            val intent = Intent().apply {
                if (action != null) {
                    this.action = action
                }
                if (packageName != null && className != null) {
                    setClassName(packageName, className)
                } else if (packageName != null) {
                    `package` = packageName
                }
            }

            when (intentType.lowercase()) {
                "service" -> {
                    if (packageName.isNullOrBlank() || className.isNullOrBlank()) {
                        return ActionResult(false, "Service intents must be explicit", System.currentTimeMillis() - startTime)
                    }
                    context.startService(intent)
                }
                "broadcast" -> {
                    if (intent.`package`.isNullOrBlank() && intent.component == null) {
                        return ActionResult(false, "Broadcast intents must target a package or component", System.currentTimeMillis() - startTime)
                    }
                    context.sendBroadcast(intent)
                }
                "activity" -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        ?: return ActionResult(false, "No activity can handle this intent", System.currentTimeMillis() - startTime)
                    context.startActivity(intent)
                }
                else -> return ActionResult(false, "Unsupported intent type: $intentType", System.currentTimeMillis() - startTime)
            }

            ActionResult(
                success = true,
                message = "Intent fired ($intentType)",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                message = "Failed to fire intent: ${e.message}",
                executionTimeMs = 0,
                stackTrace = e.stackTraceToString()
            )
        }
    }

    companion object {
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val CLASS_PATTERN = Regex("^[A-Za-z0-9_.$]+$")
    }
}
