package com.nekotype.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import com.nekotype.app.overlay.FloatingButtonService
import com.nekotype.app.prefs.AppPrefs
import com.nekotype.app.util.TamperGuard

class NekoTypeApp : Application() {

    companion object {
        lateinit var instance: NekoTypeApp
            private set
    }

    /** 系统默认崩溃处理器（崩溃自启后继续走系统逻辑，不吞崩溃） */
    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 防篡改检测：签名不匹配（重打包）或检测到 Hook 框架 → 标记，各入口拒绝运行
        val tampered = !TamperGuard.isSignatureValid(this) || TamperGuard.hasHookFramework()
        if (tampered != AppPrefs.tampered) {
            AppPrefs.tampered = tampered
        }
        // 崩溃自启（皆成同款，行为与样式开关控制）：进程崩溃时若服务在跑，
        // 用闹钟延时拉起服务，避免一次崩溃导致悬浮服务/隐藏模式永久失联
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (AppPrefs.crashRestartEnabled && AppPrefs.serviceEnabled) {
                    val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    // 闹钟触发拉起 FGS（Android 12 豁免场景），华为拦截概率更低
                    val pi = if (android.os.Build.VERSION.SDK_INT >= 26) {
                        PendingIntent.getForegroundService(
                            this, 2002,
                            Intent(this, FloatingButtonService::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        PendingIntent.getService(
                            this, 2002,
                            Intent(this, FloatingButtonService::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                    am.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pi)
                }
            } catch (_: Throwable) { }
            // 继续走系统默认处理（不吞崩溃，正常退出）
            originalHandler?.uncaughtException(thread, throwable)
        }
        // 应用主题（深色 / 浅色 / 跟随系统）
        val mode = when (AppPrefs.themeMode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
