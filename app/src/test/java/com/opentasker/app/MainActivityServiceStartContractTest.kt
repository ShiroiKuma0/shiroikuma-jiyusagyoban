package com.opentasker.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityServiceStartContractTest {
    @Test
    fun mainActivityStartsAutomationForegroundService() {
        val source = listOf(
            File("src/main/java/com/opentasker/app/MainActivity.kt"),
            File("app/src/main/java/com/opentasker/app/MainActivity.kt"),
        ).first { it.exists() }.readText()

        assertTrue("MainActivity should start the automation foreground service", "startAutomationService()" in source)
        assertTrue("MainActivity should use ContextCompat.startForegroundService", "ContextCompat.startForegroundService" in source)
        assertTrue("MainActivity should target AutomationService", "AutomationService::class.java" in source)
    }
}
