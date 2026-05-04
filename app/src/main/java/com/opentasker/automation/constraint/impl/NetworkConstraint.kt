package com.opentasker.automation.constraint.impl

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.opentasker.automation.core.ConstraintDefinition
import com.opentasker.automation.model.ConstraintConfig

/**
 * Network connectivity constraint that checks if device is connected to network.
 * Supports WiFi-only, cellular-only, or any network.
 */
class NetworkConstraint(private val context: Context) : ConstraintDefinition {
    override val id = "network"
    override val displayName = "Network Status"

    override suspend fun evaluate(config: ConstraintConfig): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
            as? ConnectivityManager ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        val networkType = config.config["type"] as String? ?: "any" // "wifi", "cellular", "any"

        return when (networkType.lowercase()) {
            "wifi" -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            "cellular" -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            "any" -> true // Already connected (network != null)
            else -> false
        }
    }
}
