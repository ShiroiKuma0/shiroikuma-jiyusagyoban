package com.opentasker.automation.constraint.impl

import android.app.ActivityManager
import android.content.Context
import com.opentasker.automation.core.ConstraintDefinition
import com.opentasker.automation.model.ConstraintConfig

/**
 * Screen state constraint that checks if screen is on/off.
 */
class ScreenStateConstraint(private val context: Context) : ConstraintDefinition {
    override val id = "screen_state"
    override val displayName = "Screen State"

    override suspend fun evaluate(config: ConstraintConfig): Boolean {
        val expectedState = config.config["state"] as String? ?: return false // "on" or "off"

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false

        val isScreenOn = powerManager.isInteractive
        
        return when (expectedState.lowercase()) {
            "on" -> isScreenOn
            "off" -> !isScreenOn
            else -> false
        }
    }
}
