package com.opentasker.automation.action.impl

import android.content.Context
import android.content.Intent
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
                "service" -> context.startService(intent)
                "broadcast" -> context.sendBroadcast(intent)
                else -> context.startActivity(intent)
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
}
