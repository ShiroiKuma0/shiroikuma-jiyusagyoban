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
import androidx.annotation.RequiresApi
import com.opentasker.app.R
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
                manager.myAssociations.map { info -> CompanionAssociation(info.id.toString(), label(context, info)) }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.map { address -> CompanionAssociation(address, address) }
            }
        }
    }

    /** False when the device couldn't be removed, so Setup can say so instead of claiming it was. */
    fun disassociate(context: Context, association: CompanionAssociation): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return unlessRefused("Removing a paired device", false) {
            if (Build.VERSION.SDK_INT >= 33) {
                val id = association.id.toIntOrNull() ?: return@unlessRefused false
                manager.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                manager.disassociate(association.id)
            }
            true
        }
    }

    /**
     * The service refuses a call with IllegalStateException (Android 8 to 12, when the manifest
     * lacks the companion_device_setup feature), SecurityException (an association that isn't
     * ours) or IllegalArgumentException (an id it no longer knows, such as a second tap on Remove
     * before the list refreshes). Any of them used to crash whichever screen asked, which was
     * Setup and Settings (#20).
     */
    private inline fun <T> unlessRefused(what: String, refused: T, call: () -> T): T = try {
        call()
    } catch (error: IllegalStateException) {
        AppLogger.warn(TAG, "$what was refused", error)
        refused
    } catch (error: SecurityException) {
        AppLogger.warn(TAG, "$what was refused", error)
        refused
    } catch (error: IllegalArgumentException) {
        AppLogger.warn(TAG, "$what was refused", error)
        refused
    }

    /** The name Android shows for the device, or a translated fallback naming the association. */
    @RequiresApi(33)
    private fun label(context: Context, info: AssociationInfo): String =
        info.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.setup_companion_association_label, info.id)

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
                                CompanionAssociation(associationInfo.id.toString(), label(activity, associationInfo)),
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
