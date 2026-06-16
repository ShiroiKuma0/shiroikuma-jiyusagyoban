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

class LocalePluginConditionContextSource : ContextSource {
    override val type = "plugin"

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
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
