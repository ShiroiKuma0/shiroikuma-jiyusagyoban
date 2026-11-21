@file:Suppress("DEPRECATION")

package com.opentasker.core.contexts

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.companion.ObservingDevicePresenceRequest
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
    /** The device's Bluetooth address, which Android 12 to 15 watch it by, or null when Android doesn't say. */
    val address: String? = null,
)

/** How Android is asked to report a paired device coming into range and leaving it. */
internal sealed interface PresenceObservation {
    data class ByAddress(val address: String) : PresenceObservation
    data class ByAssociationId(val id: Int) : PresenceObservation
}

/**
 * Android 12 to 15 watch a device by its Bluetooth address. Android 16 deprecates that for a request
 * naming the association id. Below 12 there is nothing to ask, and nothing binds the presence service.
 */
internal fun presenceObservation(sdkInt: Int, association: CompanionAssociation): PresenceObservation? = when {
    sdkInt < 31 -> null
    sdkInt >= 36 -> association.id.toIntOrNull()?.let(PresenceObservation::ByAssociationId)
    else -> association.address?.takeIf(String::isNotBlank)?.let(PresenceObservation::ByAddress)
}

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
                    CompanionAssociation(info.id.toString(), label(context, info), info.deviceMacAddress?.toString())
                }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.map { address -> CompanionAssociation(address, address, address) }
            }
        }
    }

    /**
     * Asks Android to bind [CompanionPresenceService] when each of [associations] comes into range or
     * leaves. Android only does that for an association the app asked it to watch, so without this a
     * `companion_presence` profile never fired. Asking again for one already watched changes nothing.
     */
    fun observePresence(context: Context, associations: List<CompanionAssociation>) {
        if (Build.VERSION.SDK_INT < 31) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        associations.forEach { association ->
            val observation = presenceObservation(Build.VERSION.SDK_INT, association) ?: return@forEach
            unlessRefused("Watching a paired device", Unit) { watch(manager, observation, start = true) }
        }
    }

    @RequiresApi(31)
    private fun watch(manager: CompanionDeviceManager, observation: PresenceObservation, start: Boolean) {
        try {
            when (observation) {
                is PresenceObservation.ByAddress ->
                    if (start) manager.startObservingDevicePresence(observation.address) else manager.stopObservingDevicePresence(observation.address)
                is PresenceObservation.ByAssociationId -> if (Build.VERSION.SDK_INT >= 36) {
                    val request = ObservingDevicePresenceRequest.Builder().setAssociationId(observation.id).build()
                    if (start) manager.startObservingDevicePresence(request) else manager.stopObservingDevicePresence(request)
                }
            }
        } catch (error: DeviceNotAssociatedException) {
            AppLogger.warn(TAG, "A paired device to watch is no longer paired", error)
        }
    }

    /** False when the device couldn't be removed, so Setup can say so instead of claiming it was. */
    fun disassociate(context: Context, association: CompanionAssociation): Boolean {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        // A refused stop mustn't keep the device from being removed.
        if (Build.VERSION.SDK_INT >= 31) {
            presenceObservation(Build.VERSION.SDK_INT, association)?.let { observation ->
                unlessRefused("Unwatching a paired device", Unit) { watch(manager, observation, start = false) }
            }
        }
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
