package com.nekotype.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.NekoLog

/**
 * 开机自启（可选）：开机后若服务开启且「开机自启」开关打开，自动拉起悬浮服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            AppPrefs.serviceEnabled && AppPrefs.autoStartEnabled
        ) {
            FloatingButtonService.start(context)
            NekoLog.nav("开机自启：悬浮服务已拉起")
        }
    }
}
