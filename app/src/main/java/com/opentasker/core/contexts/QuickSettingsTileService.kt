package com.opentasker.core.contexts

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val nowActive = tile.state != Tile.STATE_ACTIVE
        tile.state = if (nowActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
        QuickSettingsTileContextEvents.publishTileClicked(nowActive)
    }
}
