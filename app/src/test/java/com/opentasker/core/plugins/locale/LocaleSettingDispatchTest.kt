package com.opentasker.core.plugins.locale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleSettingDispatchTest {
    @Test
    fun aReceiverWithoutAPermissionIsDeliverable() {
        assertTrue(LocaleSettingDispatch.allowed(requiredPermission = null, senderHoldsPermission = false))
        assertTrue(LocaleSettingDispatch.allowed(requiredPermission = "", senderHoldsPermission = false))
    }

    @Test
    fun aPermissionProtectedReceiverRequiresTheSenderGrant() {
        assertFalse(
            LocaleSettingDispatch.allowed(
                requiredPermission = "com.example.plugin.FIRE",
                senderHoldsPermission = false,
            ),
        )
        assertTrue(
            LocaleSettingDispatch.allowed(
                requiredPermission = "com.example.plugin.FIRE",
                senderHoldsPermission = true,
            ),
        )
    }

    @Test
    fun productionSendPathDoesNotUseReceiverPermissionArgument() {
        val source = listOf(
            java.nio.file.Path.of("src/main/java/com/opentasker/core/plugins/locale/LocalePluginHost.kt"),
            java.nio.file.Path.of("app/src/main/java/com/opentasker/core/plugins/locale/LocalePluginHost.kt"),
        ).first { java.nio.file.Files.exists(it) }.let { java.nio.file.Files.readString(it) }
        assertTrue(source.contains("requireDeliverable(intent)"))
        assertTrue(source.contains("appContext.sendBroadcast(intent)"))
        assertFalse(source.contains("sendBroadcast(intent, permission)"))
        assertTrue(source.contains("sendOrderedBroadcast("))
        assertTrue(source.contains("null,"))
    }
}
