package com.jarvis.mobile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap from any screen starts JARVIS listening. */
class JarvisTile : TileService() {
    override fun onStartListening() {
        qsTile?.let {
            it.state = if (JarvisService.instance != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        JarvisService.instance?.toggle()
    }
}
