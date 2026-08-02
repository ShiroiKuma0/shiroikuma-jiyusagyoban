package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bridges SMS_RECEIVED and WAP_PUSH_RECEIVED into sanitized `event=sms_received` pulses. */
object SmsContextEvents {
    const val EVENT_SMS_RECEIVED = "sms_received"
    const val STATE_RECEIVED = "received"
    const val KIND_SMS = "sms"
    const val KIND_MMS = "mms"

    private const val MAX_TEXT_CHARS = 240
    private const val ANDROID_17_API = 37
    private const val EXTRA_SUBSCRIPTION = "subscription"
    private const val EXTRA_TRANSACTION_ID = "transactionId"

    private val events_ = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    val receiver = SmsReceivedReceiver()

    /** Pure input used by tests and by the Android PDU adapter. */
    data class SmsPart(
        val sender: String?,
        val body: String?,
    )

    fun publishFromIntent(intent: Intent, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        val event = when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val parts = runCatching {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                        ?.map { SmsPart(it.displayOriginatingAddress, it.messageBody) }
                        .orEmpty()
                }.getOrDefault(emptyList())
                buildSmsEvent(
                    parts = parts,
                    subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1).takeIf { it >= 0 },
                    sdkInt = sdkInt,
                )
            }
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> buildMmsEvent(
                contentType = intent.type.orEmpty(),
                transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID).orEmpty(),
                subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION, -1).takeIf { it >= 0 },
                sdkInt = sdkInt,
            )
            else -> return false
        }
        return events_.tryEmit(event)
    }

    fun buildSmsEvent(
        parts: List<SmsPart>,
        subscriptionId: Int? = null,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): ContextEvent {
        val sender = parts.firstOrNull { !it.sender.isNullOrBlank() }?.sender
        val body = parts.joinToString(separator = "") { it.body.orEmpty() }
        return buildEvent(
            kind = KIND_SMS,
            sender = sender,
            body = body,
            messageCount = parts.size,
            subscriptionId = subscriptionId,
            otpProtection = otpProtection(sdkInt),
        )
    }

    fun buildMmsEvent(
        contentType: String,
        transactionId: String,
        subscriptionId: Int? = null,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): ContextEvent = buildEvent(
        kind = KIND_MMS,
        sender = null,
        body = null,
        messageCount = 1,
        subscriptionId = subscriptionId,
        contentType = contentType,
        transactionId = transactionId,
        otpProtection = otpProtection(sdkInt),
    )

    private fun buildEvent(
        kind: String,
        sender: String?,
        body: String?,
        messageCount: Int,
        subscriptionId: Int?,
        contentType: String? = null,
        transactionId: String? = null,
        otpProtection: String,
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", EVENT_SMS_RECEIVED)
            put("state", STATE_RECEIVED)
            put("kind", kind)
            put("sender", sanitizeText(sender))
            put("body", sanitizeText(body))
            put("messageCount", messageCount.toString())
            put("otpProtection", otpProtection)
            put("otpDelayHours", if (otpProtection == OTP_PROTECTED) "3" else "0")
            subscriptionId?.let { put("subscriptionId", it.toString()) }
            contentType?.takeIf(String::isNotBlank)?.let { put("contentType", sanitizeText(it)) }
            transactionId?.takeIf(String::isNotBlank)?.let { put("transactionId", sanitizeText(it)) }
        },
    )

    fun sanitizeText(value: String?): String = value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_TEXT_CHARS)
        .orEmpty()

    fun otpProtection(sdkInt: Int): String = if (sdkInt >= ANDROID_17_API) OTP_PROTECTED else OTP_UNPROTECTED

    private const val OTP_PROTECTED = "standard_sms_otp_may_be_delayed_3h"
    private const val OTP_UNPROTECTED = "not_applicable_before_android_17"
}

class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SmsContextEvents.publishFromIntent(intent)
    }
}
