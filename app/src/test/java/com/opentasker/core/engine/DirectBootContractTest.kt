package com.opentasker.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootContractTest {
    @Test
    fun deviceProtectedStoreContainsOnlyOptInAndOnePendingPulse() {
        val source = repoFile("src/main/java/com/opentasker/core/engine/DirectBootTriggerStore.kt").readText()

        assertTrue(
            "Direct Boot state must use the device-protected DataStore file API",
            "deviceProtectedDataStoreFile" in source,
        )
        assertTrue("The armed set must be explicitly opt-in", "booleanPreferencesKey(\"enabled\")" in source)
        assertTrue(
            "The pending pulse must be a bounded timestamp",
            "longPreferencesKey(\"pending_time_tick_at\")" in source,
        )
        assertTrue("The store must collapse repeated pulses", "PENDING_TIME_TICK_AT] == null" in source)
        assertFalse("Direct Boot state must not open Room", "Room.databaseBuilder" in source)
        assertFalse("Direct Boot state must not read database encryption", "DatabaseSecurity" in source)
    }

    @Test
    fun directBootReceiversAreDeclaredAndLockedBranchCannotStartTheEngine() {
        val manifest = repoFile("src/main/AndroidManifest.xml").readText()
        val bootReceiver = repoFile("src/main/java/com/opentasker/core/engine/BootReceiver.kt").readText()
        val lockedBranch = bootReceiver
            .substringAfter("Intent.ACTION_LOCKED_BOOT_COMPLETED ->")
            .substringBefore("Intent.ACTION_USER_UNLOCKED ->")

        assertTrue("Boot receiver must be direct-boot aware", "android:directBootAware=\"true\"" in manifest)
        assertTrue(
            "Boot receiver must receive the locked-boot broadcast",
            "android.intent.action.LOCKED_BOOT_COMPLETED" in manifest,
        )
        assertTrue("Boot receiver must receive the unlock transition", "android.intent.action.USER_UNLOCKED" in manifest)
        assertTrue("The minute receiver must be direct-boot aware", "DirectBootTimeReceiver" in manifest)
        assertTrue("The locked branch must arm the device-protected scheduler", "armDirectBootTrigger(context)" in bootReceiver)
        assertFalse("The locked branch must not start the foreground service", "startForegroundService" in lockedBranch)
        assertFalse("The locked branch must not initialize the credential-protected app", "initializeAfterUnlock" in lockedBranch)
    }

    @Test
    fun normalStartupInitializesCredentialStateOnlyAfterUnlockAndReplaysAfterReload() {
        val application = repoFile("src/main/java/com/opentasker/app/OpenTaskerApp_NoHilt.kt").readText()
        val service = repoFile("src/main/java/com/opentasker/core/engine/AutomationService.kt").readText()

        assertTrue(
            "Application startup must gate credential initialization",
            "if (DirectBootTriggerStore.isUserUnlocked(this))" in application,
        )
        assertTrue("Credential initialization must have an explicit unlock guard", "fun initializeAfterUnlock()" in application)
        assertTrue("Room must remain in the post-unlock initializer", "Room.databaseBuilder" in application)
        assertTrue("The service must consume the pending direct-boot pulse", "consumePendingTimeTick" in service)
        assertTrue(
            "The pending pulse must be replayed after profile reload",
            service.indexOf("reloadProfiles()") < service.indexOf("consumePendingTimeTick"),
        )
    }

    @Test
    fun setupExplainsTheExactPreUnlockScope() {
        val setup = repoFile("src/main/java/com/opentasker/ui/screens/PermissionOnboardingScreen.kt").readText()
        val strings = repoFile("src/main/res/values/strings.xml").readText()

        assertTrue("Setup must expose the direct-boot toggle", "DirectBootSetupCard" in setup)
        // These pin the disclosure, not the engine's vocabulary. The 2026-09-03 plain-language
        // pass rewrote the copy: "app-owned minute time trigger" became "its own once-a-minute
        // clock" and "trigger family" became "kind of trigger". Each assertion moved to the new
        // spelling rather than being loosened to something both versions would satisfy.
        assertTrue(
            "Setup must say that only the app's own minute trigger is covered",
            "the only thing OpenTasker starts before you first unlock the phone is its own once-a-minute clock" in strings,
        )
        // Users are told "saved data" because "Room" is an implementation term that means nothing
        // to them.
        assertTrue(
            "Setup must disclose that saved data waits for unlock",
            "saved data, secrets" in strings,
        )
        assertTrue("Setup must disclose that secrets wait for unlock", "secrets" in strings)
        assertTrue(
            "Setup must say every other kind of trigger waits",
            "every other kind of trigger wait until you unlock" in strings,
        )
    }

    private fun repoFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }
}
