@file:Suppress("DEPRECATION")

package com.opentasker.core.contexts

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.regex.Pattern

data class CompanionAssociation(
    val id: String,
    val label: String,
)

sealed interface CompanionAssociationResult {
    data class Found(val intentSender: IntentSender) : CompanionAssociationResult
    data class Created(val association: CompanionAssociation) : CompanionAssociationResult
    data class Failed(val message: String) : CompanionAssociationResult
}

/** Small API-level adapter for user-confirmed CompanionDeviceManager associations. */
object CompanionDeviceAssociation {
    fun list(context: Context): List<CompanionAssociation> {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) {
            manager.myAssociations.map { info ->
                CompanionAssociation(info.id.toString(), "Association ${info.id}")
            }
        } else {
            @Suppress("DEPRECATION")
            manager.associations.map { address -> CompanionAssociation(address, address) }
        }
    }

    fun disassociate(context: Context, association: CompanionAssociation) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            association.id.toIntOrNull()?.let(manager::disassociate)
        } else {
            @Suppress("DEPRECATION")
            manager.disassociate(association.id)
        }
    }

    fun associate(
        activity: Activity,
        callback: (CompanionAssociationResult) -> Unit,
    ): Boolean {
        val manager = activity.getSystemService(CompanionDeviceManager::class.java) ?: return false
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothDeviceFilter.Builder()
                    .setNamePattern(Pattern.compile(".*"))
                    .build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= 33) {
            manager.associate(
                request,
                activity.mainExecutor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        callback(
                            CompanionAssociationResult.Created(
                                CompanionAssociation(associationInfo.id.toString(), "Association ${associationInfo.id}"),
                            ),
                        )
                    }

                    override fun onFailure(error: CharSequence?) {
                        callback(CompanionAssociationResult.Failed(error?.toString().orEmpty()))
                    }
                },
            )
        } else {
            @Suppress("DEPRECATION")
            manager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(intentSender: IntentSender) {
                        callback(CompanionAssociationResult.Found(intentSender))
                    }

                    override fun onFailure(error: CharSequence?) {
                        callback(CompanionAssociationResult.Failed(error?.toString().orEmpty()))
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }
        return true
    }
}
