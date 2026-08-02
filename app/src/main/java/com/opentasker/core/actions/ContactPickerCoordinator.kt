package com.opentasker.core.actions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsPickerSessionContract
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object ContactPickerCoordinator {
    private const val EXTRA_REQUEST_ID = "com.opentasker.contacts.REQUEST_ID"
    private const val EXTRA_QUERY = "com.opentasker.contacts.QUERY"
    private const val REQUEST_CODE = 7401
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<android.net.Uri?>>()

    fun nextRequestId(): String = "contacts-${nextId.incrementAndGet()}"

    fun register(requestId: String): CompletableDeferred<android.net.Uri?> =
        CompletableDeferred<android.net.Uri?>().also { pending[requestId] = it }

    fun launch(context: Context, requestId: String, query: String) {
        context.startActivity(
            Intent(context, ContactPickerActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun complete(requestId: String, uri: android.net.Uri?) {
        pending.remove(requestId)?.complete(uri)
    }

    fun remove(requestId: String) {
        pending.remove(requestId)?.cancel()
    }

    internal fun requestId(intent: Intent): String? = intent.getStringExtra(EXTRA_REQUEST_ID)

    internal fun pickerIntent(): Intent = Intent(ContactsPickerSessionContract.ACTION_PICK_CONTACTS).apply {
        putStringArrayListExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            arrayListOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ),
        )
        putExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 1)
    }

    internal const val pickerRequestCode: Int = REQUEST_CODE
}

/** Thin, non-exported bridge so a foreground user can grant a field-scoped Android 17 selection. */
class ContactPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 37) {
            finishPicker(null)
            return
        }
        runCatching {
            startActivityForResult(ContactPickerCoordinator.pickerIntent(), ContactPickerCoordinator.pickerRequestCode)
        }.onFailure {
            finishPicker(null)
        }
    }

    @Deprecated("Activity result callback is retained for the platform picker bridge.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ContactPickerCoordinator.pickerRequestCode) {
            finishPicker(data?.data.takeIf { resultCode == RESULT_OK })
        }
    }

    private fun finishPicker(uri: android.net.Uri?) {
        intent?.let { ContactPickerCoordinator.complete(ContactPickerCoordinator.requestId(it).orEmpty(), uri) }
        finish()
    }
}
