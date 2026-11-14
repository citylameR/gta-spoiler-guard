package com.chickenbuter.spoilerguard

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка в шторке быстрых настроек: быстро выключить защиту, если нужно что-то посмотреть. */
class ToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val p = Prefs.get(this)
        p.edit().putBoolean(Prefs.KEY_ENABLED, !Prefs.isEnabled(p)).apply()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val on = Prefs.isEnabled(Prefs.get(this))
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (on) "Защита вкл." else "Выключено"
        tile.updateTile()
    }
}
