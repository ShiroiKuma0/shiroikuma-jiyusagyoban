package com.opentasker.core.plugins.locale

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.util.LinkedHashMap
import kotlin.coroutines.resume

object LocalePluginContract {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val ACTION_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val ACTION_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY"
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val EXTRA_ACTIVITY_CLASS_NAME = "com.twofortyfouram.locale.intent.extra.ACTIVITY"
    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18
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

enum class LocalePluginConditionState(val resultCode: Int, val serializedName: String) {
    Satisfied(LocalePluginContract.RESULT_CONDITION_SATISFIED, "satisfied"),
    Unsatisfied(LocalePluginContract.RESULT_CONDITION_UNSATISFIED, "unsatisfied"),
    Unknown(LocalePluginContract.RESULT_CONDITION_UNKNOWN, "unknown"),
}

data class LocalePluginConditionResult(
    val state: LocalePluginConditionState,
    val message: String,
) {
    val satisfied: Boolean
        get() = state == LocalePluginConditionState.Satisfied
}

data class LocalePluginDescriptor(
    val packageName: String,
    val label: String,
    val supportsSettings: Boolean,
    val supportsConditions: Boolean,
    val requestedPermissions: List<String>,
    val settingReceiverPermissions: List<String> = emptyList(),
    val conditionReceiverPermissions: List<String> = emptyList(),
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

object LocalePluginConditionResultParser {
    fun parse(resultCode: Int, packageName: String): LocalePluginConditionResult {
        val state = when (resultCode) {
            LocalePluginContract.RESULT_CONDITION_SATISFIED -> LocalePluginConditionState.Satisfied
            LocalePluginContract.RESULT_CONDITION_UNSATISFIED -> LocalePluginConditionState.Unsatisfied
            LocalePluginContract.RESULT_CONDITION_UNKNOWN -> LocalePluginConditionState.Unknown
            else -> LocalePluginConditionState.Unknown
        }
        return LocalePluginConditionResult(
            state = state,
            message = when (state) {
                LocalePluginConditionState.Satisfied -> "Locale plugin condition satisfied for $packageName."
                LocalePluginConditionState.Unsatisfied -> "Locale plugin condition not satisfied for $packageName."
                LocalePluginConditionState.Unknown -> {
                    if (resultCode == LocalePluginContract.RESULT_CONDITION_UNKNOWN) {
                        "Locale plugin condition state is unknown for $packageName."
                    } else {
                        "Locale plugin condition returned unrecognized result code $resultCode for $packageName."
                    }
                }
            },
        )
    }
}

data class LocalePluginConditionCacheKey(
    val packageName: String,
    val bundleValues: Map<String, String>,
)

class LocalePluginConditionStateCache(
    private val maxEntries: Int = 128,
) {
    private val knownStates = object : LinkedHashMap<LocalePluginConditionCacheKey, LocalePluginConditionState>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LocalePluginConditionCacheKey, LocalePluginConditionState>?,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun resolve(
        key: LocalePluginConditionCacheKey,
        rawResult: LocalePluginConditionResult,
    ): LocalePluginConditionResult {
        return when (rawResult.state) {
            LocalePluginConditionState.Satisfied,
            LocalePluginConditionState.Unsatisfied -> {
                knownStates[key] = rawResult.state
                rawResult
            }
            LocalePluginConditionState.Unknown -> {
                val lastKnown = knownStates[key]
                if (lastKnown == null) {
                    rawResult.copy(
                        state = LocalePluginConditionState.Unsatisfied,
                        message = "${rawResult.message} No last known result is available; treating unknown as unsatisfied.",
                    )
                } else {
                    rawResult.copy(
                        state = lastKnown,
                        message = "${rawResult.message} Using last known ${lastKnown.serializedName} result.",
                    )
                }
            }
        }
    }

    companion object {
        val Shared = LocalePluginConditionStateCache()
    }
}

class LocalePluginHost(
    private val appContext: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val conditionStateCache: LocalePluginConditionStateCache = LocalePluginConditionStateCache.Shared,
) {
    suspend fun fireSetting(request: LocalePluginRequest): LocalePluginResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 1_000..30_000) { "Plugin timeout must be between 1000 and 30000 ms." }

        val bundleValues = LocalePluginBundleCodec.decodeStringBundle(request.bundleJson)
        val pluginBundle = LocalePluginBundleCodec.toBundle(bundleValues)
        val target = resolveBroadcastTarget(request.packageName, LocalePluginContract.ACTION_FIRE_SETTING)
        val component = target.component ?: return LocalePluginResult(false, target.message)
        val intent = pluginBroadcastIntent(
            action = LocalePluginContract.ACTION_FIRE_SETTING,
            request = request,
            pluginBundle = pluginBundle,
            target = component,
        )

        return withTimeout(request.timeoutMs) {
            withContext(dispatcher) {
                appContext.sendBroadcast(intent)
                LocalePluginResult(true, "Locale plugin setting dispatched to ${component.flattenToShortString()}.")
            }
        }
    }

    suspend fun queryCondition(request: LocalePluginRequest): LocalePluginConditionResult {
        LocalePluginBundleCodec.validatePackageName(request.packageName)
        require(request.timeoutMs in 1_000..30_000) { "Plugin timeout must be between 1000 and 30000 ms." }

        val bundleValues = LocalePluginBundleCodec.decodeStringBundle(request.bundleJson)
        val pluginBundle = LocalePluginBundleCodec.toBundle(bundleValues)
        val cacheKey = LocalePluginConditionCacheKey(
            packageName = request.packageName,
            bundleValues = bundleValues.toSortedMap(),
        )
        val target = resolveBroadcastTarget(request.packageName, LocalePluginContract.ACTION_QUERY_CONDITION)
        val component = target.component ?: return LocalePluginConditionResult(
                state = LocalePluginConditionState.Unknown,
                message = target.message,
            )
        val intent = pluginBroadcastIntent(
            action = LocalePluginContract.ACTION_QUERY_CONDITION,
            request = request,
            pluginBundle = pluginBundle,
            target = component,
        )

        return withTimeout(request.timeoutMs) {
            withContext(dispatcher) {
                suspendCancellableCoroutine { continuation ->
                    val resultReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent?) {
                            val result = conditionStateCache.resolve(
                                cacheKey,
                                LocalePluginConditionResultParser.parse(resultCode, request.packageName),
                            )
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                    runCatching {
                        appContext.sendOrderedBroadcast(
                            intent,
                            null,
                            resultReceiver,
                            null,
                            Activity.RESULT_CANCELED,
                            null,
                            null,
                        )
                    }.onFailure { throwable ->
                        if (continuation.isActive) {
                            continuation.resume(
                                LocalePluginConditionResult(
                                    state = LocalePluginConditionState.Unknown,
                                    message = "Locale plugin condition query failed for ${request.packageName}: ${throwable.message}",
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun pluginBroadcastIntent(
        action: String,
        request: LocalePluginRequest,
        pluginBundle: Bundle,
        target: ComponentName,
    ): Intent = Intent(action)
        .setComponent(target)
        .putExtra(LocalePluginContract.EXTRA_BUNDLE, pluginBundle)
        .putExtra(LocalePluginContract.EXTRA_BLURB, request.blurb.take(120))

    private fun resolveBroadcastTarget(packageName: String, action: String): LocaleBroadcastTargetResolution {
        val receivers = queryBroadcastReceivers(appContext.packageManager, packageName, action)
        return when (receivers.size) {
            0 -> LocaleBroadcastTargetResolution(
                component = null,
                message = "No Locale execution receiver found for $packageName and $action.",
            )
            1 -> LocaleBroadcastTargetResolution(
                component = receivers.single().component,
                message = "Resolved Locale execution receiver.",
            )
            else -> LocaleBroadcastTargetResolution(
                component = null,
                message = "Multiple Locale execution receivers found for $packageName and $action; refusing ambiguous plugin dispatch.",
            )
        }
    }
}

class LocalePluginDiscovery(private val appContext: Context) {
    fun discover(): List<LocalePluginDescriptor> {
        val pm = appContext.packageManager
        val settings = queryPackages(pm, LocalePluginContract.ACTION_EDIT_SETTING)
        val conditions = queryPackages(pm, LocalePluginContract.ACTION_EDIT_CONDITION)
        val settingReceivers = queryBroadcastReceiversByPackage(pm, LocalePluginContract.ACTION_FIRE_SETTING)
        val conditionReceivers = queryBroadcastReceiversByPackage(pm, LocalePluginContract.ACTION_QUERY_CONDITION)
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
                    settingReceiverPermissions = settingReceivers[packageName].orEmpty(),
                    conditionReceiverPermissions = conditionReceivers[packageName].orEmpty(),
                )
            }
    }

    private fun queryPackages(pm: PackageManager, action: String): Set<String> =
        pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()

    private fun queryBroadcastReceiversByPackage(pm: PackageManager, action: String): Map<String, List<String>> =
        pm.queryBroadcastReceivers(Intent(action), 0)
            .mapNotNull { resolveInfo ->
                val receiverInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                receiverInfo.packageName to receiverInfo.permission.orEmpty()
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, permissions) -> permissions.filter { it.isNotBlank() }.distinct().sorted() }
}

private data class LocaleBroadcastReceiverTarget(
    val component: ComponentName,
)

private data class LocaleBroadcastTargetResolution(
    val component: ComponentName?,
    val message: String,
)

private fun queryBroadcastReceivers(
    pm: PackageManager,
    packageName: String,
    action: String,
): List<LocaleBroadcastReceiverTarget> =
    pm.queryBroadcastReceivers(Intent(action).setPackage(packageName), 0)
        .mapNotNull { resolveInfo ->
            val receiverInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            if (receiverInfo.packageName != packageName) return@mapNotNull null
            LocaleBroadcastReceiverTarget(
                component = ComponentName(packageName, receiverInfo.name.toClassName(packageName)),
            )
        }
        .sortedBy { it.component.className }

private fun String.toClassName(packageName: String): String = when {
    startsWith(".") -> packageName + this
    "." !in this -> "$packageName.$this"
    else -> this
}
