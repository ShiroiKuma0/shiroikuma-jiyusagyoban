package com.opentasker.core.contexts

import android.content.Context
import com.opentasker.core.plugins.locale.LocalePluginRequestQueryEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Real EventContextSource backed by explicit app-owned event bridges.
 *
 * Supported events:
 *   - "notification": notification posted (requires NotificationListenerService)
 *   - "calendar": local CalendarProvider event windows (requires READ_CALENDAR)
 *   - "sun_tick": local minute tick used by sunrise/sunset event filters
 *   - "nfc": NFC tag scan
 *   - "bluetooth": Bluetooth device connected/disconnected
 *   - "locale_request_query": Locale condition plugin requested a host query
 *   - "boot_completed": manifest boot receiver restarted the engine
 *   - "tile_clicked": Quick Settings tile toggled
 */
class EventContextSourceImpl : ContextSource {
    override val type = "event"

    override fun events(app: Context): Flow<ContextEvent> = merge(
        NotificationContextEvents.events,
        NfcContextEvents.events,
        BootContextEvents.events,
        CalendarSunContextEvents.events(app),
        LocalePluginRequestQueryEvents.events(app),
        QuickSettingsTileContextEvents.events,
        ShakeContextEvents.events,
        PackageContextEvents.events,
        BluetoothContextEvents.events,
        BroadcastContextEvents.events,
        OrientationContextEvents.events,
        AppForegroundChangedContextEvents.events,
        HardwareKeyContextEvents.events,
    )
}
