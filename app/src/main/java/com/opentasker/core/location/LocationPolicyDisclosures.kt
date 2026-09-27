package com.opentasker.core.location

object LocationPolicyDisclosures {
    const val foregroundSetupBody: String =
        "Needed for live Location contexts and WiFi SSID visibility on modern Android. " +
            "Approximate access can emit lower-precision fixes; background precision cannot exceed the foreground precision the user grants."

    /** Shown in place of [foregroundSetupBody] when only approximate location is actually granted. */
    const val foregroundSetupBodyApproximate: String =
        "Only approximate location is allowed right now. WiFi network names and small geofences need " +
            "precise location, so tap Request and choose Precise."

    fun backgroundSetupBody(apiLevel: Int): String {
        val settingsPath = if (apiLevel >= 30) {
            "Android 11+ grants background location from app settings instead of the foreground permission dialog."
        } else {
            "Android 10 can route users from the permission flow to the app's location settings."
        }
        return "Needed only when geofence automations must keep evaluating after 白い熊 自由作業盤 is no longer visible. " +
            "$settingsPath Users can decline and keep using foreground-only automations. Public reliability still needs device verification."
    }

    /**
     * Whether the Location context source is ready, in the same terms as Setup's location row:
     * precise access, with a location provider switched on. Approximate fixes still flow, but they
     * are too coarse for a small geofence, and Setup already calls that missing.
     */
    fun sourceReady(precise: Boolean, providerEnabled: Boolean): Boolean = precise && providerEnabled

    fun sourceSetupDetail(
        foreground: Boolean,
        precise: Boolean,
        background: Boolean,
        providerEnabled: Boolean,
        apiLevel: Int,
    ): String =
        when {
            foreground && !providerEnabled ->
                "Location permission is granted, but GPS and network location providers are disabled."
            foreground && background && precise ->
                "Live FOSS location source is registered with precise foreground and background location permission." +
                    android14ForegroundServiceNote(apiLevel)
            foreground && background ->
                "Live FOSS location source is registered with approximate foreground and background location permission. " +
                    "Background precision follows the user's foreground precision choice." +
                    android14ForegroundServiceNote(apiLevel)
            foreground && precise ->
                "Live FOSS location source can emit while 白い熊 自由作業盤 is running; background location from app settings is still needed for long-running geofence reliability."
            foreground ->
                "Live FOSS location source can emit approximate fixes while 白い熊 自由作業盤 is running; background location from app settings and precise access are still missing."
            else ->
                "Foreground location permission is missing; live location contexts remain blocked until setup is complete."
        }

    private fun android14ForegroundServiceNote(apiLevel: Int): String =
        if (apiLevel >= 34) {
            " Android 14+ location foreground-service use remains gated to these granted location prerequisites."
        } else {
            ""
        }
}
