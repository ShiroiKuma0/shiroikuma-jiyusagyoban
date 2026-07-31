package com.opentasker.core.contexts

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.opentasker.core.engine.EngineShutdown

class QuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            // Unavailable rather than merely inactive while the app is stopped, so the tile shows the
            // truth instead of pretending a tap would do something.
            tile.state = if (EngineShutdown.isStopped(this)) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        if (EngineShutdown.refuse(this, "quick-settings tile")) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        val nowActive = tile.state != Tile.STATE_ACTIVE
        tile.state = if (nowActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
        QuickSettingsTileContextEvents.publishTileClicked(nowActive)
    }
}
