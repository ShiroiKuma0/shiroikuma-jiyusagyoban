package com.opentasker.core.contexts

import android.content.Context
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.plugins.locale.LocalePluginHost
import com.opentasker.core.plugins.locale.LocalePluginRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

class LocalePluginConditionContextSource : ContextSource {
    override val type = "plugin"

    /**
     * One poll loop for the whole process, shared by every PLUGIN context.
     *
     * Each collector used to start its own loop that queried *all* registered subscriptions, so N
     * plugin contexts issued N x N ordered broadcasts every interval - each one holding a
     * broadcast and a timeout on the plugin app. The results are identical for every collector,
     * which is exactly what a shared upstream is for.
     */
    override fun events(app: Context): Flow<ContextEvent> = sharedEvents(app.applicationContext)

    private fun pollEvents(app: Context): Flow<ContextEvent> = callbackFlow {
        val host = LocalePluginHost(app)
        val pollJob = launch {
            while (isActive) {
                val subscribers = PluginConditionSubscriptions.snapshot()
                for (sub in subscribers) {
                    if (!isActive) break
                    val result = try {
                        host.queryCondition(
                            LocalePluginRequest(
                                packageName = sub.packageName,
                                bundleJson = sub.bundleJson,
                                timeoutMs = sub.timeoutMs,
                            )
                        )
                    } catch (ex: Exception) {
                        AppLogger.warn(TAG, "Plugin condition query failed for ${sub.packageName}: ${ex.message}")
                        continue
                    }
                    trySend(
                        ContextEvent(
                            type = "plugin",
                            matched = true,
                            metadata = mapOf(
                                "package" to sub.packageName,
                                "bundleJson" to sub.bundleJson,
                                "state" to result.state.serializedName,
                                "message" to result.message,
                            ),
                        )
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        awaitClose { pollJob.cancel() }
    }

    companion object {
        private const val TAG = "PluginConditionSource"
        private const val POLL_INTERVAL_MS = 30_000L

        private val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val sharedLock = Any()
        private var shared: Flow<ContextEvent>? = null

        private fun LocalePluginConditionContextSource.sharedEvents(app: Context): Flow<ContextEvent> =
            synchronized(sharedLock) {
                shared ?: pollEvents(app)
                    .shareIn(
                        scope = sharedScope,
                        // Stops polling once the last context unsubscribes, and survives a brief
                        // gap while the engine rebuilds matchers on a profile reload.
                        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                        replay = 0,
                    )
                    .also { shared = it }
            }
    }
}

data class PluginConditionSubscription(
    val packageName: String,
    val bundleJson: String = "{}",
    val timeoutMs: Long = 5_000,
)

object PluginConditionSubscriptions {
    private val subscriptions = mutableSetOf<PluginConditionSubscription>()

    @Synchronized
    fun register(sub: PluginConditionSubscription) {
        subscriptions.add(sub)
    }

    @Synchronized
    fun replaceAll(subs: Collection<PluginConditionSubscription>) {
        subscriptions.clear()
        subscriptions.addAll(subs)
    }

    @Synchronized
    fun clear() {
        subscriptions.clear()
    }

    @Synchronized
    fun snapshot(): List<PluginConditionSubscription> = subscriptions.toList()
}
