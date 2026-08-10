package com.opentasker.core.contexts

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.external.InternalTaskRunSource
import com.opentasker.core.engine.EngineShutdown

open class QuickSettingsTileService : TileService() {

    protected open val slot = QuickSettingsTileSlots.DEFAULT
    private val store by lazy { QuickSettingsTileStore(this) }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Fork: a stopped engine cannot run the bound task, so refuse before touching state rather
        // than flipping the tile and dispatching a run that will be dropped.
        if (EngineShutdown.refuse(this, "quick-settings tile")) {
            qsTile?.let { tile ->
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
            }
            return
        }
        val config = store.load(slot)
        if (config.taskId == null) {
            val configureIntent = Intent(this, QuickSettingsTileConfigActivity::class.java)
                .putExtra(QuickSettingsTileConfigActivity.EXTRA_SLOT, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        slot,
                        configureIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                fun launchLegacyPreferences() = startActivityAndCollapse(configureIntent)
                launchLegacyPreferences()
            }
            return
        }
        val updated = store.setState(slot, active = !config.active)
        updateTile(updated)
        QuickSettingsTileContextEvents.publishTileClicked(updated.active, slot)
        sendBroadcast(
            AutomationTargetContract.internalRunTaskIntent(
                context = applicationContext,
                taskId = requireNotNull(config.taskId),
                source = InternalTaskRunSource.QUICK_SETTINGS_TILE,
                variables = mapOf("tile_slot" to slot.toString(), "tile_active" to updated.active.toString()),
            ),
        )
    }

    private fun updateTile(config: QuickSettingsTileConfig = store.load(slot)) {
        qsTile?.let { tile ->
            val label = config.label.ifBlank { config.taskName.ifBlank { getString(com.opentasker.app.R.string.app_name) } }
            tile.label = label
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = config.subtitle.ifBlank { getString(com.opentasker.app.R.string.qs_tile_subtitle_default) }
            }
            tile.icon = config.icon(this)
            tile.state = when {
                // Unavailable rather than merely inactive while the app is stopped, so the tile shows
                // the truth instead of pretending a tap would do something.
                EngineShutdown.isStopped(this) -> Tile.STATE_UNAVAILABLE
                config.taskId == null -> Tile.STATE_UNAVAILABLE
                config.active -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.updateTile()
        }
    }
}

class QuickSettingsTileServiceSlot2 : QuickSettingsTileService() {
    override val slot: Int = 2
}

class QuickSettingsTileServiceSlot3 : QuickSettingsTileService() {
    override val slot: Int = 3
}

class QuickSettingsTileServiceSlot4 : QuickSettingsTileService() {
    override val slot: Int = 4
}
