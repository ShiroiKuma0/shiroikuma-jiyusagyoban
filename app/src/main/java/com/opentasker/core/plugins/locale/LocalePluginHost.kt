package com.opentasker.core.plugins.locale

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object LocalePluginContract {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val MAX_BUNDLE_JSON_BYTES = 16 * 1024
}

data class LocalePluginRequest(
    val packageName: String,
    val bundleJson: String = "{}",
    val blurb: String = "",
    val timeoutMs: Long = 5_000,
)

data class LocalePluginResult(
    val success: Boolean,
    val message: String,
)

data class LocalePluginDescriptor(
    val packageName: String,
    val label: String,
    val supportsSettings: Boolean,
    val supportsConditions: Boolean,
    val requestedPermissions: List<String>,
)

object LocalePluginBundleCodec {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun validatePackageName(packageName: String) {
        require(packagePattern.matches(packageName)) { "Invalid plugin package name." }
    }

    fun decodeStringBundle(bundleJson: String): Map<String, String> {
        val trimmed = bundleJson.trim().ifBlank { "{}" }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= LocalePluginContract.MAX_BUNDLE_JSON_BYTES) {
            "Plugin bundle JSON exceeds ${LocalePluginContract.MAX_BUNDLE_JSON_BYTES} bytes."
        }
        val element = Json.parseToJsonElement(trimmed)
        val obj = element as? JsonObject ?: error("Plugin bundle must be a JSON object.")
        return obj.entries.associate { (key, value) ->
            require(key.isNotBlank()) { "Plugin bundle keys must not be blank." }
            val primitive = value as? JsonPrimitive ?: error("Plugin bundle values must be primitive strings, numbers, or booleans.")
            val stringValue = primitive.contentOrNull
                ?: primitive.booleanOrNull?.toString()
                ?: primitive.longOrNull?.toString()
                ?: primitive.intOrNull?.toString()
                ?: primitive.doubleOrNull?.toString()
                ?: error("Plugin bundle values must not be null.")
            key to stringValue
        }
    }

    fun toBundle(values: Map<String, String>): Bundle = Bundle().apply {
        values.entries.sortedBy { it.key }.forEach { (key, value) ->
            putString(key, value)
        }
    }
}

class LocalePluginHost(
    private val appContext: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fireSetting(request: LocalePluginRequest): LocalePluginResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 1_000..30_000) { "Plugin timeout must be between 1000 and 30000 ms." }

        val pluginBundle = LocalePluginBundleCodec.toBundle(
            LocalePluginBundleCodec.decodeStringBundle(request.bundleJson)
        )
        val intent = Intent(LocalePluginContract.ACTION_FIRE_SETTING)
            .setPackage(request.packageName)
            .putExtra(LocalePluginContract.EXTRA_BUNDLE, pluginBundle)
            .putExtra(LocalePluginContract.EXTRA_BLURB, request.blurb.take(120))

        return withTimeout(request.timeoutMs) {
            withContext(dispatcher) {
                appContext.sendBroadcast(intent)
                LocalePluginResult(true, "Locale plugin setting dispatched to ${request.packageName}.")
            }
        }
    }
}

class LocalePluginDiscovery(private val appContext: Context) {
    fun discover(): List<LocalePluginDescriptor> {
        val pm = appContext.packageManager
        val settings = queryPackages(pm, LocalePluginContract.ACTION_EDIT_SETTING)
        val conditions = queryPackages(pm, LocalePluginContract.ACTION_EDIT_CONDITION)
        return (settings + conditions)
            .distinct()
            .sorted()
            .map { packageName ->
                val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName
                val permissions = runCatching {
                    pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions
                        ?.toList()
                        .orEmpty()
                }.getOrDefault(emptyList())
                LocalePluginDescriptor(
                    packageName = packageName,
                    label = label,
                    supportsSettings = packageName in settings,
                    supportsConditions = packageName in conditions,
                    requestedPermissions = permissions.sorted(),
                )
            }
    }

    private fun queryPackages(pm: PackageManager, action: String): Set<String> =
        pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
}
