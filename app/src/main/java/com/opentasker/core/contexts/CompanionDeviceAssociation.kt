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
import com.opentasker.core.logging.AppLogger
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
    private const val TAG = "OpenTasker.Companion"

    fun list(context: Context): List<CompanionAssociation> {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return unlessRefused("Listing paired devices", emptyList()) {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.myAssociations.map { info ->
                    CompanionAssociation(info.id.toString(), "Association ${info.id}")
                }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.map { address -> CompanionAssociation(address, address) }
            }
        }
    }

    fun disassociate(context: Context, association: CompanionAssociation) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        unlessRefused("Removing a paired device", Unit) {
            if (Build.VERSION.SDK_INT >= 33) {
                association.id.toIntOrNull()?.let(manager::disassociate)
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(association.id)
            }
        }
    }

    /**
     * The service refuses a call with IllegalStateException (Android 8 to 12, when the manifest
     * lacks the companion_device_setup feature) or SecurityException (an association that isn't
     * ours). Either one used to crash whichever screen asked, which was Setup and Settings (#20).
     */
    private inline fun <T> unlessRefused(what: String, refused: T, call: () -> T): T = try {
        call()
    } catch (error: IllegalStateException) {
        AppLogger.warn(TAG, "$what was refused", error)
        refused
    } catch (error: SecurityException) {
        AppLogger.warn(TAG, "$what was refused", error)
        refused
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
        return unlessRefused("Pairing a device", false) {
            startAssociation(manager, request, activity, callback)
            true
        }
    }

    private fun startAssociation(
        manager: CompanionDeviceManager,
        request: AssociationRequest,
        activity: Activity,
        callback: (CompanionAssociationResult) -> Unit,
    ) {
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
    }
}
