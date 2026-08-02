package com.opentasker.core.contexts

import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsContextEventsTest {
    @Test
    fun smsEventCombinesPartsAndSanitizesMetadata() {
        val event = SmsContextEvents.buildSmsEvent(
            parts = listOf(
                SmsContextEvents.SmsPart(" +1 555 0100 ", " Your\n"),
                SmsContextEvents.SmsPart(null, " code is 123456 "),
            ),
            subscriptionId = 2,
            sdkInt = 36,
        )

        assertEquals("sms_received", event.metadata["event"])
        assertEquals("received", event.metadata["state"])
        assertEquals("sms", event.metadata["kind"])
        assertEquals("+1 555 0100", event.metadata["sender"])
        assertEquals("Your code is 123456", event.metadata["body"])
        assertEquals("2", event.metadata["subscriptionId"])
        assertEquals("not_applicable_before_android_17", event.metadata["otpProtection"])
    }

    @Test
    fun android17MetadataDisclosesPotentialOtpDelayWithoutClaimingDeliveryWasDelayed() {
        val event = SmsContextEvents.buildSmsEvent(
            parts = listOf(SmsContextEvents.SmsPart("555", "Code 1234")),
            sdkInt = 37,
        )

        assertEquals("standard_sms_otp_may_be_delayed_3h", event.metadata["otpProtection"])
        assertEquals("3", event.metadata["otpDelayHours"])
    }

    @Test
    fun senderAndBodyFiltersMatchSmsMetadata() {
        val event = SmsContextEvents.buildSmsEvent(
            parts = listOf(SmsContextEvents.SmsPart("BANK", "Your balance is ready")),
            sdkInt = 36,
        )
        val spec = ContextSpec(
            type = ContextType.EVENT,
            config = mapOf("event" to "sms_received", "sender" to "bank", "body" to "balance"),
        )

        assertTrue(ContextMatchEvaluator.matches(spec, event))
        assertFalse(ContextMatchEvaluator.matches(spec.copy(config = spec.config + ("sender" to "SHOP")), event))
    }

    @Test
    fun mmsEventCarriesTransportMetadataWithoutRawPayload() {
        val event = SmsContextEvents.buildMmsEvent(
            contentType = "application/vnd.wap.mms-message",
            transactionId = "tx-123",
            sdkInt = 37,
        )

        assertEquals("mms", event.metadata["kind"])
        assertEquals("application/vnd.wap.mms-message", event.metadata["contentType"])
        assertEquals("tx-123", event.metadata["transactionId"])
        assertEquals("", event.metadata["body"])
        assertFalse(event.metadata.containsKey("rawPayload"))
    }
}
