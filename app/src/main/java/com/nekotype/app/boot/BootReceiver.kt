package com.nekotype.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.sys.SysPower
import com.nekotype.app.util.NekoLog

/**
 * 开机自启（可选）：开机后若服务开启且「开机自启」开关打开，自动拉起悬浮服务。
 * 密码锁定开启时，开机自启强制生效（防杀后台：重启后自动复活）。
 *
 * 另外：开机后用 Shizuku 重新写入全部权限（无障碍 / 免电白名单 / 悬浮窗）。
 * 国产 ROM 重启后常把无障碍服务清掉，以前必须连电脑用 adb 手动恢复，现在开机自动写回。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            AppPrefs.serviceEnabled && (AppPrefs.autoStartEnabled || AppPrefs.lockEnabled)
        ) {
            FloatingButtonService.start(context)
            NekoLog.nav("开机自启：悬浮服务已拉起")

            // 开机后用 Shizuku 重新写入全部权限（后台线程；Shizuku 可能晚一点才起来，内部会等待最多 30s）
            try {
                Thread {
                    try {
                        val r = SysPower.restoreAllPrivilegesViaShizuku()
                        NekoLog.ok("开机权限恢复：" + r.replace("\n", " / "))
                    } catch (t: Throwable) {
                        NekoLog.warn("开机权限恢复失败：${t.javaClass.simpleName}: ${t.message}")
                    }
                }.start()
            } catch (_: Throwable) { }
        }
    }
}
