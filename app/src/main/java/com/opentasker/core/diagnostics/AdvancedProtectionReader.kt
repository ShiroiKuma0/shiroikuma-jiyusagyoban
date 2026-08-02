package com.opentasker.core.diagnostics

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Detects Android 16 (API 36) Advanced Protection Mode. When enabled, APM revokes Accessibility
 * from apps that do not declare the "Accessibility Tool" use and restricts other automation-class
 * capabilities, so an automation app should surface it instead of failing opaquely.
 *
 * The platform API is read via reflection so the app compiles and fails closed on any device or SDK
 * where the manager or method is unavailable (returning "not enabled" rather than crashing). Below
 * API 36 the check is a no-op.
 */
object AdvancedProtectionReader {
    private const val ADVANCED_PROTECTION_SERVICE = "advanced_protection"
    private const val MANAGER_CLASS = "android.security.advancedprotection.AdvancedProtectionManager"
    private const val CALLBACK_CLASS = "$MANAGER_CLASS\$Callback"
    private const val IS_ENABLED_METHOD = "isAdvancedProtectionEnabled"
    private const val REGISTER_METHOD = "registerAdvancedProtectionCallback"
    private const val UNREGISTER_METHOD = "unregisterAdvancedProtectionCallback"

    private val enabled_ = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabled_.asStateFlow()

    private val changes_ = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    val changes: SharedFlow<Boolean> = changes_.asSharedFlow()

    private var registeredManager: ManagerHandle? = null
    private var registeredCallback: Any? = null

    /** True only when running on API 36+ with Advanced Protection Mode actively enabled. */
    fun isEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_16_API) return false
        return runCatching {
            val handle = managerHandle(context) ?: return@runCatching false
            readEnabled(handle)
        }.getOrDefault(false)
    }

    /** Registers the API 36 callback, returning false when the service, permission, or API is unavailable. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_16_API) return true
        if (registeredCallback != null) return true

        val handle = managerHandle(context) ?: return false
        return runCatching {
            enabled_.value = readEnabled(handle)
            val callbackType = Class.forName(CALLBACK_CLASS)
            val callback = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType),
            ) { proxy, method, args ->
                when (method.name) {
                    "onAdvancedProtectionChanged" -> {
                        (args?.firstOrNull() as? Boolean)?.let(::publish)
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "OpenTaskerAdvancedProtectionCallback"
                    else -> null
                }
            }
            handle.type.getMethod(REGISTER_METHOD, Executor::class.java, callbackType)
                .invoke(handle.instance, context.mainExecutor, callback)
            registeredManager = handle
            registeredCallback = callback
            true
        }.getOrElse {
            registeredManager = null
            registeredCallback = null
            false
        }
    }

    /** Removes the callback when a host explicitly tears down the integration. */
    @Synchronized
    fun stop(): Boolean {
        val handle = registeredManager
        val callback = registeredCallback
        registeredManager = null
        registeredCallback = null
        enabled_.value = false
        if (handle == null || callback == null) return true
        return runCatching {
            val callbackType = Class.forName(CALLBACK_CLASS)
            handle.type.getMethod(UNREGISTER_METHOD, callbackType).invoke(handle.instance, callback)
            true
        }.getOrDefault(false)
    }

    /**
     * Pure mapping used for the Setup/health warning: warn only on API 36+ when APM is enabled.
     * Below API 36 the signal does not exist, so no warning is shown regardless of [apmEnabled].
     */
    internal fun shouldWarn(sdkInt: Int, apmEnabled: Boolean): Boolean =
        sdkInt >= ANDROID_16_API && apmEnabled

    internal fun supportsLiveCallback(sdkInt: Int): Boolean = sdkInt >= ANDROID_16_API

    private fun publish(value: Boolean) {
        val previous = enabled_.value
        enabled_.value = value
        if (previous != value) changes_.tryEmit(value)
    }

    private fun managerHandle(context: Context): ManagerHandle? = runCatching {
        val type = Class.forName(MANAGER_CLASS)
        val instance = context.getSystemService(ADVANCED_PROTECTION_SERVICE)
            ?: return@runCatching null
        if (!type.isInstance(instance)) return@runCatching null
        ManagerHandle(type, instance)
    }.getOrNull()

    private fun readEnabled(handle: ManagerHandle): Boolean =
        handle.type.getMethod(IS_ENABLED_METHOD).invoke(handle.instance) as? Boolean ?: false

    private data class ManagerHandle(
        val type: Class<*>,
        val instance: Any,
    )

    private const val ANDROID_16_API = 36
}
