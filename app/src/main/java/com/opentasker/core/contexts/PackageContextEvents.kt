package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object PackageContextEvents {
    private val packages = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<ContextEvent> = packages.asSharedFlow()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val eventName = when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) "package_replaced"
                    else "package_added"
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    "package_removed"
                }
                else -> return
            }
            packages.tryEmit(
                ContextEvent(
                    type = "event",
                    matched = true,
                    metadata = mapOf(
                        "event" to eventName,
                        "package" to pkg,
                    ),
                ),
            )
        }
    }

    fun intentFilter(): IntentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addDataScheme("package")
    }
}
