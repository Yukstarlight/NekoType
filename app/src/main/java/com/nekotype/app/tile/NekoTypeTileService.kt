package com.nekotype.app.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs

/**
 * 快捷设置磁贴：下拉通知栏即可一键开关 NekoType 悬浮按钮服务。
 */
class NekoTypeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val enabled = !AppPrefs.serviceEnabled
        AppPrefs.serviceEnabled = enabled
        if (enabled) {
            FloatingButtonService.start(this)
        } else {
            FloatingButtonService.stop(this)
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = AppPrefs.serviceEnabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (on) "NekoType 运行中" else "NekoType"
        tile.updateTile()
    }
}
