package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Argument validation for the Write Setting action.
 *
 * Everything here runs before the action touches a Settings table, which is the point: the Global
 * and Secure tables are writable only once a user has granted WRITE_SECURE_SETTINGS over a cable,
 * and a malformed name reaching that call is a write to somewhere nobody chose.
 */
class SettingsWriteTest {

    private fun args(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    private fun valid(vararg pairs: Pair<String, String>): SettingsWriteRequest.Valid {
        val parsed = parseSettingsWrite(args(*pairs))
        assertTrue("expected a valid request, got $parsed", parsed is SettingsWriteRequest.Valid)
        return parsed as SettingsWriteRequest.Valid
    }

    private fun rejection(vararg pairs: Pair<String, String>): String {
        val parsed = parseSettingsWrite(args(*pairs))
        assertTrue("expected a rejection, got $parsed", parsed is SettingsWriteRequest.Rejected)
        return (parsed as SettingsWriteRequest.Rejected).message
    }

    @Test
    fun eachTableNameSelectsItsOwnTable() {
        assertEquals(SettingsTable.GLOBAL, valid("table" to "global", "key" to "airplane_mode_on", "value" to "1").table)
        assertEquals(SettingsTable.SECURE, valid("table" to "secure", "key" to "location_mode", "value" to "3").table)
        assertEquals(SettingsTable.SYSTEM, valid("table" to "system", "key" to "screen_off_timeout", "value" to "60000").table)
    }

    @Test
    fun theTableNameIsCaseAndSpaceInsensitive() {
        assertEquals(SettingsTable.SECURE, valid("table" to "  Secure ", "key" to "location_mode", "value" to "3").table)
    }

    @Test
    fun anUnknownTableIsRefusedAndTheMessageNamesTheChoices() {
        val message = rejection("table" to "settings", "key" to "location_mode", "value" to "3")

        assertTrue(message, "global" in message && "secure" in message && "system" in message)
    }

    @Test
    fun aMissingTableIsRefused() {
        assertTrue(rejection("key" to "location_mode", "value" to "3").isNotEmpty())
    }

    @Test
    fun aSettingNameKeepsToTheCharactersAndroidUses() {
        assertEquals("location_mode", valid("table" to "secure", "key" to "location_mode", "value" to "3").key)
        assertEquals("a.b_c9", valid("table" to "secure", "key" to " a.b_c9 ", "value" to "1").key)

        listOf("", "Location_Mode", "location mode", "location-mode", "location/mode", "../secure", "a".repeat(65))
            .forEach { key ->
                val parsed = parseSettingsWrite(args("table" to "secure", "key" to key, "value" to "1"))
                assertTrue("\"$key\" should not be accepted as a setting name", parsed is SettingsWriteRequest.Rejected)
            }
    }

    @Test
    fun aNameOfExactlySixtyFourCharactersIsStillAccepted() {
        val key = "a".repeat(64)

        assertEquals(key, valid("table" to "secure", "key" to key, "value" to "1").key)
    }

    @Test
    fun theValueIsBoundedButAnEmptyOneIsAllowed() {
        assertEquals("", valid("table" to "secure", "key" to "location_mode", "value" to "").value)
        val longest = "9".repeat(MAX_SETTINGS_VALUE_CHARS)
        assertEquals(longest, valid("table" to "secure", "key" to "location_mode", "value" to longest).value)

        val message = rejection("table" to "secure", "key" to "location_mode", "value" to "9".repeat(MAX_SETTINGS_VALUE_CHARS + 1))
        assertTrue(message, MAX_SETTINGS_VALUE_CHARS.toString() in message)
    }

    @Test
    fun aMissingValueIsRefusedRatherThanTreatedAsEmpty() {
        assertTrue(rejection("table" to "secure", "key" to "location_mode").isNotEmpty())
    }

    /**
     * The escalation this action must not become.
     *
     * WRITE_SECURE_SETTINGS is granted once, for one reason, and then persists. A profile imported
     * afterwards reaches this action behind a single generic device-control acknowledgement, which
     * is nowhere near consent for handing an arbitrary package accessibility privileges, turning
     * off the package verifier, or allowing installs from unknown sources. Android shows no dialog
     * for any of those when the setting is written directly.
     */
    @Test
    fun namesThatControlWhatOtherAppsMayDoAreRefused() {
        listOf(
            "enabled_accessibility_services",
            "accessibility_enabled",
            "enabled_notification_listeners",
            "enabled_notification_policy_access_packages",
            "default_input_method",
            "enabled_input_methods",
            "package_verifier_enable",
            "verifier_verify_adb_installs",
            "adb_enabled",
            "development_settings_enabled",
            "install_non_market_apps",
            "device_provisioned",
            "user_setup_complete",
            "lock_pattern_autolock",
            "lockscreen.disabled",
            "location_providers_allowed",
            "always_on_vpn_lockdown",
            // Roles: naming the app that holds one is the same escalation by another route.
            "sms_default_application",
            "assistant",
            "voice_interaction_service",
            "voice_recognition_service",
            "autofill_service",
            "nfc_payment_default_component",
            // Traffic redirection and lifting the platform's own restrictions on private APIs.
            "hidden_api_blacklist_exemptions",
            "global_http_proxy_host",
            "http_proxy",
            "private_dns_specifier",
            "private_dns_mode",
            "captive_portal_server",
            "trust_agent_configuration",
            "lock_screen_allow_trust_agent_to_unlock",
        ).forEach { key ->
            SettingsTable.entries.forEach { table ->
                val parsed = parseSettingsWrite(
                    args("table" to table.wireValue, "key" to key, "value" to "1"),
                )
                assertTrue(
                    "\"$key\" must not be writable through ${table.wireValue}, got $parsed",
                    parsed is SettingsWriteRequest.Rejected,
                )
            }
        }
    }

    @Test
    fun anOrdinarySettingIsStillWritable() {
        listOf("location_mode", "screen_off_timeout", "screen_brightness", "font_scale")
            .forEach { key ->
                assertEquals(key, valid("table" to "secure", "key" to key, "value" to "1").key)
            }
    }

    /**
     * The refusal has to carry the command, because there is no other way to grant this: no
     * runtime dialog exists, and a user who only sees "permission denied" has nowhere to go.
     */
    @Test
    fun theGrantCommandNamesTheInstalledPackage() {
        val command = secureSettingsGrantCommand("com.opentasker.app.debug")

        assertEquals(
            "adb shell pm grant com.opentasker.app.debug android.permission.WRITE_SECURE_SETTINGS",
            command,
        )
    }
}
