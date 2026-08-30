package com.nekotype.app.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.ui.MainActivity
import com.nekotype.app.util.NekoLog

/**
 * 快捷设置磁贴：下拉通知栏即可一键开关 NekoType 悬浮按钮服务。
 * 密码锁定开启时，停止操作需在应用内验证密码。
 */
class NekoTypeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val enabled = !AppPrefs.serviceEnabled
        // 停止操作受密码锁定保护
        if (!enabled && AppPrefs.lockEnabled) {
            NekoLog.info("磁贴：密码锁定中，拉起验证")
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_STOP_REQUEST, true)
            }
            startActivityAndCollapse(i)
            return
        }
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
